(ns drake.test.utils
  (:require [clojure.string :as str])
  (:use clojure.test
        [clj-logging-config.log4j :as log4j]
        drake.parser
        drake.core
        drake.options
        drake.protocol-test))

(defn parse-func [data]
  (memoize #(parse-str data nil)))

(defn run-targets [func targets]
  (log4j/set-loggers! :root {:level :off})
  (set-options {:auto true})
  (with-test-protocol #(run (func) (str/split targets #" "))))

(defn test-targets [func targets expected-value]
  (is (= expected-value (run-targets func targets))))

