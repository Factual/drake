(ns drake.test.substitute
  (:use [drake.substitute]
        [clojure.test]
        [slingshot.slingshot :only [try+]]))

(def V
  {"SUB" "Chun"
   "B" "Artem"
   "C" "Aaron"})

(deftest test-substitute-unicorn
  (try+
    (substitute V "$[UNICORN]")
    (throw (Exception. "Undefined var substitution should fail!"))
    (catch map? m (comment "this is expected"))))

(deftest test-substitute-simple
  (is (= "Chun"     (substitute V "$[SUB]")))
  (is (= "...Chun"  (substitute V "...$[SUB]")))
  (is (=  "Chun..." (substitute V "$[SUB]..."))))

(deftest test-substitute-3vars
  (is (= "...Chun, Artem, Aaron..."
         (substitute V "...$[SUB], $[B], $[C]..."))))

(deftest test-substitute-dollars
  (is (= "$INPUT ${INPUT}" (substitute V "$INPUT ${INPUT}")))
  (is (= "$SUB ${SUB} Chun" (substitute V "$SUB ${SUB} $[SUB]"))))
