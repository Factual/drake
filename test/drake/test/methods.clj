(ns drake.test.methods
  (:use [clojure.tools.logging :only [info debug trace error]]
        clojure.test
        drake.test.utils)
  (:require [clj-logging-config.log4j :as log4j]
            drake.protocol-test
            drake.protocol-interpreters))

;; Mock filesystem data defined in fs.clj
(def TEST-DATA
  "BASE=test:

my_method() [test]
  0

method() [shell]                      ; will be overriden
  1

A <- X [method:my_method]             ; don't have to specify protocol

B <- A [method:method test]           ; override wrong protocol

; Variable scope tests

MYVAR=methods_var
var_scope() [test]
  $[MYVAR]

var_scope_error()
  $[UNDEFINED_VAR]

%scope_test1 <- [method:var_scope]

MYVAR=workflow_var

%scope_test2 <- [method:var_scope]

%scope_test3 <- [method:var_scope_error]

UNDEFINED_VAR=defined_now
%scope_test4 <- [method:var_scope_error test]     ; now should be ok

; Extendable methods

method_empty() [test]
method_cmds() [test]
  M

; different variants of use/append/replace for a method without body

; error - empty body
%empty_use_empty <- [method:method_empty method-mode:use]    ; 'use' is default
; OK
%empty_append_cmds <- [method:method_empty method-mode:append]
  W
; error - empty cmds resulted
%empty_append_empty <- [method:method_empty method-mode:append]
; OK
%empty_replace_cmds <- [method:method_empty method-mode:replace]
  W

; same variants for a method with body

; OK, method body is used
%cmds_use_empty <- [method:method_cmds method-mode:use]     ; 'use' is default
; OK
%cmds_append_cmds <- [method:method_cmds method-mode:append]
  W
; OK
%cmds_append_empty <- [method:method_cmds method-mode:append]
; OK
%cmds_replace_cmds <- [method:method_cmds method-mode:replace]
  W

; Method redefinition - should give a warning but will work

method_redef() [test]
  original
method_redef() [test]
  redefined

%redefine_test <- [method:method_redef]
")

(deftest test-methods
  (comment (log4j/set-loggers! 
    "drake" {:level :debug :name "console" :pattern "%m%n"} ))
  (log4j/set-loggers! "drake" { :level :warn })
  (let [data (parse-func TEST-DATA)]
    (test-targets data "A" "0")
    (test-targets data "B" "01")
    ;; variable scope
    (test-targets data "%scope_test1" "methods_var")
    (test-targets data "%scope_test2" "workflow_var")
    (is (thrown-with-msg? Exception #"undefined"
          (run-targets data "%scope_test3")))
    (test-targets data "%scope_test4" "defined_now")
    ;; extendable methods, method with empty body
    (is (thrown-with-msg? Exception #"requires non-empty commands"
          (run-targets data "%empty_use_empty")))
    (test-targets data "%empty_append_cmds" "W")
    (is (thrown-with-msg? Exception #"requires non-empty commands"
          (run-targets data "%empty_append_empty")))
    (test-targets data "%empty_replace_cmds" "W")
    ;; now method with a body
    (test-targets data "%cmds_use_empty" "M")
    (test-targets data "%cmds_append_cmds" "MW")
    (test-targets data "%cmds_append_empty" "M")
    (test-targets data "%cmds_replace_cmds" "W")
    ;; method redefinition
    (test-targets data "%redefine_test" "redefined")
  ))
