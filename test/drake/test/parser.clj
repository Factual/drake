(ns drake.test.parser
  (:use [clojure.tools.logging :only [warn debug trace]]
        clojure.test)
  (:require [drake.parser :as d]
            [drake.parser_utils :as p])
  (:import java.io.File))

(defn make-state [remainder]
  (p/make-state remainder {"BASE" "/base"} #{} 0 0))

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

(defmacro with-ignored-command [[command-binding] & body]
  `(let [f# (doto (File/createTempFile "drake-test" nil)
              (.delete))
         filename# (.getPath f#)
         ~command-binding (format "$(echo ignored | tee %s)" filename#)]
     ~@body
     (is (not (.exists f#)))
     (when (.exists f#)
       (.delete f#))))

(deftest ignored-shell-commands-not-run
  (with-ignored-command [command]
    (is (var-eq? (d/var-def-line (-> (format "CREATE:=%s\n" command)
                                     (make-state)
                                     (assoc :vars {"CREATE" "already-set"})))
                 "CREATE" "already-set"))))

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
                             "'tom '\\''&'\\'' jerry', a, %outtag1, %outtag2 <- b, c, %intag\n")))
          :vars)
         {:raw-outputs ["tom '&' jerry" "a"]
          :outputs ["/base/tom '&' jerry" "/base/a"]
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

(deftest ambiguity-test
  (with-ignored-command [command]
    (let [actual-prod
          (d/workflow
           (-> (make-state
                (format (str "\n"
                             "X:=%s \n"
                             "combined.csv , z.csv <- a.csv, b.csv [protocol:bash +ignore] \n"
                             "  q $[INPUTS]\n"
                             "\n"
                             "<- combined.csv\n"
                             "  grep -v \"Scott's Cakes\" $INPUT > $OUTPUT\n")
                        command))
               (assoc :vars {"X" "exists"})))]
      (is (var-eq? actual-prod "X" "exists")))))

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

(deftest blank-line-test
  (let [actual-prod
        (first
         (d/workflow
          (make-state
           (str "A <- B\n"
                "  echo 1\n"
                "\n"
                "  echo 2\n"
                "\n"
                "C <- D\n"
                "  echo 3\n"))))]
    (is (= [2 1] (map (comp count :cmds) (:steps actual-prod))))))

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

(defn locally [directive filename]
  (str directive " "
       (System/getProperty "user.dir")
       "/test/drake/test/resources/"
       filename
       "\n"))

(deftest include-test
  (let [actual-prod (d/call-or-include-line
                     (make-state
                      (locally "%include" "nested.d")))]
    (is (var-eq? actual-prod "NESTEDVAR" "/foo"))
    (is (var-eq? actual-prod "BASE" "/base/nest/"))
    (is (contains? (:methods (second actual-prod))
                   "sample_method")))
  (let [actual-prod (d/call-or-include-line
                     (make-state
                      (locally "%call" "nested.d")))]
    (is (var-eq? actual-prod "NESTEDVAR" nil))
    (is (var-eq? actual-prod "BASE" "/base"))
    (is (empty? (:methods (second actual-prod)))))
  (let [actual-prod
        (first
         (d/workflow
          (make-state (str (locally "%include" "nested.d")
                           "sample <- [method:sample_method]\n"))))]
    (is (contains? (:methods actual-prod) "sample_method"))))

(def INLINE-SHELL-TEST-DATA "$(for DUDE in dude.txt babe.txt belle.txt; do echo \\\"$DUDE <\\\"\\\"-\\\"; echo \\\"  echo $DUDE\\\"; echo; done)\n")

(deftest inline-shell-test
  (let [actual-prod
        (first
          (d/workflow
            (make-state
              INLINE-SHELL-TEST-DATA)))]

    (is (= (count (:steps actual-prod)) 3))
    (is (= (get-in actual-prod [:steps 0 :raw-outputs 0]) "dude.txt"))
    (is (= (get-in actual-prod [:steps 1 :raw-outputs 0]) "babe.txt"))
    (is (= (get-in actual-prod [:steps 2 :raw-outputs 0]) "belle.txt"))))

(deftest test-shell-with-parens
  (is (var-eq? (d/var-def-line (make-state "FOO=a$(echo '()')\n"))
               "FOO" "a()")))

(deftest errors-test
  (is (thrown-with-msg? Exception
        #"variable .* undefined at this point."
        (d/parse-str "a <- b\n  $[INPUT1]\n" nil)))
  (is (thrown-with-msg? Exception
        #"illegal syntax starting with .* for variable definition"
        (d/parse-str "VARNAME=abc^efg" nil)))
  (is (thrown-with-msg? Exception
        #"illegal syntax starting with .* for variable"
        (d/parse-str (locally "%include" "bad_nested.d")
                     nil)))
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
