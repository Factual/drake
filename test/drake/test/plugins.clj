(ns drake.test.plugins
  (:use clojure.test
        drake.plugins))

;; Verifies correct loading of a sample plugins config file.
;; Verifies a relevent protocol can be reified and called.
;; The sample plugins file is expected to be in test resources
;; as the file 'plugins.edn', and should point to the echostep
;; plugins demo, e.g.:
;;   {:plugins [[dirtyvagabond/drake-echostep "0.2.0"]]}
(deftest test-load-plugin-deps
  (load-plugin-deps "test/drake/test/resources/plugins.edn")
  (let [echostep (get-reified "drake.protocol." "echostep")]
    (is (.cmds-required? echostep))))

;; Like test-load-plugin-deps, except tries to pull out a
;; protocol that is not defined by any plugins.
;; Verifies nil is returned.
(deftest test-fail-load-plugin-deps
  (load-plugin-deps "test/drake/test/resources/plugins.edn")
  (is (nil? (get-reified "drake.protocol." "unicorn"))))
