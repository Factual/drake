;; Implementation of a simple "eval" protocol
;; Eval protocol runs the first line of the step body through the shell protocol,
;; while putting the rest of the body into the environment variable "CODE".

(ns drake.protocol-eval
  (:require [clojure.string :as str]
            [drake-interface.core :refer [Protocol]]
            [drake.protocol :refer [register-protocols! run-interpreter]]
            drake.protocol_interpreters))

(deftype ProtocolEval []
  Protocol
  (cmds-required? [_] true)
  (run [_ step]
    (let [{:keys [cmds vars]} step]
      (run-interpreter (-> (assoc-in step [:vars "CODE"]
                                     (str/join "\n" (rest cmds)))
                           (assoc :cmds (take 1 cmds)))
                       (get (System/getenv) "SHELL")
                       []))))

(register-protocols! "eval" (ProtocolEval.))
