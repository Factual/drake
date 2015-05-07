(ns drake.protocol-test
  (:require [clojure.string :as str]
            [drake.protocol :refer [register-protocols!]]
            [drake-interface.core :refer [Protocol]]))

(def ^{:dynamic :private} test-results "")

(deftype ProtocolTest []
  Protocol
  (cmds-required? [_] true)
  (run [_ {:keys [cmds]}]
    (def test-results
      (apply str (cons test-results (map str/trim-newline cmds))))))

(defn with-test-protocol [fn]
  (def ^:dynamic test-results "")
  (fn)
  test-results)

(register-protocols! "test" (ProtocolTest.))