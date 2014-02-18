(ns drake.clj-frontend
  (:require [drake.parser :as parse]
            [drake.core :as d-core]
            [drake.plugins :as plug]
            [drake.options :as d-opts :refer [*options*]]
            [drake.utils :as d-utils]
            [drake.steps :as d-steps]
            [clojure.core.memoize :as memo]
            [clojure.tools.logging :refer [warn debug info]]
            [fs.core :as fs]))

(defn new-workflow []
  {:steps []
   :methods {}
   :vars (d-core/build-vars)})

(defn step
  "Add a new step to the parse tree, p-tree.  infiles and outfiles are
  vectors of filenames.  input-tags and output-tags are optinally
  vectors of tag strings without the opening %. To specify commands
  use the :cmds option.  :options is an optional map of options.  :method
  can be an option in the options map.  See add-method-step and
  add-cmd-step"
  [p-tree outfiles infiles & {:keys [input-tags
                                     output-tags
                                     cmds
                                     options]}]
  (let [vars (:vars p-tree)
        raw-base (get vars "BASE" parse/default-base)
        base (parse/add-path-sep-suffix raw-base)
        map-base-prefix #(map (partial parse/add-prefix base) %)
        infiles-with-base (map-base-prefix infiles)
        outfiles-with-base (map-base-prefix outfiles)
        ;; this is used for target matching, just remove all
        ;; prefixes
        outfiles-raw (mapv #(if (#{\! \?} (first %))
                              (d-utils/clip %)
                              %) outfiles)
        ;; even though we will expand INPUT and OUTPUT variables later,
        ;; for now just put placeholders there for variable name checking
        vars (merge vars (into {}
                               (map #(vector (first %1) "*placeholder*")
                                    (merge (parse/inouts-map infiles-with-base
                                                             "INPUT")
                                           (parse/inouts-map outfiles-with-base
                                                             "OUTPUT")))))
        step {:inputs      infiles-with-base
              :input-tags  (if (nil? input-tags) () input-tags)
              :raw-outputs outfiles-raw
              :outputs     outfiles-with-base
              :output-tags (if (nil? output-tags) () output-tags)
              :vars        vars
              :opts        (if (nil? options) {} options)}
        step (if cmds (assoc step :cmds (map concat cmds)) step)]
    (update-in p-tree [:steps] #(conj % step))))

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
                                           :cmds (map concat cmds)}))

(defn add-methods
  "Adds   all the methods in methods-hash to the p-tree tree.
  methods-hash has method names for keys and vectors of method
  commands for values like this {\"method-name\" [\"method commands\"]}"
  [p-tree methods-hash]
  (reduce
   (fn [p-tree [method-name cmds]]
     (method p-tree method-name cmds))
   p-tree
   (seq methods-hash)))

(defn set-var
  "Add a variable to the parse tree.  var-name and var-value shoudld
  be strings"
  [p-tree var-name var-value]
  (assoc-in p-tree [:vars var-name] var-value))

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

;; (def p-tree (-> (new-workflow)
;;                 (cmd-step
;;                  ["out1"]
;;                  []
;;                  ["echo \"This is the first output.\" > $OUTPUT"]
;;                  :options {:timecheck false})
;;                 (method
;;                  "test_method"
;;                  ["echo \"Here we are testing a method step.\" > $OUTPUT"])
;;                 (method-step
;;                  ["method_out"]
;;                  []
;;                  "test_method")
;;                 (set-var "test_var" "TEST_VAR_VALUE")
;;                 (cmd-step
;;                  ["out3"]
;;                  ["out1"]
;;                  ["echo \\
;; \"This is the third output.
;; test_var is set to $test_var.
;; The file $INPUT contains:\" \\
;; | cat - $INPUT > $OUTPUT"])))

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
