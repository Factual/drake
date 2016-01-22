(ns drake.clj-frontend
  "Clojure frontend to drake"
  (:require [clojure.core.memoize :as memo]
            [clojure.tools.logging :refer [debug info]]
            [drake.clj-frontend-utils :as utils]
            [drake.core :as d-core]
            [drake.options :as d-opts :refer [*options*]]
            [drake.parser :as parse]
            [drake.plugins :as plug]
            [fs.core :as fs]))


(defn new-workflow
  "Create a new workflow.  Optionally specify vars to overide default
  environmental variables"
  ([] (new-workflow {}))
  ([vars]
     {:steps []
      :methods {}
      :vars (if vars vars (d-core/build-vars))}))

(defn step
  "Add a new step to the workflow, w-flow.  inputs and outputs are
  vectors and can be a mixture of tags and files. Tags are indicated
  by an opening %. cmds should be a vector of strings or nil/false for
  steps that don't need commands like method and template
  steps. Standard drake options can be appended inline as key value
  pairs, e.g. :method-mode true; or (preferred) you may pass a single
  option-map argument. Variables for the step can be given in
  the :vars key and will be substituted. See method-step, cmd-step,
  template and template-step."
  [w-flow outputs inputs cmds & options]
  (let [{:keys [template] :as options} (utils/varargs->map options)
        w-flow-vars (:vars w-flow)
        step-vars (utils/var-sub-map w-flow-vars (:vars options))
        vars (merge w-flow-vars step-vars)
        options (dissoc options :vars)
        base (parse/add-path-sep-suffix
              (get vars "BASE" parse/default-base))

        [intags infiles] (utils/split-tags-from-files inputs)
        intags (utils/remove-tag-symbol intags)
        sub-infiles (map (partial utils/var-sub vars) infiles)
        infiles-with-base (map (partial parse/add-prefix base) sub-infiles)

        [outtags outfiles] (utils/split-tags-from-files outputs)
        outtags (utils/remove-tag-symbol outtags)
        sub-outfiles (map (partial utils/var-sub vars) outfiles)
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
    (utils/check-step-validity w-flow step-map)
    (update-in w-flow [p-tree-key]
               #(if % (conj % step-map) [step-map]))))

(defn method-step
  "Shortcut for adding a step using a method to the workflow,
  w-flow. method-name should be string.  method-step does not support
  method-steps with commands in conjunction with :method-mode
  \"append\" or \"replace\".  For a method-step with commands use step
  and specify :method and :method-mode options. See step for outputs
  inputs and options."
  [w-flow outputs inputs method-name & options]
  (step w-flow outputs inputs nil (-> (utils/varargs->map options)
                                      (assoc :method method-name))))

(def cmd-step
  "Shortcut for adding a command step to the workflow, w-flow. See
  step for outputs inputs cmds and options."
  step)

(defn template
  "Shortcut for adding a template to the workflow, w-flow.  See step
  for outputs, inputs, cmds and options."
  [w-flow outputs inputs cmds & options]
  (step w-flow outputs inputs cmds (-> (utils/varargs->map options)
                                       (assoc :template true))))

(defn template-step
  "Shortcut for adding a step that uses a template to the workflow,
  w-flow.  See step for outputs, inputs, cmds and options"
  [w-flow outputs inputs & options]
  (step w-flow outputs inputs nil (utils/varargs->map options)))

(defn method
  "Add a method to the workflow, w-flow.  method-name should be a
  string and cmds should be a vector of command strings.  Options are
  standard drake options as vararg key value pairs, e.g. :my-option
  \"my-value\", or (preferred) a single map. Variables for the method can be
  given in the :vars key and will be substituted."
  [w-flow method-name cmds & options]
  (when ((:methods w-flow) method-name)
    (println (format "Warning: method redefinition ('%s')" method-name)))
  (let [options (utils/varargs->map options)
        w-flow-vars (:vars w-flow)
        step-vars (utils/var-sub-map w-flow-vars (:vars options))
        vars (merge w-flow-vars step-vars)]
    (assoc-in w-flow [:methods method-name] {:opts (dissoc options :vars)
                                             :vars vars
                                             :cmds (mapv utils/var-place cmds)})))

(defn add-methods
  "Adds all the methods in methods-hash to workflow, w-flow.
  methods-hash has method names for keys and vectors of method
  commands for values like this {\"method-name\" [\"method
  commands\"]}"
  [w-flow methods-hash]
  (reduce
   (fn [w-flow [method-name cmds]]
     (method w-flow method-name cmds))
   w-flow
   (seq methods-hash)))

(defn set-var
  "Add a variable to the workflow, w-flow.  var-name and var-value
  should be strings"
  [w-flow var-name var-value]
  (let [vars (:vars w-flow)
        sub-var-name (utils/var-sub vars var-name)
        sub-var-value (utils/var-sub vars var-value)]
    (assoc-in w-flow [:vars sub-var-name] sub-var-value)))

(defn base
  "Shortcut to set BASE in the workflow, w-flow, to new-base"
  [w-flow new-base]
  (set-var w-flow "BASE" new-base))

(defn run-workflow
  "Run the workflow in w-flow.  Optionally specify targetv as a
  key value pair, e.g. :targetv [\"outA.txt\" \"outB.txt\" \"outC.txt]\",
  otherwise the default targetv is core/DEFAULT-TARGETV.
  Other run options to run-workflow can also be specified as key value pairs.
  Set :repl-feedback to :quiet, :default or :verbose to adjust the repl feedback
  level."
  [w-flow & {:keys [targetv repl-feedback]
                 :or {targetv d-core/DEFAULT-TARGETV
                      repl-feedback :default}
                 :as run-options}]
  (let [opts (merge d-opts/DEFAULT-OPTIONS
                    {:auto true}
                    run-options)
        opts-with-eb (if (not= repl-feedback :quiet)
                       (merge opts {:guava-event-bus (utils/start-event-bus)})
                       opts)]
    (d-opts/set-options opts-with-eb)
    (d-core/configure-logging)
    (memo/memo-clear! parse/shell-memo)

    (debug "Drake" d-core/VERSION)
    (info "Clojure version:" *clojure-version*)
    (info "Options:" opts-with-eb)

    (plug/load-plugin-deps (:plugins *options*))
    (fs/with-cwd fs/*cwd*
      (-> w-flow
          (utils/compile-parse-tree)
          (d-core/run targetv)))))
