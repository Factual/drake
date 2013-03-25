(ns drake.options
  (:refer-clojure :exclude [file-seq]))

(def ^:dynamic *options* {})
(defn set-options [opts]
  (def ^:dynamic *options* opts))
