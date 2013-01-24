(ns drake.c4.core-test
  (:use [drake.c4.core]
        [clojure.test])
  (:require [cheshire.core :as json]
            [fs.core :as fs]
            [ordered.set :as ords]))

(defn parser-for-type [type]
  (get-in FILE-TYPES [type :parser]))

(defn joiner-for-type [type]
  (get-in FILE-TYPES [type :joiner]))

(def HEADERS (ords/ordered-set "name" "addr" "zip"))

(def TEST-ROW {"name" "Factual" "addr" "425 Sherman" "zip" "94306"})

(defn make-temp-in-file! [base ext headers]
  (let [file (fs/temp-file base ext)]
    (when (expect-header? file)
      (write-headers-for! file headers))
    file))

(deftest test-join-csv
  (let [joiner (joiner-for-type :CSV)]
    (is (= (joiner TEST-ROW HEADERS) "Factual,425 Sherman,94306\n"))))

(deftest test-join-tsv
  (let [joiner (joiner-for-type :TSV)]
    (is (= (joiner TEST-ROW HEADERS) "Factual\t425 Sherman\t94306\n"))))

(deftest test-join-json
  (let [joiner (joiner-for-type :JSON)
        joined (joiner TEST-ROW nil)
        parsed (json/parse-string joined)]
    (is (= parsed {"zip"  "94306"
                   "addr" "425 Sherman"
                   "name" "Factual"}))))

(deftest test-csv-with-quoted-cr
  (let [in-file (make-temp-in-file! "trouble" ".csv" (ords/ordered-set "A" "B" "C"))
        ;; A  B   C
        ;; a  \nb c
        trouble "a,\"\nb\",c"]
    (spit in-file trouble)
    (let [{:strs [A B C]} (first (row-seq in-file))]
      (is (= "a"   A))
      (is (= "\nb" B))
      (is (= "c"   C)))))

(deftest test-json-with-quoted-cr
  (let [in-file (make-temp-in-file! "trouble" ".json" nil)
        ;; A  B   C
        ;; a  \nb c
        trouble "{\"A\":\"a\", \"B\":\"\nb\", \"C\":\"c\"}"]
    (spit in-file trouble)
    (let [{:strs [A B C]} (first (row-seq in-file))]
      (is (= "a" A))
      (is (= "\nb" B))
      (is (= "c" C)))))