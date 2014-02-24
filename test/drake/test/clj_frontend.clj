(ns drake.test.clj-frontend
  (:use [clojure.tools.logging :only [warn debug trace]]
        clojure.test)
  (:require [drake.clj-frontend :as c-f]))

(deftest var-re-test
  (is (= (re-seq c-f/var-re "test$[xxx]sdfdsf")
         (["$[xxx]" "xxx"]))))
