(ns drake.protocol-interpreters
  (:require [drake-interface.core :refer [Protocol]]
            [drake.shell :as shell]
            [drake.protocol :refer [register-protocols! run-interpreter]]))

(def WINDOWS? (.startsWith (System/getProperty "os.name") "Win"))

(defn- register-interpreter!
  [[protocol-name interpreter args]]
  (register-protocols! protocol-name
                       (reify Protocol
                         (cmds-required? [_] true)
                         (run [_ step]
                           (run-interpreter step interpreter args)))))

(dorun (map register-interpreter! [(into ["shell"] (shell/shell-cmd-prelude))
                                   ["ruby" "ruby" nil]
                                   ["python" "python" nil]
                                   ["node" "node" nil]
                                   ["R" "R" ["--slave" "-f"]]]))
