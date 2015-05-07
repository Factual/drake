(ns drake.test.steps
  (:use clojure.test
        drake.test.utils))

;; Mock filesystem data defined in fs.clj

;; TODO(artem)
;; Idea - default option values can be defined in environment variables
;; unless overriden. Then we won't have to repeat "protocol:test" here and
;; in general, options and variables are very alike.

(def TEST-DATA
"BASE=test:

test_method1()          ; minimalistic definitions (just for testing target
test_method2()          ; selection using methods)

A, %tag1 <- X [test]
  0

B <- Y [test]
  1

C, %tag2 <- A, B [test method:test_method1 method-mode:replace]
  2

D, %tag3 <- C [test method:test_method2 method-mode:replace]
  3

E, %tag2 <- B [test]
  4

F <- D, E [test]
  5

G, H <- E [test]
  6

I <- %tag3 [test -timecheck]
  7

K, %tag4 <- %tag2 [test -timecheck method:test_method2 method-mode:replace]
  8")

(deftest test-target-selection
  (let [data (parse-func TEST-DATA)]
    ;; Simple
    (test-targets data "=A" "0")
    (test-targets data "=test:A" "0")      ;; either way is fine
    (test-targets data "=G" "6")
    ;; Up-tree
    (test-targets data "A" "0")
    (test-targets data "B" "1")
    (test-targets data "C" "012")
    (test-targets data "D" "0123")
    (test-targets data "E" "14")
    (test-targets data "F" "012345")
    (test-targets data "G" "146")
    (test-targets data "H" "146")
    ;; Down-tree
    (test-targets data "^A" "0235")
    (test-targets data "^B" "123456")
    (test-targets data "^C" "235")
    (test-targets data "^D" "35")
    (test-targets data "^E" "456")
    (test-targets data "^F" "5")
    (test-targets data "^G" "6")
    (test-targets data "^H" "6")
    ;; Combined
    (test-targets data "^A ^G" "02356")
    (test-targets data "^G ^A" "60235")
    ;; Regex-matching
    (test-targets data "^@(A|G)" "02356")
    (test-targets data "^@test:(G|A)" "02356")       ;; same order
    (test-targets data "^@(C|E)" "23456")
    (test-targets data "=@." "0123456")              ;; matching all targets
    (test-targets data "^@." "0123456")
    ;; All targets (explicitly)
    (test-targets data "..." "0123456")
    (test-targets data "=..." "0123456")
    (test-targets data "^..." "0123456")
    ;; Exclusion
    (test-targets data "^A -C" "35")
    (test-targets data "-C ^A" "0235")
    (test-targets data "... -^A" "146")
    ;; Dependecy-based order of execution:
    ;; regardless of the order the targets are specified in,
    ;; if one target depends on the other, directly or indirectly, it
    ;; should be executed second
    (test-targets data "=A =C" "02")     ;; direct
    (test-targets data "=C =A" "02")
    (test-targets data "=A =F" "05")     ;; indirect
    (test-targets data "=F =A" "05")
    ;; Tags
    (is (thrown? Exception (run-targets data "%tag8")))   ;; Unknown tag
    (test-targets data "%tag1" "0")
    (test-targets data "+I" "01237")
    (test-targets data "%tag3" "0123")
    (test-targets data "%tag2" "0124")
    (test-targets data "=%tag2" "24")
    (test-targets data "^%tag3" "35")
    (test-targets data "%tag2 -=%tag3" "0124")
    (test-targets data "%tag2 -%tag3" "4")
    (test-targets data "... -=%tag2" "01356")
    (test-targets data "+... -=%tag2" "0135678")
    (test-targets data "^%@tag[23]" "23456")
    (test-targets data "^%@tag" "0234586")
    (test-targets data "+=%@.*" "02348")        ;; everything that is tagged
    (test-targets data "+=%..." "02348")        ;; same
    ;; Methods
    (is (thrown? Exception (run-targets data "method_84()")))  ;; Unknown method
    (test-targets data "=test_method1()" "2")
    (test-targets data "test_method1()" "012")
    (test-targets data "=test_method2()" "38")
    (test-targets data "test_method2()" "012348")
    (test-targets data "^test_method2()" "358")
    (test-targets data "^@test_method2()" "358")
    (test-targets data "test_method1() ^test_method2()" "012358")
    (test-targets data "@test_method()" "012348")
    (test-targets data "^@test_method()" "2358")
    (test-targets data "+=@.*()" "238")        ;; everything that uses a method
    (test-targets data "+=...()" "238")        ;; same
    ))

(def TEST-DATA-CYCLES
"BASE=test:

B <- A [test]
  0

C <- B [test]
  1

A <- C [test]
  2

D <- B [test]
  3

E <- D [test]
  4

F <- X [test]
  5

G <- F [test]
  6
")

(deftest test-cycles
  (let [data (parse-func TEST-DATA-CYCLES)]
    ;; A completely isolated subtree should be OK to execute
    (test-targets data "G" "56")
    (test-targets data "^F" "56")
    ;; Selecting either A, B or C in any mode would fail
    (is (thrown? Exception (run-targets data "A")))
    (is (thrown? Exception (run-targets data "B")))
    (is (thrown? Exception (run-targets data "C")))
    (is (thrown? Exception (run-targets data "^A")))
    (is (thrown? Exception (run-targets data "^B")))
    (is (thrown? Exception (run-targets data "^C")))
    ;; Drake pulls in the cycle by denepding on B, in up-tree mode
    ;; it should be an error
    (is (thrown? Exception (run-targets data "D")))
    ;; but in down-tree mode it should be OK
    (test-targets data "^D" "34")
))

(def TEST-DATA-CONFLICTS
"BASE=test:

A <- X [test]
  0

A <- Y [test]
  1

B <- C [test]
  2

D, B <- E [test]
  3

F <- E [test]
  4

G <- B [test]
  5

H <- G [test]
  6
")

(deftest test-conflicts
  (let [data (parse-func TEST-DATA-CONFLICTS)]
    ;; if the selected targets do not include a name conflict, it's OK
    (test-targets data "F" "4")
    (test-targets data "=G" "5")
    (test-targets data "^G" "56")
    (is (thrown? Exception (run-targets data "A")))
    (is (thrown? Exception (run-targets data "B")))
    (is (thrown? Exception (run-targets data "G")))
    ))

(def TEST-DATA-NORMALIZATION
"BASE=test:

double//and///trailing/slashes/ <- A [test]
  /

BASE=\"\"

; local file normalization
bla/../test <- test:A [test]
  n

BASE=test:/base/

/a/ <- !test:A [test]
  a

BASE=\"\"

bla/../test1 <- test:A [test]
  conflict

; This should be a conflict with bla/../test1 after normalization
test1 <- test:A [test]
  conflict

; This two should be a conflict also, even though this one has local:
; prefix and another one doesn't
BASE=dir/..
test2 <- input1 [test]
  conflict

BASE=\"\"
file:test2 <- test:A [test]
  conflict
")

(deftest test-base-and-normalization
  (let [data (parse-func TEST-DATA-NORMALIZATION)]
    ;; Basic normalization
    (test-targets data "=test:double//and///trailing/slashes/" "/")
    (test-targets data "=test:double/and/trailing/slashes/" "/")
    (test-targets data "=double/and/trailing/slashes/" "/")
    (test-targets data "=test:double/and/trailing/slashes" "/")
    (test-targets data "=double/and/trailing/slashes" "/")
    ;; All of this is equivalent - with BASE or without, and duplicated
    ;; and trailing slashes do not matter
;    (test-targets data "+=test:/base//a" "a")
;    (test-targets data "+=test:/base/a" "a")
    ;; matching without BASE
    (test-targets data "+=/a/" "a")
    (test-targets data "+=//a//" "a")
    ;; normalization
    (test-targets data "+=test" "n")
    (test-targets data "+=file:test" "n")
    (test-targets data "+=bla/../test" "n")
    (test-targets data "+=file:bla/../test" "n")
    ;; conflicts
    (is (thrown? Exception (run-targets data "+=test1")))
    (is (thrown? Exception (run-targets data "+=@test1")))
    ;; but this one will work because regex matching does not
    ;; perform normalization
    (test-targets data "+=@bla/../test1" "conflict")
    ;; same for test2
    (is (thrown? Exception (run-targets data "+=test2")))
    (is (thrown? Exception (run-targets data "+=file:test2")))
    (is (thrown? Exception (run-targets data "+=dir/../test2")))
    (is (thrown? Exception (run-targets data "+=file:dir/../test2")))
    (test-targets data "+=@^file:test2$" "conflict")
    ))

