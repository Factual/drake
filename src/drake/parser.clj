(ns drake.parser
  (:use [clojure.tools.logging :only [warn]]
        [slingshot.slingshot :only [throw+]]
        drake.shell
        [drake.steps :only [add-dependencies calc-step-dirs]]
        drake.utils
        drake.parser_utils)
  (:require [name.choi.joshua.fnparse :as p]
            [clojure.string :as s]
            [fs.core :as fs]))

  "This namespace is responsible for the overall parsing of a .d file into
   a standard 'parse tree'. Contains the top level logic to take a .d file name,
   or a string, and produce the fully parsed parse tree that
   contains all the data necessary to later execute the steps.
   See parse-file and parse-str.

   The top level parse tree will look like:

   {:steps [step1 step2 ...]         ;; see below
    :vars  { ... } }                 ;; same as individual step's vars

   The most important aspect in the parse tree is the collection of parsed
   steps. Each step will contain all data about that step, including commands,
   outputs, inputs, vars, etc.

   Each step will look like:

   {:outputs        ['/tmp/base/A', '/tmp/base/B']
    :raw-outputs    ['A', 'B']
    :output-tags    ['usa-files']
    :inputs         ['/tmp/base/C']
    :input-tags     ['setup']
      ;; vector of strings
    :cmds           ['cp $INPUT $OUTPUT' 'wc -l $OUTPUT']
      ;; all available variable context for this particular step
    :vars           {'BASE' '/tmp/base/'
                     'MYVAR' 'myvalue'}
      ;; directory for step's intermediate files and logs
      ;; based on output files and output tags, created under .drake/
    :dir            'A_B_use-files'
   }

   At this stage, no string substitution have been performed except in
   variables. The Clojure extension code can operate on this parse tree,
   for example, by copying some steps and modifying inputs or outputs,
   or inserting new variables into :vars. All other string substitutions
   (inputs, outputs, tags, command lines) are performed at later stages.
   This is done deliberately to make writing Clojure-extensions easier.

   After parsing is done, variable string substitution is performed on this
   data structure, and step dependencies are calculated and added to each step,
   expressed as vectors of step indexes in the :steps vector of the parse tree:

   { :parents   [ 0 1 ]             ;; step's dependencies
     :children  [ 4 ]               ;; step's dependants
   }

   Also, on the top level of the parse-tree, the following maps are build
   used for target lookup:
   {
      ;; maps all outputs to steps producing them
    :output-map  { 'A' [ 0 2 ] 'B' [ 1 ] }
      ;; same for output tags
    :output-tags-map { 'tag-all' [ 0 1 2 ] 'tag-stats' [ 4 ] }
   }

   ===========================================

   This parser for Drake is built using FnParse 2
   (https://github.com/joshua-choi/fnparse/wiki)
   Using a parser combinator methodology, Rules can be easily composed to
   build up more complicated rules. Here, each character is considered a
   token, as opposed to using a lexer to tokenize before applying the parser.

   Rule naming conventions:
     1. If it ends with \"line\" or \"lines\", then it is expected to start
        at column 0 and end with a line-break character. Each \"line\" can
        have optional trailing whitespace and/or comments."


(def default-base "")

;;
;; Drake-specific grammar rules
;;

(def var-name-chars
  (p/alt alphanumeric underscore hyphen))
;; TODO(artem)
;; 1. Var values can be also represented as string literals ("value")
;; Make sure string substitution works there
;; 2. colon is enabled here? what about option values?
(def var-value-chars
  (p/alt alphanumeric underscore hyphen period colon forward-slash))
;; TODO(artem)
;; 1. Need to provide escaping for filenames to treat whitespace etc.
;; 2. Need to make sure filenames cannot _start_ with =, -, + and ^ unless
;; escaped.
;; 3. Maybe allow using string literals for filenames as well ("filename")?
(def filename-chars
  (p/alt alphanumeric underscore hyphen period colon forward-slash equal-sign))

(def inline-comment
  (p/conc semicolon (p/rep* non-line-break)))

(def line-ending
  (p/conc (p/opt inline-ws) (p/opt inline-comment) line-break))

(defn- var-sub
  "This is a function that returns an fnparse object. This object has
   variation based on the context. Function parameters:
     substitute-value   whether to perform variable substitution or not
     var-check          whether to check if the variable has been defined

   If no variable substitution is performed, instead of a value string,
   a hash-set #{var-name} will be returned, which will be identified as
   a special placeholder value for variable substitution in run-time
   (see core/prepare-step-for-run).

   fnparse-wise:

   input: variable substitution of the form $[...]
   output: value of the variable, #{} placeholder,
           or throws error if var doesn't exist."
  [substitute-value var-check]
  (p/complex [_ dollar-sign
              _ open-bracket
              var-name (p/semantics (p/rep+ var-name-chars) apply-str)
              _ (p/failpoint close-bracket
                             (illegal-syntax-error-fn "variable"))
              vars (p/get-info :vars)
              inputs (p/get-info :inputs)
              ]
             ;; even though we don't always make substitutions here,
             ;; we still check if the variable is defined
             ;; (unless it's a method in which case
             ;; we just don't know what variables will be available)
             (if (and var-check (not (contains? vars var-name)))
               (throw+ {:msg (format "variable \"%s\" undefined at this point."
                                     var-name)})
               (if-not substitute-value
                 #{var-name}
                 (get vars var-name)))))

(declare command-sub)

(def string-lit
  "input: quoted strings of form \"...\". It may contain var or command
   substitutions
   output: string with the quotes removed and substitutions made"
  (p/complex [_ string-delimiter
              contents (p/rep* (p/alt (var-sub true true)
                                      command-sub string-char))
            _ string-delimiter]
    (apply-str contents)))

(def command-sub
  "input: shell cmd invocations of form $(...)
   output: output of the shell command with trailing line-breaks trimmed"
  (p/complex
   [_ dollar-sign
    _ open-paren
    prod (p/semantics
          (p/rep+ (p/alt (var-sub true true)
                         string-lit (p/except string-char
                                              close-paren)))
          apply-str)
    _ (p/failpoint close-paren
                   (illegal-syntax-error-fn "command substitution"))]
   (let [cmd-out (java.io.StringWriter.)]
     ;; stderr preserved by default
     (shell prod :die true :use-shell true :out [cmd-out])
     (s/trim-newline (str cmd-out)))))

(defn string-substitution
  [chars]
  (p/semantics (p/rep+ (p/alt (var-sub true true) command-sub chars))
               apply-str))

(def option-true-bool
  "input: option of form: +option_name
   output: {option_name:true}"
  (p/complex [_ plus-sign
              option (p/rep+ var-name-chars)]
             [(keyword (apply-str option)) true]))

(def option-false-bool
  "input: option of form: -option_name
   output: {option_name:false}"
  (p/complex [_ minus-sign
              option (p/rep+ var-name-chars)]
             [(keyword (apply-str option)) false]))

(def option-map-entry
  "input: option of form: option_name:option_value
   output: {option_name:option_value}"
  (p/complex [option (p/rep+ var-name-chars)
              value (p/opt
                     (p/conc colon
                             (p/alt keyword-lit
                                    (string-substitution var-value-chars)
                                    string-lit)))]
             (let [opt-name (apply-str option)]
               (if (empty? value)
                 [:protocol opt-name]
                 [(keyword opt-name) (second value)]))))

;; By default, options can have multiple values. In this case, option value
;; will be returned as a vector. It is the responsibility of the protocol
;; to detect whether option value is a scalar or a sequence.
;; Below is the list of options that must have unique values. If any of those
;; is specified multiple times, an exception will be thrown.
(def ^:private unique-options
  #{:protocol
    :this_is_for_test_only_no_use})

(defn make-option-map
  "Converts a vector of key-value tuples into an option map, properly
   treating multiple value options."
  [keyvals]
  (let [val-vectors (reduce (fn [res [key value]]
                              (assoc res key (concat (res key) [value])))
                            {} keyvals)]
    ;; convert single values to scalars and check for invalid multiples
    (into {} (map (fn [[key vals]]
                    [key
                     (if (== (count vals) 1)
                       (first vals)
                       (if-not (unique-options key)
                         vals
                         (throw-parse-error
                          p/get-state
                          (format "option \"%s\" cannot have multiple values."
                                  (name key))
                          nil)))]) val-vectors))))

(def options
  "input: options for a step definition. ie., [shell +hadoop my_var:my_value]
   output: map from param names to param values.
           :protocol is a reserved param name"
  (p/complex [_ (opt-inline-ws-wrap open-bracket)
              _ (p/opt ws)
              option-keyvals (p/rep* (p/alt option-true-bool
                                            option-false-bool
                                            option-map-entry
                                            ws))
              _ (p/failpoint (opt-inline-ws-wrap close-bracket)
                             (illegal-syntax-error-fn "option"))]
             (make-option-map (remove #(= :ws %) option-keyvals))))

(def arrow (p/conc lt-sign hyphen))

(def file-name
  (p/complex [sign (p/opt
                    (p/alt exclamation-mark percent-sign question-mark caret))
              name (string-substitution filename-chars)
              end-marker (p/opt dollar-sign)]
             (str sign name end-marker)))


(def name-list
  "input: comma separated names. ie., \"a.csv, b.out\"
   output: vector of the names. ie., [\"a.csv\" \"b.out\"]"
  (p/complex
   [first-file file-name
    rest-files (p/rep* (p/semantics
                        (p/conc (p/conc (p/opt ws) comma (p/opt ws))
                                file-name)
                        second))]   ;; first is ",", second is <file-name>
   (cons first-file rest-files)))

(defn add-prefix
  "Appends prefix if necessary (unless prepended by '!')."
  [prefix file]
  (if (= \! (first file))
    (clip file)
    (str prefix file)))

(defn add-path-sep-suffix [path]
  (if (or (empty? path)
          (.endsWith path "/")
          (.endsWith path ":"))
    path
    (str path "/")))

(defn inouts-map
  "Builds a hash-map of environment variables for inputs and outputs,
   per our spec for providing input and output values, including
   separate variables for count, and a space concatenated list.

   items should be an ordered sequence of either inputs or outputs,
   and prefix should be 'INPUT' or 'OUTPUT'.

   Example items:
     ['aaron.txt' 'artem.json' 'tim.yaml']

   Example return value, assuming prefix = 'INPUT':
     {
       'INPUTS' 'aaron.txt artem.json tim.yaml'
       'INPUTN' 3
       'INPUT'  'aaron.txt'
       'INPUT0' 'aaron.txt'
       'INPUT1' 'artem.json'
       'INPUT2' 'tim.yaml'
     }"
  [items prefix]
  (reduce
   #(assoc %1 (str prefix (first %2)) (second %2))
   {prefix (first items)
    (str prefix "S") (s/join " " items)
    (str prefix "N") (count items)}
   (keep-indexed vector items)))

;; TODO(artem)
;; Should we move this to a common library?
(defn demix
  "Given N conditions, returns N sequences satisfying each of these
   conditions respectively, and N+1'th sequence satisfying none.
   Conditions are evaluated in the order they are specified,
   sum of lengths of all sequences returned will be equal to the length of
   the sequence provided. "
  [seq & conds]
  (let [[seqs tail]
        (reduce (fn [[seqs tail] cond]
                  [(conj seqs (filter cond tail))
                   (filter (complement cond) tail)])
                [[] seq] conds)]
    (conj seqs tail)))

(def step-def-line
  "input: first line of a step definition that looks something like
     a <- b [ opts ]\n
   output: map with :inputs, :outputs,
     :raw-outputs (outputs without BASE prefix),
     :inputs, :vars, and possibly :opts"
  (p/complex
   [outputs (p/opt (p/invisi-conc name-list (p/opt ws)))
    _ (p/conc arrow (p/opt inline-ws))
    inputs (p/opt name-list)
    opts (p/opt options)
    _ (p/opt inline-ws)
    _ (p/opt inline-comment)
    _ (p/failpoint line-break (illegal-syntax-error-fn "step definition"))
    vars (p/get-info :vars)]
   (let [raw-base (get vars "BASE" default-base)
         base (add-path-sep-suffix raw-base)

         remove-tag-symbol (fn [tags] (map clip tags))
         map-base-prefix #(map (partial add-prefix base) %)

         [intags infiles] (demix inputs #(= \% (first %)))
         intags (remove-tag-symbol intags)
         infiles-with-base (map-base-prefix infiles)

         [outtags outfiles] (demix outputs #(= \% (first %)))
         outtags (remove-tag-symbol outtags)
         outfiles-with-base (map-base-prefix outfiles)
         ;; this is used for target matching, just remove all
         ;; prefixes
         outfiles-raw (mapv #(if (#{\! \?} (first %))
                               (clip %)
                               %) outfiles)
         ;; even though we will expand INPUT and OUTPUT variables later,
         ;; for now just put placeholders there for variable name checking
         vars (merge vars (into {} (map #(vector (first %1) "*placeholder*")
                                        (merge (inouts-map infiles-with-base
                                                           "INPUT")
                                               (inouts-map outfiles-with-base
                                                           "OUTPUT")))))]
     {:inputs      infiles-with-base
      :input-tags  intags
      :raw-outputs outfiles-raw
      :outputs     outfiles-with-base
      :output-tags outtags
      :vars        vars
      :opts        (if (nil? opts) {} opts)})))

(defn- command-line
  "input: command for a step, denoted by preceding ws, ending with line-break

   If there are comments at the end of a command line, it is incorporated as
   part of the command itself, so the comment syntax must be understandable by
   the protocol.

   This is a function, not a data structure, so it has to be called (just
   like var-sub). The parameter specifies whether to do variable name
   checking (substitution is never performed at parse-time for command lines).
   For regular steps, this checking is made during parse-time, but for methods
   no checking is possible since we don't know what variables will be defined
   at method instantiation."
  [var-check]
  (p/complex [ws (p/rep+ (p/alt space tab))
              cmd (p/rep+ (p/alt (var-sub false var-check) non-line-break))
              _ line-break]
             {:cmds [(concat ws cmd)]}))

(defn deep-merge
  "Takes seq of maps and merges them. For keys existing in multiple maps,
   if the value is a vector, then the vectors are concatenated; if a map,
   they're merged; otherwise throw error to flag invalid case."
  [vector-of-maps]
  (reduce #(merge-with
            (fn [l-val r-val]
              (cond
               (map? l-val)
                 (merge l-val r-val)
               (or (vector? l-val) (seq? l-val) (list? l-val))
                 (concat l-val r-val)
               :else
               (throw+ {:msg
                        (str "joining maps with non-vector and non-map values"
                             l-val " " r-val)})))
            %1 %2)
          nil
          vector-of-maps))

(def step-lines
  "input: step-def-line plus commands
   output: {:steps [<map with input/output vars and cmds>]} or
     :steps is replaced with :templates if the template option is true"
  (p/complex
   [orig-vars (p/get-info :vars)
    methods (p/get-info :methods)
    step-def-product step-def-line

    ;; generate vars from step-def-line, so that commands can refer to them
    _ (p/update-info :vars #(merge % (:vars step-def-product)))
    commands (p/failpoint (p/rep* (command-line true))
                          (illegal-syntax-error-fn "step"))

    ;; auto generated vars only persist during the step; unwind var scope
    _ (p/set-info :vars orig-vars)
    state p/get-state] ; state used for error purposes
   (let [step-prod (merge
                    step-def-product
                    (deep-merge commands))
         method (get-in step-def-product [:opts :method])
         method-mode (get-in step-def-product [:opts :method-mode])]
     (cond
      (not (or (empty? method) (methods method)))
      (throw-parse-error state (format "method '%s' undefined at this point."
                                       method)
                         nil)

      (not (or (empty? method-mode) (#{"use" "append" "replace"} method-mode)))
      (throw-parse-error state (str "invalid method-mode, valid values are: "
                                    "use (default), append, and replace.")
                         nil)

      (not (or method (empty? method-mode)))
      (throw-parse-error state
                         "method-mode specified but method name not given"
                         nil)

      (and method (not (#{"append" "replace"} method-mode))
           (not (empty? commands)))
      (throw-parse-error state
                         (str "commands not allowed for method calls "
                              "(use method-mode:append or method-mode:replace "
                              "to allow)") nil)

      (= true (get-in step-def-product [:opts :template]))
      {:templates [step-prod]}

      :else
      {:steps [step-prod]}))))


(def method-lines
  "input: method definition. ie.,
   my_func() [opts]\n
     my_cmds\n
   output: A map with a single key :methods. The value is another map whose
   key is the method name, and whose value is in the same format as a \"step\".
   This {:methods { ..} } map will be merged with others such that the value
   for :methods is a mapping from all the method names to their respective
   commands."
  (p/complex
   [method-name (p/semantics (p/rep+ var-name-chars) apply-str)
    _ (p/conc open-paren close-paren)
    opts (p/opt options)
    _ (p/opt inline-ws)
    _ (p/opt inline-comment)
    _ line-break
    commands (p/failpoint (p/rep* (command-line false))
                          (illegal-syntax-error-fn "method definition"))
    vars (p/get-info :vars)
    methods (p/get-info :methods)
    _ (p/update-info :methods #(conj % method-name))
    ]
   (do
     (if (methods method-name)
       (warn (format "Warning: method redefinition ('%s')" method-name)))
     {:methods {method-name
                (merge
                 {:opts (if (nil? opts) {} opts)
                  :vars vars}
                 (deep-merge commands))}})))


(def var-def-line
  "input: line defining a var. ie.,
   my_var=my_value\n
   output: nil, but state is updated with var definition"
  (p/complex
   [var-name (p/rep+ var-name-chars)
    has-colon (p/opt colon)
    _ equal-sign
    var-value (p/alt (string-substitution var-value-chars) string-lit)
    _ (p/opt inline-ws)
    _ (p/opt inline-comment)
    _ (p/failpoint line-break (illegal-syntax-error-fn "variable definition"))
    vars (p/get-info :vars)
    _ (if (and has-colon (get vars (apply-str var-name)))
        p/emptiness  ;do nothing if var exists and using := assignment
        (p/update-info :vars
                     #(assoc % (apply-str var-name) var-value)))]
   nil))

(declare call-or-include-line)

(def workflow
  "A workflow is a composition of various types of lines."
  (p/complex
   [body (semantic-rm-nil
          (p/rep* (p/alt step-lines
                         method-lines
                         call-or-include-line
                         (nil-semantics (p/conc (p/opt inline-ws) line-break))
                                        ;; any ws ending with line-break
                         (nil-semantics (p/conc inline-comment line-break))
                         (nil-semantics var-def-line))))
    vars (p/get-info :vars)]
   (assoc (deep-merge (concat [{:vars {} :methods {}}] body))
     :vars vars)))

(declare parse-state)

(def call-or-include-helper
  "See call-or-include-line below"
  (p/complex
   [_ percent-sign
    directive  (p/semantics (p/alt (p/lit-conc-seq "include" nb-char-lit)
                               (p/lit-conc-seq "call" nb-char-lit))
                            apply-str)
    _ inline-ws
    file-path file-name
    _ (p/opt inline-ws)
    _ (p/opt inline-comment)
    vars (p/get-info :vars)
    methods (p/get-info :methods)
    _ (p/failpoint line-break (illegal-syntax-error-fn "%call / %include"))]
   (let [raw-base (get vars "BASE" default-base)
         base (add-path-sep-suffix raw-base)
         ;; Need to use fs/file here to honor cwd
         tokens (slurp (fs/file file-path))
         prod (parse-state
               (assoc (struct state-s
                        (if (.endsWith tokens "\n") tokens (str tokens "\n"))
                        vars methods 0 0)
                 :file-path file-path))]
     (if (= directive "include")
       prod ;call-or-include line will merge vars from prod into parent's vars
       (dissoc prod :vars))))) ;vars from %call should not affect parent

(def call-or-include-line
  "input: directive to call/include another Drake workflow. ie.,
   %include nested.d
   output: same as if the lines of the nested file were copy and pasted into
   the parent workflow.  If call was used though, then the variable
   definitions in the nested workflow will not exist after the call is
   completed.

   This is split into 2 parts, call-or-include-line and call-or-include-helper,
   mainly because we need to call parse-state during the rule-product
   manipulation phase, and then we need to save vars from the %include into
   our state stuct. However, we can only set the state vars during the rule
   matching phase, which comes before the product manipulation phase when using
   \"complex\". Thus we add a wrapper rule to set the variable."
  (p/complex
   [prod call-or-include-helper
    _ (if (:vars prod)
        (p/set-info :vars (:vars prod))
        p/emptiness)]
   (dissoc prod :vars)))


;; The functions below uses the rules to parse workflows.

(defn parse-state  [state]
  (->
   (p/rule-match workflow
                 #((illegal-syntax-error-fn "start of workflow")
                   (:remainder %2) %2) ;; fail
                 #((illegal-syntax-error-fn "workflow")
                   (:remainder %2) %2) ;; incomplete match
                 state)
   add-dependencies
   calc-step-dirs))

(defn parse-str [tokens vars]
  (parse-state (struct state-s
                       (if (.endsWith tokens "\n") tokens (str tokens "\n"))
                       (merge vars {"BASE" default-base})
                       #{}
                       1 1)))

(defn parse-file [file vars]
  (parse-str (slurp file) vars))
