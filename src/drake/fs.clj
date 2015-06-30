(ns drake.fs
  (:refer-clojure :exclude [file-seq])
  (:require [fs.core :as fs]
            [drake.plugins :as plugins]
            [hdfs.core :as hdfs]
            [clojure.string :as s :refer [join split]]
            [aws.sdk.s3 :as s3]
            [drake-interface.core :as di]
            [slingshot.slingshot :refer [throw+]]
            [drake.shell :refer [shell]]
            [drake.options :refer [*options*]])
  (:import org.apache.hadoop.conf.Configuration
           (org.apache.hadoop.fs Path FileStatus)
           java.io.File))

(def drake-ignore "Names of files or directories to be ignored by Drake"
  #{"_logs"})

(defn split-path
  "Returns a tuple: prefix (possibly empty) and path."
  [path]
  (let [splt (split path #":" -1)]
    (if (= (count splt) 1)
      ["file" (first splt)]
      [(first splt) (join ":" (rest splt))])))

(defn make-path
  "The reverse of split-path"
  [prefix path]
  (str prefix (when (seq prefix) ":") path))

(defn path-fs
  "Returns path's filesystem prefix (or an empty string if not specified)."
  [path]
  (first (split-path path)))

(defn path-filename
  "Returns the path-part of path, without the filesystem prefix."
  [path]
  (second (split-path path)))

(defn should-ignore? [path]
  (drake-ignore (last (split path #"/"))))

(defn remove-extra-slashes
  "Removes duplicate and trailing slashes from the filename."
  [filename]
  (s/replace filename #"/(?=/|$)" ""))

(defn assert-files-exist [fs files]
  (doseq [f files]
    (when (not (di/exists? fs f))
      (throw+ {:msg (str "file not found: " f)}))))

(defn file-info-impl [fs path]
  {:path path
   :mod-time (di/mod-time fs path)
   :directory (di/directory? fs path)})

(defn file-info-seq-impl [fs path]
  (map #(di/file-info fs %) (di/file-seq fs path)))

(defn data-in?-impl [fs path]
  (seq (di/file-info-seq fs path)))

(def file-info-impls
  {:file-info file-info-impl
   :file-info-seq file-info-seq-impl
   :data-in? data-in?-impl})

;; ----- Local FS -----------

(deftype LocalFileSystem [])

(extend LocalFileSystem
  di/FileSystem
  (merge
    file-info-impls
    { :exists?
      (fn [_ path]
        (fs/exists? (fs/file path)))

      :directory?
      (fn [_ path]
        (fs/directory? (fs/file path)))

      :mod-time
      (fn [_ path]
        (fs/mod-time path))

      :file-seq
      (fn [this path]
        (let [f (fs/file path)]
          (if (or (not (.exists f)) (should-ignore? (.getName f)))
            []
            (if-not (.isDirectory f)
            [(.getPath f)]
            (mapcat #(di/file-seq this (.getPath ^File %)) (.listFiles f))))))

      :normalized-filename
      (fn [_ path]
        (str (fs/normalized-path path)))

      :rm
      (fn [_ path]
        ;; TODO(artem)
        ;; This is dirty, we probably should re-implement this using syscalls
        (shell "rm" "-rf" path :use-shell true :die true))

      :mv
      (fn [_ from to]
        ;; TODO(artem)
        ;; This is dirty, we probably should re-implement this using syscalls
        (shell "mv" from to :use-shell true :die true))}))

;; ----- HDFS -----------

;; TODO(artem)
;; Support fully qualified filenames in Drake, such as
;; hdfs://n01:9000/tmp/drake-test/hdfs_1

(defn get-hadoop-conf-file-or-fail
  "Returns the full path to Hadoop config as a File, or throws an Exception indicating a
   problem finding the file.

   Prefers the HADOOP_HOME environment variable to indicate Hadoop's home directory for
   configuration, in which case the file [HADOOP_HOME]/conf/core-site.xml is verified
   for existance. If it exists, it's returned as a File. Otherwise an error is thrown.

   If there is no HADOOP_HOME defined, the file /etc/hadoop/conf/core-site.xml is
   expected to exist. If it exists, it's returned as a File. Otherwise an error is thrown."
  []
  (let [hadoop-home (get (System/getenv) "HADOOP_HOME")
        conf-file (fs/file (or hadoop-home "/etc/hadoop")
                           "conf/core-site.xml")]
    (if (fs/exists? conf-file)
      conf-file
      (throw+ {:msg (format "Hadoop configuration file %s doesn't exist" conf-file)}))))

(def ^:private hdfs-configuration
  (memoize #(doto (Configuration.)
              (.addResource (Path. (str (get-hadoop-conf-file-or-fail)))))))

(defn- remove-hdfs-prefix
  "Removes the prefix HDFS libraries may insert."
  [^String path]
  (let [prefix "hdfs://"]
    (assert (.startsWith path "hdfs://"))
    (let [spl (split (subs path (count prefix)) #"/")]
      (str "/" (join "/" (rest spl))))))

(defn- hdfs-file-info [^FileStatus status]
  {:path (remove-hdfs-prefix (str (.getPath status)))
   :mod-time (.getModificationTime status)
   :directory (.isDir status)})

(defn- ^org.apache.hadoop.fs.FileSystem hdfs-filesystem [path]
  ;; there's a bug in hdfs-clj's filesystem function (can't provide
  ;; configuration), so we're doing it manually here
  (org.apache.hadoop.fs.FileSystem/get (.toUri (hdfs/make-path path))
                                       (hdfs-configuration)))

(defn- hdfs-list-status [path]
  (map hdfs-file-info (.listStatus (hdfs-filesystem path)
                                   (hdfs/make-path path))))

(deftype HDFS [])

(extend HDFS
  di/FileSystem
  (merge
    file-info-impls
    { :exists?
      (fn [_ path]
        (.exists (hdfs-filesystem path) (hdfs/make-path path)))

      :directory?
      (fn [_ path]
        (.isDirectory (hdfs-filesystem path) (hdfs/make-path path)))

      :mod-time
      (fn [_ path]
        (.getModificationTime ^FileStatus (hdfs/file-status path)))

      :file-seq
      (fn [this path]
        (map :path (di/file-info-seq this path)))

      :file-info
      (fn [this path]
        (hdfs-file-info (hdfs/file-status path)))

      :file-info-seq
      (fn [this path]
        (if (or (not (di/exists? this path)) (should-ignore? path))
        []
        (let [statuses (hdfs-list-status path)]
          (if-not (di/directory? this path)
            statuses
            (for [{:keys [path directory] :as status} statuses
                  :when (not (should-ignore? path))
                  file (if directory
                         (di/file-info-seq this path)
                         [status])]
              file)))))

      :normalized-filename
      (fn [_ path]
        (remove-extra-slashes path))

      :rm
      (fn [_ path]
        ;; TODO(artem)
        ;; This is dirty, we probably should reimplement this using Hadoop API
        (shell "hadoop" "fs" "-rmr" path :use-shell true :die true))

      :mv
      (fn [_ from to]
        ;; TODO(artem)
        ;; This is dirty, we probably should reimplement this using Hadoop API
        (shell "hadoop" "fs" "-mv" from to :use-shell true :die true))}))

;; -------- S3 -----------
;; Support for Amazon AWS object store
;;
;; AWS credentials should be stored in a properties file
;; under the property names "access_key" and "secret_key".
;; The name of the file should be identified by using the
;; --aws-credentials command line option.

;; Generic code to load a properties file into a struct map
(defn- load-props
  "Loads a java style properties file into a struct map."
  [filename]
  (let [file (fs/file filename)]
    (when-not (fs/exists? file)
      (throw+ {:msg (format "unable to locate properties file %s" filename)}))
    (let [io (java.io.FileInputStream. file)
          prop (java.util.Properties.)]
      (.load prop io)
      (into {} prop))))
;; Load credentials from a properties file
(def ^:private s3-credentials
  (memoize #(if-not (*options* :aws-credentials)
              (throw+ {:msg (format (str "No aws-credentials file. "
                                         "Please specify a properties file "
                                         "containing aws credentials using "
                                         "the -s command line option."))})
              (let [props (load-props (*options* :aws-credentials))]
                {:access-key (props "access_key")
                 :secret-key (props "secret_key")}))))

(defn- s3-bucket-key
  "Returns a struct-map containing the bucket and key for a path"
  [path]
  (zipmap [:bucket, :key]
          (split (last (split path #"^/*"))
                 #"/" 2 )))

(defn- s3-object-to-info
  "Converts the elements the results of s3/list-objects
  into filesystem info objects"
  [{bucket :bucket key :key {last-mod :last-modified} :metadata}]
  {:path     (join "/" ["" bucket key])
   :directory (.endsWith ^String key "/")
   :mod-time  (.getTime ^java.util.Date last-mod)})

(deftype S3 [])

(extend S3
  di/FileSystem
  (merge
    file-info-impls
    { :exists?
      (fn [_ path]
        (let [{bucket :bucket key :key} (s3-bucket-key path)]
          (s3/object-exists? (s3-credentials) bucket key)))

      :directory?
      (fn [_ path]
        (.endsWith ^String path "/"))

      :mod-time
      (fn [_ path]
        (let [{bucket :bucket key :key} (s3-bucket-key path)
              ^java.util.Date last-mod (-> (s3-credentials)
                                           (s3/get-object-metadata bucket key)
                                           :last-modified)]
          (.getTime last-mod)))

      ;; S3 list-object api call by default will give
      ;; us everything to fill out the file-info-seq
      ;; call. This one calls that one and strips out the
      ;; extra data
      :file-seq
      (fn [this path]
        (map :path (di/file-info-seq this path)))

      ;; Not using the impl here as it would result in an
      ;; excessive number of api calls. We get all that we
      ;; need from list-objects anyway.
      :file-info-seq
      (fn [this path]
        (if (should-ignore? path)
          []
          ;; S3 is funny about directories - they do not really exist
          ;; so if we are looking to list the contents of a file
          ;; that does not seem to exist, we need to explicitly try
          ;; adding a separator character to it and calling s3/list-objects
          ;; on that.
          (if (di/directory? this path)
            ;; it is a directory and it exists, so
            ;; we should go do a list-object call
            (let [{bucket :bucket key :key} (s3-bucket-key path)]
              (map s3-object-to-info
                   (remove #(should-ignore? (:key %))
                           (:objects (s3/list-objects (s3-credentials)
                                                      bucket
                                                      {:prefix key})))))
            ;; not a directory
            (if (di/exists? this path)
              [(di/file-info this path)]
              (di/file-info-seq this (str path "/"))))))

      ;; Normalize file names for s3 objects need to look like
      ;; s3://bucket/path/to/object for compatibility for tools
      ;; like s3cmd.
      ;;
      ;; TODO(howech)
      ;; remove-extra-slashes is probably doing some other things
      ;; that could potentially be wrong in S3.
      :normalized-filename
      (fn [_ path]
        (join "/" ["" (remove-extra-slashes path)]))

      :rm
      (fn [_ path]
        (let [{bucket :bucket key :key} (s3-bucket-key)]
          (s3/delete-object (s3-credentials bucket key))))

      :mv
      (fn [_ from to]
        (let [{from-bucket :bucket from-key :key} (s3-bucket-key from)
              {to-bucket :bucket to-key :key} (s3-bucket-key to)]
          ;; ensure that moving to/from the same name is a null operation
          (when-not (and (= from-bucket to-bucket)
                         (= from-key to-key))
            ;; There are two flavors of the move command - one for
            ;; in the same bucket, the other for different buckets.
            ;; Might not be necessary to do this, but we try to call
            ;; the right one
            (if (= from-bucket to-bucket)
              (s3/copy-object (s3-credentials) from-bucket from-key to-key)
              (s3/copy-object (s3-credentials) from-bucket from-key to-bucket to-key))
            (s3/delete-object (s3-credentials) from-bucket from-key))))}))

;; ----- Mock FS --------
;; Mock file system does not support drake-ignore

(defrecord MockFileSystem [fs-data])

(extend MockFileSystem
  di/FileSystem
  (merge
    file-info-impls
    { :exists?
      (fn [this path]
        (contains? (:fs-data this) path))

      :directory?
      (fn [this path]
        (get-in this [:fs-data path :directory]))

      :mod-time
      (fn [this path]
        (if-not (di/exists? this path)
          (throw+ {:msg (str "file not found: " path)})
          (condp = (get-in this [:fs-data path :mod-time])
            :pre (Long/MIN_VALUE)
            :now (System/currentTimeMillis)
            (get-in this [:fs-data path :mod-time]))))

      :file-seq
      (fn [this path]
        (keys (filter (fn [[name opts]]
                        (and (not (:directory opts))
                             (.startsWith ^String name path)))
                      (:fs-data this))))

      :normalized-filename
      (fn [_ path]
        (remove-extra-slashes path))

      :rm
      (fn [_ _]
        (throw+ {:msg (str "rm is not implemented on mock filesystem")}))

      :mv
      (fn [_ _]
        (throw+ {:msg (str "mv is not implemented on mock filesystem")}))}))


(def ^:private MOCK-FILESYSTEM-DATA
  {"A" {:mod-time 108}
   "B" {:mod-time 107}
   "C" {:mod-time 106}
   "D" {:mod-time 105}
   "E" {:mod-time 104}
   "F" {:mod-time 103}
   "G" {:mod-time 102}
   "H" {:mod-time 101}
   "I" {:mod-time 100}
   "K" {:mod-time 99}
   "X" {:mod-time 207}
   "Y" {:mod-time 208 :directory true}
   "Y/A" {:mod-time 210}
   "Y/B" {:mod-time 212}
   "Y/C" {:mod-time 224}})

;; -------------------------------

(def ^:private FILESYSTEMS
  {"file" (LocalFileSystem.)
   "hdfs" (HDFS.)
   "s3" (S3.)
   "test" (MockFileSystem. MOCK-FILESYSTEM-DATA)})

(defn get-fs
  "Determines the filesystem by prefix, defaults to the local filesystem
   if the prefix is unknown."
  [path]
  (let [[prefix filename] (split-path path)
        fs (or (FILESYSTEMS prefix)
               (plugins/get-reified "drake.fs." prefix)
               (FILESYSTEMS "file"))]
    [fs prefix filename]))

(defn fs
  "Automatically determines the filesystem from the filename and dispatches
   the call to f."
  [f filename]
  (let [[system _ name] (get-fs filename)]
    (f system name)))

(defn normalized-path
  "Returns absolute path preserving the prefix."
  [path]
  (let [[filesystem prefix filename] (get-fs path)]
    (make-path prefix (di/normalized-filename filesystem filename))))

(defn pick-by-mod-time
  "Traverses the full directory tree starting at path, applies given
   transformation on the modification times, sorts the list according to
   the modification times and returns the first element. (Use identity
   transformation to return a minimum, or - to return the maximum mod-time
   file). Returns a file-info structure (see FileSystem/file-info)."
  [path transform]
  (apply min-key (comp transform :mod-time)
         (fs di/file-info-seq path)))

(defn oldest-in
  [path]
  (pick-by-mod-time path identity))

(defn newest-in
  [path]
  (pick-by-mod-time path -))
