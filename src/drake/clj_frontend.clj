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

(defn add-step
  "Add a new step to the parse tree, p-tree.  infiles and outfiles are
  vectors of filenames.  input-tags and output-tags are optinally
  vectors of tag strings without the opening %. To specify commands
  use the :cmds option.  :opts is an optional map of options.  :method
  can be an option in the opts map.  See add-method-step and
  add-cmd-step"
  [p-tree infiles outfiles & {:keys [input-tags
                                     output-tags
                                     cmds
                                     opts]}]
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
              :opts        (if (nil? opts) {} opts)}
        step (if cmds (assoc step :cmds (map concat cmds)) step)]
    (update-in p-tree [:steps] #(conj % step))))

(defn add-method-step
  "Shortcut for making a step using a method.  infiles, outfiles,
  input-tags and output-tags should be vectors.  method is a string
  with the method name."
  [p-tree infiles outfiles method & {:keys [input-tags
                                            output-tags
                                            opts]}]
  (add-step p-tree infiles outfiles
            :input-tags input-tags
            :output-tags output-tags
            :opts (assoc opts :method method)))

(defn add-cmd-step
  "Shortcut for making a step with commands. infiles, outfiles and
  cmds should be vectors.  Optionallly, input-tags and output-tags
  should contain vectors of tags without an opening %."
  [p-tree infiles outfiles cmds & {:keys [input-tags
                                          output-tags
                                          opts]}]
  (add-step p-tree infiles outfiles
            :cmds cmds
            :input-tags input-tags
            :output-tags output-tags
            :opts opts))

(defn add-method
  "Add a method to the parse tree.  method-name should be a string and
  cmds should be a vector of command strings"
  [p-tree method-name cmds & {:keys [opts]
                              :or {opts {}}}]
  (when ((:methods p-tree) method-name)
    (warn (format "Warning: method redefinition ('%s')" method-name)))
  (assoc-in p-tree [:methods method-name] {:opts opts
                                           :vars (:vars p-tree)
                                           :cmds (map concat cmds)}))

(defn add-var
  "Add a variable to the parse tree.  var-name and var-value shoudld
  be strings"
  [p-tree var-name var-value]
  (assoc-in p-tree [:vars var-name] var-value))

(defn add-step-ids
  "Add Unique ID to each step in parse-tree"
  [parse-tree]
  (let [steps (map (fn [step]
                     (assoc step :id (str (java.util.UUID/randomUUID))))
                   (:steps parse-tree)) ; add unique ID to each step
        steps (into [] steps)]
    (assoc parse-tree :steps steps)))

(defn compile-parse-tree
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
              :as opts}]
  (let [opts (merge d-core/DEFAULT-OPTIONS opts)
        opts (merge {} opts)]
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

;; Example usage:
;; (use 'drake.clj-frontend)
;; (def p-tree (-> (new-workflow)
;;                 (add-cmd-step
;;                  []
;;                  ["out"]
;;                  ["echo test > $OUTPUT"])
;;                 (add-method
;;                  "echo_test2"
;;                  ["echo test2 > $OUTPUT"])
;;                 (add-method-step
;;                  []
;;                  ["out2"]
;;                  "echo_test2")
;;                 (add-var "test_var" "TEST_VAR_VALUE")
;;                 (add-cmd-step
;;                  []
;;                  ["out3"]
;;                  ["echo $test_var > $OUTPUT"])))
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
