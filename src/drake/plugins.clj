(ns drake.plugins
  "Finds and loads configured plugins.

   Looks for an existing plugins.edn file on current working directory.
   When found, plugins.edn is expected to specify one or more plugins.

   plugins.edn should read to a hash-map with:
     :plugins       array of dependency tuples
     :repositories  optional hash-map of Maven repos to use

   The Maven central repo and the Clojars repo are the default repositories.
   If :repositories is included in the config, the indicated repos will be
   merged into the defaults.

   All specified plugins will become available to the Drake workflow being
   run. A plugin is executed when it's specified as a step's protocol, matching
   on the :protocol specified in the plugin configuration. E.g.:

     out.json <- in.json [myprotocol]
       SomeStepCodeGoesHere"
  (:require [fs.core :as fs]
            [cemerick.pomegranate :as pom]))

(def PLUGINS-FILE "plugins.edn")

(def DEFAULT-REPOS (merge cemerick.pomegranate.aether/maven-central
                          {"clojars" "http://clojars.org/repo"}))

(defn read-plugins-conf []
  (when (fs/exists? PLUGINS-FILE)
    (read-string (slurp PLUGINS-FILE))))

(defn add-deps [conf]
  (let [repos  (merge DEFAULT-REPOS (:repositories conf))]
    (pom/add-dependencies :coordinates (:plugins conf)
                          :repositories (merge DEFAULT-REPOS repos))))

(defn load-plugin-deps
  "Loads onto the classpath all plugins specified in plugins configuration"
  []
  (when-let [conf (read-plugins-conf)]
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
       (try
         (require namespace)
         (resolve f-symbol)
         (catch java.io.FileNotFoundException e))))))
