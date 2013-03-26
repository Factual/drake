JAR=test_proj/test.jar

; TODO(artem) This should be a no-input step when it's supported
$[JAR] <- regtest_protocol_eval.sh
  # TODO(artem)
  # There must be a smarter way to do it than cd-ing but I haven't found one
  cd test_proj
  lein uberjar

dummy_output <- $[JAR] [eval]
  java -cp $[JAR] test.core
  (do
    (println (clojure.string/join " " [2 "+" 2 "=" (+ 2 (inc 1))]))
    (println (* 8 8)))
