(ns drake.test.parser
  (:use clojure.test)
  (:require [drake.parser :as d]))

(defstruct state-s :vars :methods :line :column :remainder)
(def make-state (partial struct state-s {"BASE" "/base"} #{} 0 0))

(defn prod-eq?
  [actual-tuple expected-product]
  (and (= (first actual-tuple) expected-product)
       (= (:remainder (second actual-tuple)) nil)))

(defn var-eq?
  [actual-tuple key expected-value]
  (= (get-in (second actual-tuple) [:vars key]) expected-value))

(deftest var-def-test
  (let [actual-tuple (d/var-def-line (make-state "MYVAR=myvalue\n"))]
    (is (prod-eq? actual-tuple nil))
    (is (= (get (:vars (second actual-tuple)) "MYVAR")
           "myvalue")))
  (is (var-eq? (d/var-def-line (make-state "MYVAR=\"myvalue\"\n"))
               "MYVAR" "myvalue"))
  (is (var-eq? (d/var-def-line
                (make-state [\A \= \" \\ \" \b \\ \" \" \newline]))
               "A" "\"b\""))
  (is (var-eq? (d/var-def-line (make-state "MYVAR=myvalue   \n"))
               "MYVAR" "myvalue"))
  (is (var-eq? (d/var-def-line (make-state "MYVAR=myvalue ;comment \n"))
               "MYVAR" "myvalue"))
  (is (var-eq? (d/var-def-line (make-state "BASE=/newbase\n"))
             "BASE" "/newbase"))
  (is (var-eq? (d/var-def-line (make-state "BASE:=/newbase\n"))
               "BASE" "/base")))

(deftest cmd-sub
  (is (var-eq? (d/var-def-line
                (make-state "MYVAR=$(echo \"foo bar\" | sed s/o/u/g)\n"))
               "MYVAR" "fuu bar")))

(deftest options-test
  (is (prod-eq? (d/options (make-state "[shell]")) {:protocol "shell"}))
  (is (prod-eq? (d/options
                 (make-state "[myproto +mytruebool -myfalsebool
                              alsobooltrue:true alsoboolfalse:false
                              notbool:\"true\"
                              dep:../mydep file:\"quoted string\"
                              option-with-dashes:55]"))
                {:protocol "myproto" :file "quoted string"
                 :dep "../mydep" :myfalsebool false :mytruebool true
                 :alsobooltrue true :alsoboolfalse false :notbool "true"
                 :option-with-dashes "55"}))
  (is (prod-eq? (d/options (make-state "[dep:mydep]")) {:dep "mydep"}))
  ;; multiple values is allowed by default, the order is preserved
  (is (prod-eq? (d/options (make-state "[dep:mydep +dep dep:5 -dep]"))
                {:dep ["mydep" true "5" false]}))
  (is (prod-eq? (d/options (make-state "[dep:mydep shell]"))
                {:dep "mydep" :protocol "shell"}))
  (is (prod-eq? (d/options (make-state " [ shell ] ")) {:protocol "shell"}))
  (is (prod-eq? (d/options (make-state "[protocol:shell]"))
                {:protocol "shell"}))
  ;; could be parsed as boolean, but it's OK - we will fail with
  ;; "unknown protocol" anyway
  (is (prod-eq? (d/options (make-state " [+protocol] ")) {:protocol true}))
  ;; "protocol" and some other (TODO:artem which? add them here) options
  ;; cannot be specified multiple times
  (is (thrown-with-msg? Exception
        #"cannot have multiple values"
        (d/options (make-state "[protocol:shell shell]"))))
  (is (thrown-with-msg? Exception
        #"cannot have multiple values"
        (d/options (make-state "[+this_is_for_test_only_no_use
                                 -this_is_for_test_only_no_use]"))))
  )

(deftest step-def-test
  (is (= (dissoc
          (first
           (d/step-def-line (make-state
                             "a, %outtag1, %outtag2 <- b, c, %intag\n")))
          :vars)
         {:raw-outputs ["a"]
          :outputs ["/base/a"]
          :output-tags ["outtag1" "outtag2"]
          :inputs '("/base/b" "/base/c")
          :input-tags ["intag"]
          :opts {}}))
  (let [actual-prod (first (d/step-def-line
                            (make-state "a <- b [shell] ;comment \n")))]
    (is (= (:opts actual-prod {:protocol :shell}))))
  (let [actual-prod (first (d/step-def-line (make-state "!a<-!b\n")))]
    (is (= (actual-prod :outputs) ["a"])))
  (let [actual-prod (first (d/step-def-line (make-state "<- a\n")))]
    (is (= (actual-prod :outputs) [])))
  (let [actual-prod (first (d/step-def-line (make-state "a <- \n")))]
    (is (= (actual-prod :inputs) [])))
  (let [actual-prod (first (d/step-def-line (make-state "a, b <- c, %d\n")))]
    (is (= (actual-prod :inputs) ["/base/c"]))
    (is (= (actual-prod :outputs) ["/base/a" "/base/b"])))
  (let [actual-prod (first (d/step-def-line
                            (make-state "a, \nb\n<-c [\n+hadoop\n]\n")))]
    (is (= (actual-prod :inputs) ["/base/c"]))
    (is (= (actual-prod :outputs) ["/base/a" "/base/b"]))
    (is (= (get-in actual-prod [:opts :hadoop]) true))))

(deftest step-test
  (let [actual-tuple (d/step-lines (make-state
                          (str "a <- b\n"
                               "  c $[OUTPUT0]\n")))]
    (is (= (dissoc (first (:steps (first actual-tuple))) :vars)
           {:cmds [[\space \space \c \space #{"OUTPUT0"}]]
            :raw-outputs ["a"]
            :outputs ["/base/a"]
            :inputs '("/base/b")
            :input-tags []
            :output-tags []
            :opts {}}))
    (is (var-eq? actual-tuple :inputs nil)) ; verify :vars state not changed
                                            ; after step has concluded
    ))

(deftest template-test
  (is (:templates (first (d/step-lines (make-state
                                        (str ".sorted$ <- . [+template]\n"
                                             " sort inputs\n")))))))

(deftest method-test
  (is (prod-eq? (d/method-lines (make-state
                                 (str "method_a() [shell my_option:my_value] \n"
                                      " x $[OUTPUT]q\n"
                                      " y\n")))
                {:methods {"method_a" {:cmds [[\space \x \space #{"OUTPUT"} \q]
                                              [\space \y]]
                                       :opts {:protocol "shell"
                                              :my_option "my_value"}
                                       :vars {"BASE" "/base"}}}})))

(deftest workflow-test
  (let [actual-prod
        (first
         (d/workflow
          (make-state
           (str "\n"
                "; our base directory\n"
                "BASE=/mybase\n"
                "\n"
                "combined.csv , z.csv <- a.csv, b.csv [protocol:bash +ignore] \n"
                "  q $[INPUTS]\n"
                "\n"
                "<- combined.csv\n"
                "  grep -v \"Scott's Cakes\" $INPUT > $OUTPUT\n"))))]
    (is (= (count (:steps actual-prod)) 2))
    (is (= (-> actual-prod :steps (first) (:cmds))
           [[\space \space \q \space #{"INPUTS"}]]))
    ))

(deftest newline-test
  (let [actual-prod
        (first
         (d/workflow
          (make-state
           (str "A <- B\n"
                "  command\n"
                "B <- C\n"
                "  command\n"))))]
    (is (= (count (:steps actual-prod)) 2))
    ))

(deftest include-test
  (let [actual-prod (d/call-or-include-line
                     (make-state
                      (str "%include "
                           (System/getProperty "user.dir")
                           "/test/drake/test/resources/nested.d\n")))]
    (is (var-eq? actual-prod "NESTEDVAR" "/foo"))
    (is (var-eq? actual-prod "BASE" "/base/nest/")))
  (let [actual-prod (d/call-or-include-line
                     (make-state
                      (str "%call "
                           (System/getProperty "user.dir")
                           "/test/drake/test/resources/nested.d\n")))]
    (is (var-eq? actual-prod "NESTEDVAR" nil))
    (is (var-eq? actual-prod "BASE" "/base"))))

(deftest errors-test
  (is (thrown-with-msg? Exception
        #"variable .* undefined at this point."
        (d/parse-str "a <- b\n  $[INPUT1]\n" nil)))
  (is (thrown-with-msg? Exception
        #"illegal syntax starting with .* for variable definition"
        (d/parse-str "VARNAME=abc^efg" nil)))
  (is (thrown-with-msg? Exception
        #"illegal syntax starting with .* for variable"
        (d/parse-str (str  "%include "
                           (System/getProperty "user.dir")
                           "/test/drake/test/resources/bad_nested.d\n") nil)))
  (is (thrown-with-msg? Exception
        #"commands not allowed for method calls"
        (d/parse-str
         "a()\n  echo\n\nA <- B [method:a]\n  commands\n"
         nil)))
  (is (thrown-with-msg? Exception
        #"method-mode specified but"
        (d/parse-str
         "A <- B [method-mode:replace]\n"
         nil)))
  (is (thrown-with-msg? Exception
        #"invalid method-mode"
        (d/parse-str
         "a()\nA <- B [method:a method-mode:invalid]\n"
         nil)))
  (is (thrown-with-msg? Exception
        #"method 'future' undefined at this point."
        (d/parse-str
         "A <- B [method:future]\nfuture()\n  cmd\n"
         nil)))
  )
