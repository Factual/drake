(ns drake.clj-frontend
  "Clojure frontend to drake"
  (:require [clojure.core.memoize :as memo]
            [clojure.tools.logging :refer [debug info warn]]
            [drake.clj-frontend-utils :as utils]
            [drake.core :as d-core]
            [drake.options :as d-opts :refer [*options*]]
            [drake.parser :as parse]
            [drake.plugins :as plug]
            [fs.core :as fs]))


(defn workflow
  "Create a new workflow.  Optionally specify vars to overide defaults
  environmental variables"
  ([] (workflow false))
  ([vars]
     {:steps []
      :methods {}
      :vars (if vars vars (d-core/build-vars))}))

(defn step
  "Add a new step parse-tree.  inputs and outputs are vectors and can
  be a mixture of tags and files. Tags are indicated by an opening
  %. cmds should be a vector of strings or nil/false for steps that
  don't need commands like method and template steps. Standard drake
  options can be appended inline as key value pairs, e.g. :method-mode
  true.  See method-step, cmd-step, template and template-step."
  [parse-tree outputs inputs cmds & {:keys [template]
                                 :as options}]
  (let [vars (:vars parse-tree)
        base (parse/add-path-sep-suffix
              (get vars "BASE" parse/default-base))

        [intags infiles] (utils/split-tags-from-files inputs)
        intags (utils/remove-tag-symbol intags)
        sub-infiles (map (partial utils/var-sub->str vars) infiles)
        infiles-with-base (map (partial parse/add-prefix base) sub-infiles)

        [outtags outfiles] (utils/split-tags-from-files outputs)
        outtags (utils/remove-tag-symbol outtags)
        sub-outfiles (map (partial utils/var-sub->str vars) outfiles)
        outfiles-with-base (map (partial parse/add-prefix base) sub-outfiles)

        ;; this is used for target matching, just remove all
        ;; prefixes
        outfiles-raw (mapv utils/remove-prefix sub-outfiles)

        ;; even though we will expand INPUT and OUTPUT variables later,
        ;; for now just put placeholders there for variable name checking
        vars (utils/add-placeholder-vars
              vars
              infiles-with-base
              outfiles-with-base)

        step-map {:inputs      infiles-with-base
                  :input-tags  (if (nil? intags) () intags)
                  :raw-outputs outfiles-raw
                  :outputs     outfiles-with-base
                  :output-tags (if (nil? outtags) () outtags)
                  :vars        vars
                  :opts        (if (nil? options) {} options)}
        step-map (if cmds
                   (assoc step-map
                     :cmds
                     (mapv (partial utils/var-place vars) cmds))
                   step-map)
        p-tree-key (if template :templates :steps)]
    (utils/check-step-validity parse-tree step-map)
    (update-in parse-tree [p-tree-key]
               #(if % (conj % step-map) [step-map]))))

(defn method-step
  "Shortcut for adding a step using a method to parse-tree. method
  name should be string.  method-step does not support method-steps
  with commands in conjunction with :method-mode \"append\" or
  \"replace\".  For a method-step with commands use step and specify
  :method and :method-mode options. See step for outputs inputs and
  options."
  [parse-tree outputs inputs method-name & options]
  (apply step parse-tree outputs inputs nil :method method-name options))

(def cmd-step
  "Shortcut for adding a command step to parse-tree. See step for
  outputs inputs cmds and options."
  step)

(defn template
  "Shortcut for adding a template to parse-tree.  See step for
  outputs, inputs, cmds and options."
  [parse-tree outputs inputs cmds & options]
  (apply step parse-tree outputs inputs cmds :template true options))

(defn template-step
  "Shortcut for adding a step that uses a template to parse-tree.  See
  step for outputs, inputs, cmds and options"
  [parse-tree outputs inputs & options]
  (apply step parse-tree outputs inputs nil options))

(defn method
  "Add a method to parse-tree.  method-name should be a string and cmds
  should be a vector of command strings.  Options are standard drake
  options as key value pairs, e.g. :my-option \"my-value\""
  [parse-tree method-name cmds & {:as options}]
  (when ((:methods parse-tree) method-name)
    (println (format "Warning: method redefinition ('%s')" method-name)))
  (assoc-in parse-tree [:methods method-name] {:opts (if (nil? options)
                                                      {}
                                                      options)
                                           :vars (:vars parse-tree)
                                           :cmds (mapv utils/var-place cmds)}))

(defn add-methods
  "Adds all the methods in methods-hash to parse-tree.  methods-hash
  has method names for keys and vectors of method commands for values
  like this {\"method-name\" [\"method commands\"]}"
  [parse-tree methods-hash]
  (reduce
   (fn [parse-tree [method-name cmds]]
     (method parse-tree method-name cmds))
   parse-tree
   (seq methods-hash)))

(defn set-var
  "Add a variable to parse-tree.  var-name and var-value should
  be strings"
  [parse-tree var-name var-value]
  (let [vars (:vars parse-tree)
        sub-var-name (utils/var-sub->str vars var-name)
        sub-var-value (utils/var-sub->str vars var-value)]
    (assoc-in parse-tree [:vars sub-var-name] sub-var-value)))

(defn base
  "Shortcut to set BASE to new-base"
  [parse-tree new-base]
  (set-var parse-tree "BASE" new-base))

;; This function should print some interactive output to the repl as
;; steps are run.  Currently it just blocks with no output till all
;; the steps are done.  I'm not quite sure how to do this.  Can it be
;; through the logging configuration?
(defn run-workflow
  "Run the workflow in parse-tree.  Optionally specify targetv as a
  key value pair, e.g. :targetv [\"=...\"]\", otherwise the default
  targetv is [\"=...\"]\".  Other run options to run-workflow can also
  be specified as key value pairs"
  [parse-tree & {:keys [targetv]
              :or {targetv ["=..."]}
              :as run-options}]
  (let [opts (merge d-core/DEFAULT-OPTIONS run-options)]
    (d-opts/set-options opts)
    (d-core/configure-logging)
    (memo/memo-clear! parse/shell-memo)

    (debug "Drake" d-core/VERSION)
    (info "Clojure version:" *clojure-version*)
    (info "Options:" opts)

    (plug/load-plugin-deps (*options* :plugins))
    (fs/with-cwd fs/*cwd*
      (-> parse-tree
          (utils/compile-parse-tree)
          (d-core/run targetv)))))


;; Example usage:

;; (use 'drake.clj-frontend)

(def p-tree
  (->
   (workflow {})
   (cmd-step
    ["out1"]
    []
    ["echo \"This is the first output.\" > $OUTPUT"]
    :timecheck false)
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
    ["echo \"This is the third output.\" > $OUTPUT"
     "echo \"test_var is set to $test_var - $[test_var].\" >> $OUTPUT"
     "echo \"The file $INPUT contains:\" | cat - $INPUT >> $[OUTPUT]"])))

;; (run-workflow p-tree :preview true)
;; (run-workflow p-tree :auto true)
