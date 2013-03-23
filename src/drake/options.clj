(ns drake.options
  (:refer-clojure :exclude [file-seq])
  (:use [clojure.tools.logging :only [info debug trace error]]
        [slingshot.slingshot :only [try+ throw+]]
  (:gen-class)))

(def ^:dynamic *options* {})
(defn set-options [opts]
  (def ^:dynamic *options* opts))
