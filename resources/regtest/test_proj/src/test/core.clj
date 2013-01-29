(ns test.core
  (:gen-class))

(defn -main [& args]
  (let [cmd (get (System/getenv) "CODE")]
    (println "Evaluating:" cmd)
    (eval (read-string cmd))
    (println "Evaluated.")))