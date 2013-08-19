(ns drake.test.utils
  (:require [clojure.string :as str])
  (:use [clojure.tools.logging :only [info debug trace error]]
        clojure.test
        [clj-logging-config.log4j :as log4j]
        drake.parser
        drake.core
        drake.options
        drake.protocol-test))

(defn sort-chars [s]
  (apply str (sort (seq (char-array s)))))

(defn parse-func [data]
  (memoize #(parse-str data nil)))

(defn run-targets [func targets]
  (comment (log4j/set-loggers! 
    :root {:level :debug :name "console" :pattern "%m%n"}
    "drake" {:level :debug :name "console" :pattern "%m%n"}))
  (log4j/set-loggers! :root {:level :off})
  (set-options {:auto true :jobs 1})
  (set-jobs-semaphore 1)
  (with-test-protocol #(run (func) (str/split targets #" "))))

(defn test-targets [func targets expected-value]
  (let [value (run-targets func targets)]
    (is (or 
          (= expected-value value) 
          (= expected-value (sort-chars value)) 
          (= (sort-chars expected-value) (sort-chars value))))))

