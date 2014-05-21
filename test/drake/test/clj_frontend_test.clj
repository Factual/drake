(ns drake.test.clj-frontend-test
  (:require [clojure.test :refer :all]
            [drake.clj-frontend :refer :all]
            [drake.clj-frontend-utils :as utils :refer
                                      [tpprint]]))


(deftest var-check-test
  (is (= (utils/var-check {"a" 1 "b" 2} "a") nil))
  (is (thrown? Exception
               (utils/var-check {"a" 1 "b" 2} "c"))))

(deftest var-re-test
  (are [s] (= (re-find utils/var-re s) ["$[var]" "var"])
       "$[var]"
       "aaa$[var]"
       "aaa$[var]bbb"
       "$other_var $[var]"
       "$[var] $other_var"))

(deftest var-split-re-test
  (are [s m] (= (re-find utils/var-split-re s) m)
       "no-var" nil
       "$no_sub" nil
       "$[bare_var]" ["$[bare_var]" "" "bare_var" ""]
       "a$[var]" ["a$[var]" "a" "var" ""]
       "$[var]b" ["$[var]b" "" "var" "b"]
       "a$[var]b" ["a$[var]b" "a" "var" "b"]
       "a$[var1]b$[var2]" ["a$[var1]b" "a" "var1" "b"]
       "a$[var1]b$var2" ["a$[var1]b$var2" "a" "var1" "b$var2"]))

