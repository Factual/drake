(ns drake.options
  (:refer-clojure :exclude [file-seq]))

;; This namespace is responsible for the keeping track of
;; options. These were previously housed in the drake.core namespace,
;; which made them inaccessible to other namespaces in the drake 
;; project.

;; TODO
;; move all option processing here.

(def ^:dynamic *options* {})
(defn set-options [opts]
  (def ^:dynamic *options* opts))
