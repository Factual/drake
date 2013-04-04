(ns drake.fs
  (:refer-clojure :exclude [file-seq])
  (:require [fs.core :as fs]
            [hdfs.core :as hdfs]
            [aws.sdk.s3 :as s3])
  (:use [slingshot.slingshot :only [throw+]]
        [clojure.string :only [join split]]
        drake.shell
        drake.options)
  (:import org.apache.hadoop.conf.Configuration
           org.apache.hadoop.fs.Path))

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
  (format "%s%s%s" prefix (if-not (empty? prefix) ":" "") path))

(defn path-fs
  "Return's path's filesystem prefix (or an empty string if not specified)."
  [path]
  (first (split-path path)))

(defn path-filename
  "Return's path's filesystem prefix (or an empty string if not specified)."
  [path]
  (second (split-path path)))

(defn should-ignore? [path]
  (drake-ignore (last (split path #"/"))))

(defn assert-files-exist [fs files]
  (doseq [f files]
    (when (not (.exists? fs f))
      (throw+ {:msg (str "file not found: " f)}))))

(defn remove-extra-slashes
  "Removes duplicate and trailing slashes from the filename."
  [filename]
  (let [spl (split filename #"/" -1)]
    (str (if (empty? (first spl)) "/" "")
         (join "/" (filter (complement empty?) spl)))))

(defprotocol FileSystem
  (exists? [_ path])
  (directory? [_ path])
  (mod-time [_ path])
  (file-seq [_ path])
  (file-info [_ path])
  (file-info-seq [_ path])
  (data-in? [_ path])
  (normalized-filename [_ path])
  (rm [_ path])
  (mv [_ from to]))

;; TODO(artem)
;; I tried a lot of things but still don't know how to create a common
;; ancestor in Clojure and inherit two other classes from it
;; TODO(artem)
;; We have to figure it out somehow, since adding direct calls to these
;; functions in all descendants is a bit tiring.
(defn file-info-impl [fs path]
  {:path path
   :mod-time (.mod-time fs path)
   :directory (.directory? fs path)})

(defn file-info-seq-impl [fs path]
  (map #(.file-info fs %) (.file-seq fs path)))

(defn data-in?-impl [fs path]
  (not (empty? (.file-info-seq fs path))))

;; ----- Local FS -----------

(deftype LocalFileSystem []
  FileSystem
  (exists? [_ path]
    (fs/exists? (fs/file path)))
  (directory? [_ path]
    (fs/directory? (fs/file path)))
  (mod-time [_ path]
    (fs/mod-time path))
  (file-seq [this path]
    (let [f (fs/file path)]
      (if (or (not (.exists f)) (should-ignore? (.getName f)))
        []
        (if-not (.isDirectory f)
          [(.getPath f)]
          (mapcat #(file-seq this (.getPath %)) (.listFiles f))))))
  (file-info [this path]
    (file-info-impl this path))
  (file-info-seq [this path]
    (file-info-seq-impl this path))
  (data-in? [this path]
    (data-in?-impl this path))
  (normalized-filename [_ path]
    (str (fs/normalized-path path)))
  (rm [_ path]
    ;; TODO(artem)
    ;; This is dirty, we probably should re-implement this using syscalls
    (shell "rm" "-rf" path :use-shell true :die true))
  (mv [_ from to]
    ;; TODO(artem)
    ;; This is dirty, we probably should re-implement this using syscalls
    (shell "mv" from to :use-shell true :die true)))

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
        conf-file (if hadoop-home
                    (fs/file hadoop-home "conf/core-site.xml")
                    (fs/file "/etc/hadoop/conf/core-site.xml"))]
    (if (fs/exists? conf-file)
      conf-file
      (throw+ {:msg (format "Hadoop configuration file %s doesn't exist" conf-file)}))))

(def ^:private hdfs-configuration
  (memoize #(doto (Configuration.)
              (.addResource (Path. (str (get-hadoop-conf-file-or-fail)))))))

(defn- remove-hdfs-prefix
  "Removes the prefix HDFS libraries may insert."
  [path]
  (let [prefix "hdfs://"]
    (assert (.startsWith path "hdfs://"))
    (let [spl (split (.substring path (count prefix)) #"/")]
      (str "/" (join "/" (rest spl))))))

(defn- hdfs-file-info [status]
  {:path (remove-hdfs-prefix (.toString (.getPath status)))
   :mod-time (.getModificationTime status)
   :directory (.isDir status)})

(defn- hdfs-filesystem [path]
  ;; there's a bug in hdfs-clj's filesystem function (can't provide
  ;; configuration), so we're doing it manually here
  (org.apache.hadoop.fs.FileSystem/get (.toUri (hdfs/make-path path))
                                       (hdfs-configuration)))

(defn- hdfs-list-status [path]
  (map hdfs-file-info (.listStatus (hdfs-filesystem path)
                                   (hdfs/make-path path))))

(deftype HDFS []
  FileSystem
  (exists? [_ path]
    (.exists (hdfs-filesystem path) (hdfs/make-path path)))
  (directory? [_ path]
    (.isDirectory (hdfs-filesystem path) (hdfs/make-path path)))
  (mod-time [_ path]
    (.getModificationTime (hdfs/file-status path)))
  (file-seq [this path]
    (map :path (.file-info-seq this path)))
  (file-info [_ path]
    (hdfs-file-info (hdfs/file-status path)))
  (file-info-seq [this path]
    (if (or (not (.exists? this path)) (should-ignore? path))
      []
      (let [statuses (hdfs-list-status path)]
        (if-not (.directory? this path)
          statuses
          (mapcat #(if (should-ignore? (% :path))
                     []
                     (if-not (% :directory)
                       [%]
                       (.file-info-seq this (% :path))))
                  statuses)))))
  (data-in? [this path]
    (data-in?-impl this path))
  (normalized-filename [_ path]
    (remove-extra-slashes path))
  (rm [_ path]
    ;; TODO(artem)
    ;; This is dirty, we probably should reimplement this using Hadoop API
    (shell "hadoop" "fs" "-rmr" path :use-shell true :die true))
  (mv [_ from to]
    ;; TODO(artem)
    ;; This is dirty, we probably should reimplement this using Hadoop API
    (shell "hadoop" "fs" "-mv" from to :use-shell true :die true)))


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
  (when-not (fs/exists? (fs/file filename))
    (throw+ {:msg (format "unable to locate properties file %s" filename)}))
  (let [io (java.io.FileInputStream. filename)
        prop (java.util.Properties.)]
    (.load prop io)
    (into {} prop)))
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
  (let [ bkt-key (split (last (split path #"^/*"))
                         #"/" 2 )]
    { :bucket (first bkt-key)
     :key (second bkt-key)}))

(defn- s3-object-to-info
  "Converts the elements the results of s3/list-objects
  into filesystem info objects"
  [{bucket :bucket key :key {last-mod :last-modified} :metadata}]
  {:path     (join "/" ["" bucket key])
   :directory (.endsWith key "/")
   :mod-time  (.getTime last-mod)})

(deftype S3 []
  FileSystem
  (exists? [_ path]
    (let [{bucket :bucket key :key} (s3-bucket-key path)]
      (s3/object-exists? (s3-credentials) bucket key )))
  (directory? [this path]
    (.endsWith path "/"))
  (mod-time [_ path]
    (let [{bucket :bucket key :key} (s3-bucket-key path)]
      (.getTime (:last-modified (s3/get-object-metadata (s3-credentials) bucket key)))))
  ;; S3 list-object api call by default will give
  ;; us everything to fill out the file-info-seq
  ;; call. This one calls that one and strips out the
  ;; extra data
  (file-seq [this path]
    (map :path (.file-info-seq this path)))
  ;; Using the impl here
  (file-info [this path]
    (file-info-impl this path))
  ;; Not using the impl here as it would result in an
  ;; excessive number of api calls. We get all that we
  ;; need from list-objects anyway.
  (file-info-seq [this path]
    (if (should-ignore? path)
      []
      ;; S3 is funny about directories - they do not really exist
      ;; so if we are looking to list the contents of a file
      ;; that does not seem to exist, we need to explicitly try
      ;; adding a separator character to it and calling s3/list-objects
      ;; on that.
      (if (.directory? this path)
        ;; it is a directory and it exists, so
        ;; we should go do a list-object call
        (let [{bucket :bucket key :key} (s3-bucket-key path)]
          (map s3-object-to-info
               (remove #(should-ignore? (:key %))
                       (:objects (s3/list-objects (s3-credentials)
                                                  bucket
                                                  {:prefix key})))))
        ;; not a directory
        (if (.exists? this path)
          [(.file-info this path)]
          (file-info-seq this (str path "/"))))))
  (data-in? [this path]
    (data-in?-impl this path))
  ;; Normalize file names for s3 objects need to look like
  ;; s3://bucket/path/to/object for compatibility for tools
  ;; like s3cmd.
  ;;
  ;; TODO(howech)
  ;; remove-extra-slashes is probably doing some other things
  ;; that could potentially be wrong in S3.
 (normalized-filename [_ path]
    (join "/" ["" (remove-extra-slashes path)]))
  (rm [_ path]
    (let [{bucket :bucket key :key} (s3-bucket-key path)]
      (s3/delete-object (s3-credentials) bucket key)))
  (mv [_ from to]
    (let [{from-bucket :bucket from-key :key} (s3-bucket-key from)
          {to-bucket :bucket to-key :key} (s3-bucket-key to)]
      ;; ensure that moving to/from the same name
      ;; is a null operation
      (when-not (and (= from-bucket to-bucket)
                     (= from-key to-key))
        ;; There are two flavors of the move command - one for
        ;; in the same bucket, the other for different buckets.
        ;; Might not be necessary to do this, but we try to call
        ;; the right one
        (if (= from-bucket to-bucket)
          (s3/copy-object (s3-credentials) from-bucket from-key to-key)
          (s3/copy-object (s3-credentials) from-bucket from-key to-bucket to-key))
        (s3/delete-object (s3-credentials) from-bucket from-key)))))
;; ----- Mock FS --------
;; Mock file system does not support drake-ignore

(deftype MockFileSystem [fs-data]
  FileSystem
  (exists? [_ path]
    (contains? fs-data path))
  (directory? [_ path]
    (get-in fs-data path :directory))
  (mod-time [this path]
    ;; (println "--")
    ;; (println fs-data)
    ;; (println "--")
    (if-not (.exists? this path)
      (throw+ {:msg (str "file not found: " path)})
      (condp = (:mod-time (fs-data path))
          :pre (Long/MIN_VALUE)
          :now (System/currentTimeMillis)
          (:mod-time (fs-data path)))))
  (file-seq [_ path]
    (keys (filter (fn [[name opts]]
                    ;; skip directories
                    (and (not (opts :directory))
                         (.startsWith name path)))
                  fs-data)))
  (file-info [this path]
    (file-info-impl this path))
  (file-info-seq [this path]
    (file-info-seq-impl this path))
  (data-in? [this path]
    (data-in?-impl this path))
  (normalized-filename [_ path]
    (remove-extra-slashes path))
  (rm [_ _]
    (throw+ {:msg (str "rm is not implemented on mock filesystem")}))
  (mv [_ _ _]
    (throw+ {:msg (str "mv is not implemented on mock filesystem")})))

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
        filesystem (FILESYSTEMS prefix)]
    (if (nil? filesystem)
      [(FILESYSTEMS "file") "file" path]
      [filesystem prefix filename])))

(defn fs
  "Automatically determines the filesystem from the filename and dispatched
   the call to fn."
  [fn filename]
  (let [[system _ name] (get-fs filename)]
    (fn system name)))

(defn normalized-path
  "Returns absolute path preserving the prefix."
  [path]
  (let [[filesystem prefix filename] (get-fs path)]
    (make-path prefix (.normalized-filename filesystem filename))))

(defn pick-by-mod-time
  "Traverses the full directory tree starting at path, applies given
   transformation on the modification times, sorts the list according to
   the modification times and returns the first element. (Use identity
   transformation to return a minimum, or - to return the maximum mod-time
   file). Returns a file-info structure (see FileSystem/file-info)."
  [path transform]
  (first (sort-by #(transform (% :mod-time))
                  (fs file-info-seq path))))

(defn oldest-in
  [path]
  (pick-by-mod-time path identity))

(defn newest-in
  [path]
  (pick-by-mod-time path -))
