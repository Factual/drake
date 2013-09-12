(ns drake.plugins
  "Supports Drake's approach to plugins. Primary entry points are:
     load-plugin-deps
     get-plugin-fn"
  (:use [clojure.tools.logging :only [debug]]
        [slingshot.slingshot :only [try+ throw+]])
  (:require [fs.core :as fs]
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
        (throw+ {:msg "Could not resolve all plugin dependencies. Check your plugins configuration file and make sure all dependencies are correct and exist in Maven Central, Clojars, or any additional repos you've specified."})))))

(defn load-plugin-deps
  "Loads onto the classpath all plugins specified in plugins configuration file f.
   f must be an EDN formatted file that reads to a hash-map with:
     :plugins       array of dependency tuples
     :repositories  optional hash-map of Maven repos to use"
  [f]
  (debug "looking for plugins conf file" (fs/absolute-path f))
  (when-let [conf (read-plugins-conf f)]
    (add-deps conf)))

(def get-plugin-fn
  "Returns the resolved function, based on protocol name, from loaded plugins.
   Assumes the function is located in a namespace named drake.[protocol-name].
   Assumes the function is named protocol-name.
   Returns nil if the function is not found."
  (memoize
   (fn [protocol-name]
     (let [base-name (str "drake." protocol-name)
           namespace (symbol base-name)
           f-symbol-name (str base-name "/" protocol-name)
           f-symbol  (symbol f-symbol-name)]
       (try+ (require namespace)
             (catch java.io.FileNotFoundException e
               (throw+ {:msg (str
                 "Could not find plugin namespace '" namespace
                 "' on the classpath."
                 " Make sure your plugins conf file exists and"
                 " includes the plugins you need.")})))
       (try+ (resolve f-symbol)
             (catch java.io.FileNotFoundException e
               (throw+ {:msg (str
                 "There is no function named '%s' in the plugin namespace '%s'"
                 "There is something hokey about that plugin."
                 f-symbol namespace)})))))))
