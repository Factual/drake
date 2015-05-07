(ns drake.plugins
  "Supports Drake's approach to plugins. Primary entry points are:
     load-plugin-deps
     get-plugin-fn"
  (:require [clojure.tools.logging :refer [debug]]
            [slingshot.slingshot :refer [try+ throw+]]
            [fs.core :as fs]
            [cemerick.pomegranate :as pom]))

(def DEFAULT-REPOS (merge cemerick.pomegranate.aether/maven-central
                          {"clojars" "http://clojars.org/repo"}))

(defn read-plugins-conf [f]
  (if (fs/exists? f)
    (read-string (slurp f))
    (debug "no plugins file found at" (fs/absolute-path f))))

(defn add-deps
  "The Maven central repo and the Clojars repo are the default repositories.
   If :repositories is included in the config, the indicated repos will be
   merged into the defaults."
  [conf]
  (let [repos  (merge DEFAULT-REPOS (:repositories conf))]
    (try+
      (pom/add-dependencies :coordinates (:plugins conf)
                            :repositories repos)
      (catch org.sonatype.aether.resolution.DependencyResolutionException e
        (throw+
         {:msg (str "Plugin error. " (.getMessage ^Exception e))})))))

(defn load-plugin-deps
  "Loads onto the classpath all plugins specified in plugins configuration file f.
   f must be an EDN formatted file that reads to a hash-map with:
     :plugins       array of dependency tuples
     :repositories  optional hash-map of Maven repos to use

   Returns nill regardless of whether the file is found.
   (A missing file is taken to mean there's no plugin config desired.)"
  [f]
  (debug "looking for plugins conf file" (fs/absolute-path f))
  (when-let [conf (read-plugins-conf f)]
    (add-deps conf)))

(defn req-ns
  "Attempts to require the namespace named ns-symbol.
   Returns truthy only if succussful."
  [ns-symbol]
  (try+ (require ns-symbol)
        true
        (catch java.io.FileNotFoundException e nil)))

(def get-reified
  "Returns reified protocol based on protocol-name, from
   loaded plugins. Assumes the reified protocol is returned by the function
   named protocol-name in the namespace named [ns-prefix][protocol-name].
   Returns nil if the expected namespace is not require-able.
   Throws an exception if the expected namespace is found but the expected
   function is not.

   Example calls:
     (plugins/get-reified 'drake.fs.' 'myfs')
     (plugins/get-reified 'drake.protocol.' 'myproto')"
  (memoize
   (fn [ns-prefix protocol-name]
     (let [ns-symbol-name (str ns-prefix protocol-name)
           ns-symbol      (symbol ns-symbol-name)
           f-symbol-name  (str ns-symbol-name "/" protocol-name)
           f-symbol       (symbol f-symbol-name)]
       (when (req-ns ns-symbol)
         (try+ ((resolve f-symbol))
               (catch java.io.FileNotFoundException e
                 (throw+ {:msg (str
                                "Bad plugin: There is no function named '%s' in "
                                "the plugin namespace '%s'"
                                f-symbol-name ns-symbol-name)}))))))))
