(ns drake.clj-frontend
  (:require [drake.parser :as parse]
            [drake.parser_utils :refer [state-s
                                        illegal-syntax-error-fn
                                        throw-parse-error]]
            [drake.core :as d-core]
            [drake.plugins :as plug]
            [drake.options :as d-opts :refer [*options*]]
            [drake.utils :as d-utils]
            [drake.steps :as d-steps]
            [clojure.core.memoize :as memo]
            [clojure.tools.logging :refer [warn debug info]]
            [fs.core :as fs]
            [clojure.string :as str]
            [slingshot.slingshot :refer [throw+]]
            [name.choi.joshua.fnparse :as p]))

(defn tprn
  "Transparent prn"
  [x]
  (prn x)
  x)

(defn new-workflow
  "Create a new workflow.  Optionally specify vars to overide defaults"
  ([] (new-workflow false))
  ([vars]
     {:steps []
      :methods {}
      :vars (if vars vars (d-core/build-vars))}))

(def var-re
  "Regular Expression for variable subsitution.  re-groups on the
  match should return a two element vector, where the first element is
  $[XXX] and the second is just XXX"
  #"\$\[([^\]]*)\]")

(defn- var-check
  "Check if var-name is a key in vars.  If it isn't raise an error."
  [vars var-name]
  (when-not (contains? vars var-name)
    (throw+
     {:msg (format "variable \"%s\" undefined at this point." var-name)})))

;; (var-check {"a" 1 "b" 2} "a")
;; (var-check {"a" 1 "b" 2} "c")

(defn- var-sub
  "Substitute the matches to $[XXX] with the value of XXX in the vars
  map. Throw an error if XXX is not found.  Return an array of characters"
  [vars s]
  (let [sub-fn (fn [var-match]
                 (let [var-name (second var-match)]
                   (var-check vars var-name)
                   (vars var-name)))]
    (concat (str/replace s var-re sub-fn))))

(defn- var-sub->str
  [vars s]
  (apply str (var-sub vars s)))

;; (var-sub {"xxx" "value1" "yyy" "value2"} "test$[xxx]sdf $[yyy] sdf")
;; (var-sub {"xxx" "value1"} "test$[xxx]sdf $[yyy] sdf")
;; (var-sub {} "test no var")

(defn- var-place
  "Replace all $[XXX] in a string with #{XXX}. Return an array of
  characters and #{XXX} placeholders.  To check that the var-names
  exist, pass in a vars map."
  ([s]
     (var-place false s))
  ([vars s]
     (let [without-vars (map concat (str/split s var-re))
           var-names (map second (re-seq var-re s))
           placeholders (map hash-set var-names)]
       (when vars (dorun (map (partial var-check vars) var-names)))
       (if-not (empty? var-names)
         (flatten (interleave without-vars placeholders))
         (concat s)))))

;; (var-place "test$[xxx]sdf $[yyy] sdf")
;; (var-place {"xxx" 1 "yyy" 2} "test$[xxx]sdf $[yyy] dfd" )
;; (var-place {"xxx" 1} "test$[xxx]sdf $[yyy] dfd")
;; (var-place "test$OUTPUT")
;; (var-place {"xxx" 1} "test$OUTPUT")
;; (map (partial var-place {"xxx" 1 "yyy" 2}) ["test$[xxx]sdf $[yyy] dfd"])

(defn add-placeholder-vars
  [vars infiles outfiles]
  (merge vars
         (into {}
               (map #(vector (first %1) "*placeholder*")
                    (merge (parse/inouts-map infiles
                                             "INPUT")
                           (parse/inouts-map outfiles
                                             "OUTPUT"))))))

(defn remove-prefix
  "Remove ! and ? prefixes from the file f"
  [f]
  (if (#{\! \?} (first f))
    (d-utils/clip f)
    f))

;; To Do.  These will all need tests.
(defn check-step-validity
  "Make sure a step is valid.  Possible problems: Undefined method,
  invalid method-mode, method-mode is set without method, commands
  with method-mode set to \"use\""
  [p-tree step-map]
  (let [step-method (get-in step-map [:opts :method])
        method-mode (get-in step-map [:opts :method-mode])
        methods (set (keys (:methods p-tree)))
        commands (:cmds step-map)
        state nil]
    (cond
     (not (or (empty? step-method) (methods step-method)))
     (throw-parse-error state
                        "method '%s' undefined at this point."
                        step-method)

     (not (or (empty? method-mode) (#{"use" "append" "replace"} method-mode)))
     (throw-parse-error state
                        (str "%s is not a valid method-mode, valid values are: "
                             "use (default), append, and replace.")
                        method-mode)

     (not (or step-method (empty? method-mode)))
     (throw-parse-error state
                        "method-mode specified but method name not given"
                        nil)

     (and step-method (not (#{"append" "replace"} method-mode))
          (not (empty? commands)))
     (throw-parse-error state
                        (str "commands not allowed for method calls "
                             "(use method-mode:append or method-mode:replace "
                             "to allow)")
                        nil))))

(defn step
  "Add a new step to the parse tree, p-tree.  infiles and outfiles are
  vectors of filenames.  input-tags and output-tags are optionally
  vectors of tag strings without the opening %. To specify commands
  use the :cmds option.  :options is an optional map of options.
  :method and :method-mode can be an option in the options map.  See
  add-method-step and add-cmd-step"
  [p-tree outfiles infiles & {:keys [input-tags
                                     output-tags
                                     cmds
                                     options]}]
  (let [vars (merge (:vars p-tree))
        base (parse/add-path-sep-suffix
              (get vars "BASE" parse/default-base))
        parse-file-fn (comp
                       (partial parse/add-prefix base)
                       (partial var-sub->str vars))
        infiles-with-base (map parse-file-fn infiles)
        outfiles-with-base (map parse-file-fn outfiles)
        ;; this is used for target matching, just remove all
        ;; prefixes
        outfiles-raw (mapv remove-prefix outfiles)
        ;; even though we will expand INPUT and OUTPUT variables later,
        ;; for now just put placeholders there for variable name checking
        vars (add-placeholder-vars vars infiles-with-base outfiles-with-base)
        step-map {:inputs      infiles-with-base
                  :input-tags  (if (nil? input-tags) () input-tags)
                  :raw-outputs outfiles-raw
                  :outputs     outfiles-with-base
                  :output-tags (if (nil? output-tags) () output-tags)
                  :vars        vars
                  :opts        (if (nil? options) {} options)
                  :cmds        (when cmds
                                 (mapv (partial var-place vars) cmds))}]
    (check-step-validity p-tree step-map)
    (update-in p-tree [:steps] #(conj % step-map))))

(defn method-step
  "Shortcut for making a step using a method.  infiles, outfiles,
  input-tags and output-tags should be vectors.  method is a string
  with the method name."
  [p-tree outfiles infiles method & {:keys [input-tags
                                            output-tags
                                            options]}]
  (step p-tree outfiles infiles
        :input-tags input-tags
        :output-tags output-tags
        :options (assoc options :method method)))

(defn cmd-step
  "Shortcut for making a step with commands. infiles, outfiles and
  cmds should be vectors.  Optionallly, input-tags and output-tags
  should contain vectors of tags without an opening %."
  [p-tree outfiles infiles cmds & {:keys [input-tags
                                          output-tags
                                          options]}]
  (step p-tree outfiles infiles
        :cmds cmds
        :input-tags input-tags
        :output-tags output-tags
        :options options))

(defn method
  "Add a method to the parse tree.  method-name should be a string and
  cmds should be a vector of command strings"
  [p-tree method-name cmds & {:keys [options]
                              :or {options {}}}]
  (when ((:methods p-tree) method-name)
    (warn (format "Warning: method redefinition ('%s')" method-name)))
  (assoc-in p-tree [:methods method-name] {:options options
                                           :vars (:vars p-tree)
                                           :cmds (mapv var-place cmds)}))

(defn add-methods
  "Adds all the methods in methods-hash to the p-tree tree.
  methods-hash has method names for keys and vectors of method
  commands for values like this {\"method-name\" [\"method commands\"]}"
  [p-tree methods-hash]
  (reduce
   (fn [p-tree [method-name cmds]]
     (method p-tree method-name cmds))
   p-tree
   (seq methods-hash)))

(defn set-var
  "Add a variable to the parse tree.  var-name and var-value should
  be strings"
  [p-tree var-name var-value]
  (let [vars (:vars p-tree)
        sub-var-name (var-sub->str vars var-name)
        sub-var-value (var-sub->str vars var-value)]
    (assoc-in p-tree [:vars sub-var-name] sub-var-value)))

(defn base
  "Shortcut to set BASE to new-base"
  [p-tree new-base]
  (set-var p-tree "BASE" new-base))

(defn- add-step-ids
  "Add Unique ID to each step in parse-tree"
  [parse-tree]
  (let [steps (map (fn [step]
                     (assoc step :id (str (java.util.UUID/randomUUID))))
                   (:steps parse-tree)) ; add unique ID to each step
        steps (into [] steps)]
    (assoc parse-tree :steps steps)))

(defn- compile-parse-tree
  "add-dependencies, calc-step-dirs and add-step-ids to
  p-tree"
  [p-tree]
  (-> p-tree
      d-steps/add-dependencies
      d-steps/calc-step-dirs
      add-step-ids))

(defn run-workflow
  "Run the workflow in p-tree.  Optionally specify :targetv, otherwise
  defaults to [\"=...\"]\".  Other options to run-workflow can be
  specified as a map to :opts"
  [p-tree & {:keys [targetv]
              :or {targetv ["=..."]}
              :as run-options}]
  (let [opts (merge d-core/DEFAULT-OPTIONS run-options)]
    (d-opts/set-options opts)
    (d-core/configure-logging)          ; currently configure-logging
                                        ; is private
    (memo/memo-clear! parse/shell-memo)

    (debug "Drake" d-core/VERSION)
    (info "Clojure version:" *clojure-version*)
    (info "Options:" opts)

    (plug/load-plugin-deps (*options* :plugins))
    (fs/with-cwd fs/*cwd*
      (-> p-tree
          (compile-parse-tree)
          (d-core/run targetv)))))

;; ;; Example usage:
;; (use 'drake.clj-frontend)

(def p-tree (-> (new-workflow {})
                (cmd-step
                 ["out1"]
                 []
                 ["echo \"This is the first output.\" > $OUTPUT"]
                 :options {:timecheck false})
                (method
                 "test_method"
                 ["echo \"Here we are testing a method step.\" > $OUTPUT"])
                (method-step
                 ["out_method"]
                 []
                 "test_method")
                (set-var "test_var" "TEST_VAR_VALUE")
                (set-var "output_three" "out3")
                (cmd-step
                 ["$[output_three]"]
                 ["out1"]
                 ["echo \\
\"This is the third output.
test_var is set to $test_var and $[test_var].
The file $INPUT contains:\" \\
| cat - $INPUT > $[OUTPUT]"])
                ))

;; (run-workflow p-tree)

;; Things I need to test

;; INPUTS and multiple inputs
;; outputs and multiple outputs
;; changing directory, try setting BASE as a variable
;; Variables in general
;; Options: -timescheck, protocol, at least do a simple ruby protocol
;; targetv
;; methods with different method-modes
;; various run-options, :opts, to run-workflow
(defn- ensure-final-newline
  "Make sure the string ends with a newline"
  [s]
  (if (.endsWith s "\n")
    s
    (str s "\n")))

(defn str->p-tree
  "Take a string s and map vars and a make a raw p-tree."
  ([s vars]
     (let [state (struct state-s
                         (ensure-final-newline s)
                         (merge {"BASE" parse/default-base} vars)
                         #{}
                         1 1)]
       (p/rule-match parse/workflow
                     #((illegal-syntax-error-fn "start of workflow")
                       (:remainder %2) %2) ;; fail
                     #((illegal-syntax-error-fn "workflow")
                       (:remainder %2) %2) ;; incomplete match
                     state))))


(defn- file->p-tree
  "Take a file and convert it to a raw parse tree for comparison with
  results from the clojure frontend"
  ([file-name] (file->p-tree file-name (d-core/build-vars)))
  ([file-name vars]
     (let [d-file (slurp file-name)]
       (str->p-tree d-file vars))))

;; (def tree (file->p-tree "test.drake.txt" {}))

(defn cmd-ws-remover
  "Simplified version of drake.core/despace-cmds, just for testing"
  [cmds]
  (when cmds
    (let [prefix-len (count (take-while #{\space \tab} (first cmds)))]
      (map #(drop prefix-len %) cmds))))

(defn step-ws-remover
  "Remove whitespace from commands in step-map"
  [step-map]
  (update-in step-map [:cmds] cmd-ws-remover))

(defn remove-step-ws [p-tree]
  (update-in p-tree [:steps]
             (fn [steps]
               (for [step-map steps]
                 (step-ws-remover step-map)))))

(defn remove-method-ws [p-tree]
  (let [method-names (keys (:methods p-tree))]
    (reduce (fn [p-tree meth]
              (update-in p-tree [:methods meth]
                         step-ws-remover))
            p-tree
            method-names)))

(defn remove-p-tree-ws
  [p-tree]
  (-> p-tree
      remove-step-ws
      remove-method-ws))
