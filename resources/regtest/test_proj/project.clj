(defproject test "0.0.1-SNAPSHOT"
  :description "Drake regression test JAR"

  :dependencies [[org.clojure/clojure "1.4.0"]]
  :main test.core
  :uberjar-name "test.jar"
  :aot :all)
