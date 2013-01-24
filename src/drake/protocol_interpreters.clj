(ns drake.protocol-interpreters
  (:use drake.protocol))

(defn- register-interpreter!
  [[protocol-name interpreter]]
  (register-protocols! protocol-name
                       (reify Protocol
                         (cmds-required? [_] true)
                         (run [_ step]
                           (run-interpreter step interpreter)))))

(dorun (map register-interpreter! [["shell" (get (System/getenv) "SHELL")]
                                   ["ruby" "ruby"]
                                   ["python" "python"]]))