(deftest var-place-test
  (is (= (utils/var-place "test") '(\t \e \s \t)))
  (is (= (utils/var-place "aaa$[var1]") '(\a \a \a #{"var1"})))
  (is (= (utils/var-place "aaa$[var1]bbb$OUTPUT")
         '(\a \a \a #{"var1"} \b \b \b \$ \O \U \T \P \U \T)))
  (is (= (utils/var-place "test$OUTPUT")
         '(\t \e \s \t \$ \O \U \T \P \U \T)))
  (is (= (utils/var-place {"var" "var-val"}
                          "aaa$[var]")
         '(\a \a \a #{"var"})))
  (is (thrown? Exception
               (utils/var-place {"not-var" "val"}
                                "aaa$[var]"))))

(deftest check-step-validity-test
  (is (thrown-with-msg?
       Exception
       #"method 'future' undefined at this point."
       (-> (new-workflow {})
           (method-step ["A"] ["B"] "future")
           (method "future" ["cmd"]))))
  (is (thrown-with-msg?
    Exception
    #"bad-method-mode is not a valid method-mode, valid values are: .*"
    (-> (new-workflow {})
        (method "a-method" ["cmd"])
        (method-step ["out"] ["in"] "a-method"
                     :method-mode "bad-method-mode"))))
  (is (thrown-with-msg?
       Exception
       #"method-mode specified but method name not given"
       (-> (new-workflow {})
           (step [] [] [] :method-mode "append")
           )))
  (is (thrown-with-msg?
       Exception
       #"commands not allowed for method calls .*"
       (-> (new-workflow {})
           (method "test-method" ["cmd"])
           (step [] [] ["bad-commands"] :method "test-method")))))


;; Here are the bulk of the tests.  The basic idea is to make a
;; workflow using clj-frontend and make sure it is exactly the same as
;; the workflow coming form parser

(deftest basic-wf-test
  (is (=
       (-> (new-workflow {})
           (cmd-step ["out"] ["in"] ["  cat $[INPUT] > $OUTPUT"])) ;note
                                                                   ;cmd
                                                                   ;space

       (utils/str->parse-tree "
out <- in
  cat $[INPUT] > $OUTPUT"))))

(deftest subsitution-in-file-test
  (is (=
       (-> (new-workflow {})
           (set-var "var" "val")
           (cmd-step ["$[var]"] [] ["  cmd"]))
       (utils/str->parse-tree "
var=val
$[var] <-
  cmd"))))

(deftest two-commands-test
  (is (=
       (-> (new-workflow {})
             (cmd-step ["out"] ["in"]
                       ["  echo \"first command\""
                        "  echo \"second command\""]))

       (utils/str->parse-tree "
out <- in
  echo \"first command\"
  echo \"second command\""))))

(deftest method-test
  (is (=
       (:steps (-> (new-workflow {})
                   (method "sort_and_unique"
                           ["  sort $INPUT | uniq > $OUTPUT"]
                           :protocol "shell"
                           :my_option "my_value")
                   (method-step
                    ["output1"]
                    ["input1"]
                    "sort_and_unique")
                   (method-step
                    ["output2"]
                    ["input2"]
                    "sort_and_unique"
                    :my_option "new_my_value")))
       (:steps (utils/str->parse-tree "
sort_and_unique() [shell my_option:my_value]
  sort $INPUT | uniq > $OUTPUT

output1 <- input1 [method:sort_and_unique]
output2 <- input2 [method:sort_and_unique my_option:new_my_value]")))))

(deftest template-test
  (is (=
       (-> (new-workflow {})
           (template [".sorted$"] ["."]
                     ["  cat $INPUT | sort | uniq > $OUTPUT"])
           (template-step ["output1.sorted"] ["input1"]))

       (utils/str->parse-tree "
.sorted$ <- . [+template]
  cat $INPUT | sort | uniq > $OUTPUT
output1.sorted <- input1"))))

(deftest tag-test
  (is (=
       (-> (new-workflow {})
           (cmd-step ["%A"] [] ["  echo Step A"])
           (cmd-step ["%B"] [] ["  echo Step B"])
           (cmd-step ["%C"] ["%A" "%B"] ["  echo Step C"])
           (cmd-step ["%D"] ["%C"] ["  echo Step D"]))

       (utils/str->parse-tree "
%A <-
  echo Step A

%B <-
  echo Step B

%C <- %A, %B
  echo Step C

%D <- %C
  echo Step D"))))

(deftest base-in-out-test
  (is (=
       (-> (new-workflow {})
           (base "/tmp")
           (cmd-step
            ["words" "lines"]
            ["cpg.csv"]
            ["  echo INPUTS=$[INPUTS]"
             "  echo INPUTN=$[INPUTN]"
             "  echo INPUT=$[INPUT]"
             "  echo INPUT0=$[INPUT0]"
             "  echo OUTPUTS=$[OUTPUTS]"
             "  echo OUTPUTN=$[OUTPUTN]"
             "  echo OUTPUT=$[OUTPUT]"
             "  echo OUTPUT0=$[OUTPUT0]"
             "  echo OUTPUT1=$[OUTPUT1]"]))
       (utils/str->parse-tree "
BASE=/tmp

words, lines <- cpg.csv
  echo INPUTS=$[INPUTS]
  echo INPUTN=$[INPUTN]
  echo INPUT=$[INPUT]
  echo INPUT0=$[INPUT0]
  echo OUTPUTS=$[OUTPUTS]
  echo OUTPUTN=$[OUTPUTN]
  echo OUTPUT=$[OUTPUT]
  echo OUTPUT0=$[OUTPUT0]
  echo OUTPUT1=$[OUTPUT1]"))))

(deftest off-base-test
  (is (= (-> (new-workflow {})
          (base "/tmp")
          (cmd-step
           ["!/usr/local/bin/a-bin"]
           ["input"]
           ["  cmd"]))
      (utils/str->parse-tree "
BASE=/tmp

!/usr/local/bin/a-bin <- input
  cmd"))))

(deftest run-workflow-test
  "Just make sure we don't get any errors"
  (let [wf (-> (new-workflow {})
               (cmd-step ["fake_output"] [] ["echo \"I don't do much\""]))]
    (run-workflow wf)
    (doseq [verbosity [:quiet :default :verbose]]
      (run-workflow wf :repl-feedback verbosity))))

;; (run-tests)
