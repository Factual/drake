(ns drake.core
  (:refer-clojure :exclude [file-seq])
  (:import [java.util.concurrent Semaphore])
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.core.memoize :as memo]
            [clj-logging-config.log4j :as log4j]
            [fs.core :as fs]
            [clojopts.ui :as clojopts]
            [flatland.useful.state :as state]
            ;; register built-in protocols
            drake.protocol_interpreters
            drake.protocol_c4
            drake.protocol_eval
            drake.event
            [drake-interface.core :as di])
  (:use [clojure.tools.logging :only [info debug trace error]]
        [slingshot.slingshot :only [try+ throw+]]
        clojure.set
        clojopts.core
        sosueme.throwables
        drake.stdin
        drake.steps
        drake.plugins
        drake.fs
        [drake.protocol :only [get-protocol-name get-protocol]]
        drake.parser
        drake.options
        [drake.event :only [EventWorkflowBegin EventWorkflowEnd EventStepBegin EventStepEnd EventStepError]]
        drake.utils)
  (:gen-class :methods [#^{:static true} [run_opts [java.util.Map] void]
                        #^{:static true} [run_opts_with_event_bus [java.util.Map com.google.common.eventbus.EventBus] void]]))

(def VERSION "0.1.6")
(def PLUGINS-FILE "plugins.edn")
(def DEFAULT-VARS-SPLIT-REGEX-STR ; matches and consumes a comma; requires that an even number of "
                                  ; characters exist between the comma and end of string
  "(?x)       ## (?x) enables inline formatting and comments
  ,           ## a comma
  (?=         ## as long as what comes next is
    ([^\"]*   ## any number of characters other than \"
    \"        ## then a \"
    [^\"]*\"  ## and more non-\"s followed by a \"
  )*          ## all this any number of times
  [^\"]*$)    ## and finally a bunch of non-quotes before end of string")

(def DEFAULT-OPTIONS {:workflow "./Drakefile"
                      :logfile "drake.log"
                      :jobs 1
                      :plugins PLUGINS-FILE
                      :tmpdir ".drake"})

;; TODO(artem)
;; Optimize for repeated BASE prefixes (we can't just show it
;; without base, since it can be ambiguous)
;; also in (confirm-run), (confirm-merge), (step-dirnname)
;; TODO(artem) Tags are not supported here
(defn- step-string
  "Returns step's symbolic representation for printing."
  [step]
  (str/join " <- "
            (for [[tags files] [[:output-tags :outputs] [:input-tags :inputs]]]
              (str/join ", " (concat (map (partial str "%") (step tags))
                                     (step files))))))

(defn- user-confirms?
  "Returns true if the user enters 'Y', otherwise returns false."
  []
  (print "Confirm? [y/n] ")
  (flush)
  (letfn [(valid? [c] (when (or (= c "y") (= c "n")) c))]
    (if-let [v (valid? (.toLowerCase (read-line-stdin)))]
      (let [confirmed (= v "y")]
        (if-not confirmed (info "Aborted."))
        confirmed)
      (do
        (println "That's not a valid response. Please enter Y or n")
        (recur)))))

(defn- branch-adjust-step
  "Given a step, adjusts inputs and outputs for branches by:
     - adding #branch suffixes to all outputs
     - adding #branch suffixes only to those inputs that do exist in this branch,
       unless add-to-all is true"
  [{:keys [inputs outputs] :as step} add-to-all]
  (let [branch (:branch *options*)
        branch-adjusted-outputs (if (empty? branch)
                                  outputs
                                  (map #(str % "#" branch) outputs))
        branch-adjusted-inputs (if (empty? branch)
                                 inputs
                                 (map #(if (or add-to-all
                                               (fs di/data-in? (str % "#" branch)))
                                         (str % "#" branch)
                                         %)
                                      inputs))]
    (assoc step :inputs branch-adjusted-inputs
                :outputs branch-adjusted-outputs)))

(defn- normalize-filename-for-run
  "Normalizes filename and also removes local filesystem prefix (file:) from
   it. This is safe to do since it's the default filesystem,
   but it gives us a bit easier compatibility with existing tools."
  [filename]
  (let [n (normalized-path filename)]
    (if (= "file" (path-fs n)) (path-filename n) n)))

(defn- despace-cmds
  "Given a sequence of commands, removes leading whitespace found in the first
   line from all lines, e.g.:
      |  1
      |   2
      |  3
   ->
      |1
      | 2
      |3
   If we just delete all leading space from each line independently, we won't
   be able to process languages with semantically significant whitespace,
   such as Python."
  [cmds]
  (let [prefix (apply str (take-while #{\space \tab} (first cmds)))
        prefix-len (count prefix)]
    (for [^String cmd cmds]
      (if (.startsWith cmd prefix)
        (.substring cmd prefix-len)
        cmd))))

(defn- prepare-step-for-run
  "Given a step, prepares its inputs and outputs for running by:
     - adjusting inputs and outputs for branches
     - normalizing all inputs and outputs
     - adding INPUT/OUTPUT variables to vars
     - expanding method's body into the step's commands, if applicable
     - expanding all parsed variables in the commands and converting command
       lines to strings"
  [step {:keys [methods]}]
  (let [{:keys [inputs outputs vars cmds opts] :as step}
            (branch-adjust-step step false)
        normalized-outputs (map normalize-filename-for-run outputs)
        normalized-inputs (map normalize-filename-for-run inputs)
        vars (merge vars
                    (inouts-map normalized-inputs "INPUT")
                    (inouts-map normalized-outputs "OUTPUT"))
        method (methods (:method opts))
        method-mode (:method-mode opts)
        cmds (if (or (not method) (= method-mode "replace"))
               cmds
               (if (= method-mode "append")
                 (concat (:cmds method) cmds)
                 (:cmds method)))
        vars (if-not method vars (merge (:vars method) vars))
        substitute-var #(if-not (set? %)
                         %
                         (let [var-name (first %)]
                           (if (contains? vars var-name)
                             (vars var-name)
                             (throw+
                              {:msg (format
                                     "variable \"%s\" undefined at this point."
                                     var-name)}))))
        cmds (despace-cmds (map #(apply str (map substitute-var %)) cmds))]
    (if (and (empty? cmds) (di/cmds-required? (get-protocol step)))
      (throw+ {:msg
               (format "protocol '%s' requires non-empty commands, for step: %s "
                       (get-protocol-name step)
                       (step-string step))}))
    (assoc step
      :inputs normalized-inputs
      :outputs normalized-outputs
      :cmds cmds
      :vars vars
      :opts (if-not method opts (merge (:opts method) opts)))))

(defn- should-build?
  "Given the parse tree and a step index, determines whether it should
   be built and returns the reason (e.g. 'timestamped') or
   nil if it shouldn't be.

   Arguments (all booleans):
     forced         Whether this step is specified in forced rebuild mode.
     triggered      Whether this step is triggered by an earlier step, i.e.
                    one or more of this steps' inputs is an output of another
                    step scheduled to run before this one.
     match-type     What matching is performed (can be :tag, :method, etc.).
     fail-on-empty  Whether to raise an an exception on any input
                    that lacks data. This only works for triggered steps (see
                    above). If this step is specified manually (root of a
                    dependency subtree), will always fail on an empty input,
                    since it will not be generated by any other step."
  [step forced triggered match-type fail-on-empty]
  (trace "should-build? fail-on-empty: " fail-on-empty)
  (let [{:keys [inputs outputs opts]} (branch-adjust-step step false)
        empty-inputs (filter #(not (fs di/data-in? %)) inputs)
        no-outputs (empty? outputs)]
    (trace "should-build? forced:" forced)
    (trace "should-build? match-type:" match-type)
    (trace "should-build? triggered:" triggered)
    (trace "should-build? inputs: " inputs)
    (trace "should-build? outputs: " outputs)
    (trace "should-build? empty inputs: " empty-inputs)
    (trace "should-build? no-outputs: " no-outputs)
    (if (and (not (empty? empty-inputs)) (or fail-on-empty (not triggered)))
      (throw (Exception. (str "no input data found in locations: "
                              (str/join ", " empty-inputs))))
      ;; check that all output files are present
      (cond
       forced (str "forced" (if (not= match-type :output)
                              (format " (via %s)" (name match-type))))
       ;; steps with no outputs can be specified via a tag or a method only:
       (not= match-type :output) (str "via " (name match-type))
       ;; otherwise no-outputs steps are not built
       no-outputs nil
       ;; one or more outputs are missing? (can only look for those
       ;; for targets which were specified explicitly, not triggered)
       (and (not triggered)
            (some #(not (fs di/data-in? %)) outputs)) "missing output"
       ;; timecheck disabled
       (= false (get-in step [:opts :timecheck])) nil
       ;; built as a dependency?
       triggered "projected timestamped"
       ;; no-input steps are always rebuilt
       (empty? inputs) "no-input step"
       :else
       (let [input-files (mapv newest-in inputs)
             newest-input (apply max (map :mod-time input-files))
             output-files (mapv oldest-in (filter #(fs di/data-in? %) outputs))]
         (let [oldest-output (apply min (map :mod-time output-files))]
           (debug (format "Timestamp checking, inputs: %s, outputs: %s"
                          input-files output-files))
           (debug (format "Newest input: %d, oldest output: %d"
                          newest-input oldest-output))
           (if (> newest-input oldest-output)
             "timestamped"
             nil)))))))

(defn- predict-steps
  "Given a vector of target steps (:index, :build), tries to
   predict what will be run by using timestamp evaluation on
   non-force-build targets and assuming that if a target is
   selected to be run, it will invalidate all its outputs
   (and all its dependencies will also be run).
   Returns filtered target-steps with added :cause key."
  [parse-tree target-steps]
  (trace "predict-steps: target-steps=" target-steps)
  (first
   (reduce (fn [[new-target-steps triggered-deps]
                {:keys [index build match-type] :as step}]
             (let [step-map (get-in parse-tree [:steps index])
                   cause (should-build? step-map (= build :forced)
                                        (triggered-deps index) match-type false)]
               (trace (format "predict-steps, index=%d, cause=%s" index cause))
               (if (nil? cause)
                 [new-target-steps triggered-deps]
                 [(conj new-target-steps (assoc step :cause cause))
                  (set/union triggered-deps
                             (all-dependencies parse-tree index))])))
           [[] {}]
           target-steps)))

(defn steps-report
  "Returns a report of steps-to-run suitable for cli printing."
  [parse-tree steps-to-run]
  (str "The following steps will be run, in order:\n"
       (str/join "\n"
         (for [[i {:keys [index cause]}]
               (map-indexed vector steps-to-run)]
           ;; TODO(artem)
           ;; Optimize for repeated BASE prefixes (we can't just show it
           ;; without base, since it can be ambiguous)
           ;; also in (step-string), (step-dirnname)
           ;;
           ;; If the cause is "projected timestamped", meaning that we will
           ;; also run one or more targets this one depends on, we will
           ;; assume they all will generate data in the branch and add
           ;; branch suffix to all inputs, otherwise behave as the data
           ;; dictates
           (format "  %d: %s [%s]"
                   (inc i)
                   (step-string (branch-adjust-step
                                 (get-in parse-tree [:steps index])
                                 (contains? #{"projected timestamped"
                                              "forced"}
                                            cause)))
                   cause)))))

;; TODO(artem):
;; Let's also write how many, something like
;; targets will be built
;; directly specified: K
;; dependancy-based: L
;; (N = K + L)
;; or something similar...
(defn- confirm-run
  "Estimates the list of steps to be run, asks the user for confirmation,
   returns this new list if confirmed, or nil if rejected."
  [parse-tree steps-to-run]
  (if (:auto *options*)
    true
    (do
      (println (steps-report parse-tree steps-to-run))
      (user-confirms?))))

(defn- spit-step-vars [{:keys [vars dir] :as step}]
  (let [file (fs/file dir (str "vars-" start-time-filename))
        contents (apply str
                        "Environment vars set by Drake:\n\n"
                        (for [[k v] vars]
                          (str k "=" v)))]
    (fs/mkdirs dir)
    (spit file contents)
    (debug "step's vars saved to" (relative-path file))))

(defn- run-step
  "Runs one step performing all necessary checks, returns
   true if the step was actually run; false if skipped."
  [parse-tree step-number {:keys [index build match-type opts]}]
  (let [{:keys [inputs] :as step} (get-in parse-tree [:steps index])]
    ;; TODO(artem)
    ;; Somewhere here, before running the step or checking timestamps, we need to
    ;; check for optional files and replace them with empty strings if they're
    ;; not found (according to the spec). We shouldn't just rewrite :inputs and
    ;; should probably separate two versions, since the step name
    ;; (used in debugging and log files names) should not vary.
    ;; For now just check that none of the input files is optional.
    (if (some #(= \? (first %)) inputs)
      (throw+ {:msg (str "optional input files are not supported yet: "
                         inputs)}))
    (let [step-descr (step-string (branch-adjust-step step false))
          step (-> step
                   (update-in [:opts] merge opts)
                   (prepare-step-for-run parse-tree))
          should-build (should-build? step (= build :forced)
                                      false match-type true)]
      (info "")
      (info (format "--- %d. %s: %s"
                    step-number
                    (if should-build
                      (format "Running (%s)" should-build)
                      "Skipped (up-to-date)")
                    step-descr))
      (when should-build
        ;; save all variable values in --tmpdir directory
        (spit-step-vars step)
        (with-time-elapsed
          #(let [wait (- (*options* :step-delay 0) %)]
             (info (format "--- %d: %s -> done in %.2fs%s"
                           step-number
                           step-descr
                           (/ % 1000.0)
                           (if-not (pos? wait)
                             ""
                             (do (Thread/sleep wait)
                                 (format " + waited %dms" wait))))))
          (di/run (get-protocol step) step)))
      should-build)))

(defn- create-state-atom
  "Create the atom which will keep track of which steps are not yet runnable,
   which steps are runnable, and which steps are done."
  [steps]
  (let [sort-map (zipmap (map :index steps) (range))] ; order sorted-set by step order
    (atom (hash-map
            :not-runnable (into #{} (map :index (filter #(seq (:deps %)) steps)))
            :runnable (into (sorted-set-by #(< (sort-map %1) (sort-map %2)))
                            (map :index (filter #(empty? (:deps %)) steps)))
            :done #{}
            :steps steps))))

(defn- pop-next-step-from-atom
  "Pop the next runnable step from the state atom.
   Block if waiting on dependencies."
  [state-atom]
  (loop []
    (let [{:keys [runnable not-runnable steps] :as state}
          (state/wait-until state-atom #(or (seq (:runnable %))
                                            (empty? (:not-runnable %))))]
      (cond
        ; If there are runnable steps, pop one off and return it
        (seq runnable) (let [popped-step-index (first runnable)]
                         (if (compare-and-set! state-atom state
                                               (update-in state [:runnable] disj popped-step-index))
                           (first (filter #(= (:index %) popped-step-index) steps))
                           (recur))) ; if compare-and-set fails, try the whole thing again

        ; If there are non-runnable steps, go back and wait until one is runnable
        (seq not-runnable) (recur)

        ; Otherwise, there's nothing left
        :else nil))))

(defn- update-state-atom-when-step-finishes
  "Use this with swap! to update the state atom when a step finishes.
   It moves all children of this step which were not runnable but have become
   so (because this step was their last dependency) from :not-runnable to
   :runnable."
  [state step]
  (let [children (:children step)
        state (update-in state [:done] conj (:index step)) ; put this step on the "done" list
        {:keys [done not-runnable steps]} state
        newly-runnable-step-numbers (for [{:keys [index deps]} steps
                                          :when (and (children index)
                                                     (not-runnable index)
                                                     (every? done deps))]
                                      index)]
    (-> (apply update-in state [:not-runnable] disj newly-runnable-step-numbers)
        (update-in [:runnable] into newly-runnable-step-numbers))))

(defn- lazy-step-list
  "Create a lazy list that pops runnable steps from the state-atom."
  [state-atom]
  (take-while identity (repeatedly #(pop-next-step-from-atom state-atom))))

(defn- add-empty-promises-to-steps
  [steps promise-key]
  (map (fn [step] (assoc step promise-key (promise))) steps))

(defn- assoc-promise
  "Associates a promise instance for each step.
  - a promise of value 1 is delivered on success
  - a promise of value 0 is delirvered on failure"
  [steps]
  (add-empty-promises-to-steps steps :promise))

(defn- assoc-deps
  "Associates dependencies as set object containing the indexes for each step"
  [parse-tree steps]
  (let [indexes (into #{} (map :index steps))]
    (for [{:keys [index] :as step} steps]
      (assoc step :deps
             (-> (expand-step-restricted parse-tree index nil indexes)
                 (set)             ; turn into set to remove duplicates
                 (disj index)))))) ; do not mark the step itself as its dependency

(defn- assoc-parents-and-children
  [parse-tree steps]
  (let [indexes (into #{} (map :index steps))
        tree-steps (:steps parse-tree)]
    (for [step steps]
      (let [tree-step (tree-steps (:index step))
            children (intersection (:children tree-step) indexes)
            parentals (intersection (:parents tree-step) indexes)]
        (into (assoc step
                :name (step-string tree-step)
                :children (or children #{})
                :parents (or parentals #{}))
              (for [k [:opts :input-tags :output-tags :id]]
                [k (get tree-step k)]))))))

(defn- assoc-no-stdin-opt
  "Set :no-stdin option for all steps if jobs > 1"
  [jobs steps]
  (if (> jobs 1)
    (map (fn [step] (assoc-in step [:opts :no-stdin] true)) steps)
    steps))

(defn- assoc-exception-promise
  "Associate a promise for any exceptions generated by this step"
  [steps]
  (add-empty-promises-to-steps steps :exception-promise))

(defn- attempt-run-step
  [parse-tree step]
  (let [{:keys [index promise exception-promise]} step]
    (try
      ; run the step (the actual job)
      (run-step parse-tree index step)
      (deliver promise 1) ; delivers a promise of 1/success
      (catch Exception e
        (deliver (:exception-promise step) e))
      (finally
        ; if promise not delivered, deliver a promise of 0/failure
        (when (not (realized? promise))
          (deliver promise 0))))))

(defn- function-for-step
  "Returns an anonymous function that can be triggered in its own thread to execute a step.
  Each step delivers its own promise.  Dependent steps will block on that promise. "
  [parse-tree steps promises-indexed step]
  (fn []
    ; wait for parent promises in the tree promises to be delivered
    ; accumulate successful parent tasks into a sum : successful-parent-steps
    (let [{:keys [promise deps exception-promise]} step]
      (try
        (let [successful-parent-steps (reduce +
                                              (map (fn [i]
                                                     @(promises-indexed i))
                                                   deps))]
          (if (= successful-parent-steps (count deps))
            (attempt-run-step parse-tree step)
            (deliver promise 0)))
        (catch Exception e
          (deliver exception-promise e))
        (finally
          (when (not (realized? promise))
            (deliver promise 0)))))))

(defn- assoc-function
  "Associates a future (anonymous function) for each step"
  [parse-tree steps]
  ; for quickly accessing promises via a map :index => :promise
  (let [promises-indexed (zipmap (map :index steps) (map :promise steps))]
    ; associate a :function on each step
    (map (fn [step]
           (assoc step
                  :function
                  (function-for-step parse-tree steps promises-indexed step)))
         steps)))

(defn- post
  [^com.google.common.eventbus.EventBus event-bus event]
  (when event-bus (.post event-bus event)))

(defn- sanitize-step
  [step]
  (dissoc step :function :promise :exception-promise))

(defn- trigger-futures-helper
  [jobs lazy-steps state-atom event-bus]
  (let [semaphore (new Semaphore jobs true)]
    (loop [steps lazy-steps]
      (.acquire semaphore)
      (when (seq steps)
        (let [step (first steps)
              sanitized-step (sanitize-step step)]
          (future (try
                    (post event-bus (EventStepBegin sanitized-step))
                    ((:function step))
                    (finally
                      (swap! state-atom update-state-atom-when-step-finishes step)
                      (when (realized? (:exception-promise step))
                        (post event-bus (EventStepError sanitized-step @(:exception-promise step))))
                      (post event-bus (EventStepEnd sanitized-step))
                      (.release semaphore))))
          (recur (rest steps)))))))

(defn- trigger-futures
  "Run all the steps in (jobs) number of threads"
  [jobs steps event-bus]
  (let [state-atom (create-state-atom steps)]
    (trigger-futures-helper jobs (lazy-step-list state-atom) state-atom event-bus)))

(defn- await-promises
  "waits for all the promises to be fullfilled otherwise
   we get an premature exit on the main thread
   returns the sum of all deref'd promises each
   returning either 0/1 for failure/success respectively"
  [steps]
  (reduce + (map (fn [step] @(:promise step)) steps)))

(defn- run-steps-async
  "Runs steps asynchronously.
   If concurrence = 1, this will run the steps in the same order as the
   array of their indexes.  If concurrence = N, this will grab the first
   N steps and run in parallel.  As steps complete, it will grab additional
   steps in index order and run them, but no more than N at a time.
   NOTE: less than N steps may be running depending on whether dependencies
   are met for the steps."
  [parse-tree steps]
  (let [jobs (:jobs *options*)
        event-bus (:guava-event-bus *options*)]
    (if (empty? steps)
      (info "Nothing to do.")
      (do
        (info (format "Running %d steps with concurrence of %d..."
                      (count steps)
                      jobs))

        (let [steps-data (->> steps
                           (assoc-deps parse-tree)
                           (assoc-parents-and-children parse-tree)
                           (assoc-no-stdin-opt jobs))
              steps-future (->>
                             steps-data
                             assoc-exception-promise
                             assoc-promise
                             (assoc-function parse-tree))]

          (post event-bus (EventWorkflowBegin steps-data))

          (trigger-futures jobs steps-future event-bus)

          (let [successful-steps (await-promises steps-future)]
            (info (format "Done (%d steps run)." successful-steps))
            (post event-bus (EventWorkflowEnd))
            (when (not= successful-steps (count steps))
              (let [steps-with-exception (filter
                                           #(realized? (:exception-promise %))
                                           steps-future)]
                (if (not-empty steps-with-exception)
                  (throw @(:exception-promise (first steps-with-exception)))
                  (throw+ {:msg (str "successful-steps ("
                                     successful-steps
                                     ") does not equal total steps ("
                                     (count steps)
                                     ")")}))))))))))

(defn print-steps
  "Prints inputs and outputs of steps to run."
  [parse-tree steps-to-run]
  (doseq [step (map (:steps parse-tree) (map :index steps-to-run))]
    (println "S")
    (doseq [[prefix key] [["I" :inputs]
                          ["%I" :input-tags]
                          ["O" :outputs]
                          ["%O" :output-tags]]
            target (step key)]
      (println (str prefix \tab target)))))

(defn run
  "Runs Drake with the specified parse-tree and an array of target
   selection expressions."
  [parse-tree targets]
  (let [target-steps (select-steps parse-tree targets)]
    (debug "selected (expanded) targets:" target-steps)
    (trace "--- Parse Tree: ---")
    (trace (with-out-str (clojure.pprint/pprint parse-tree)))
    (trace "-------------------")
    (let [steps-to-run (predict-steps parse-tree target-steps)]
      (cond
       (empty? steps-to-run)
         (info "Nothing to do.")
       (:print *options*)
         (print-steps parse-tree steps-to-run)
       (:preview *options*)
         (println (steps-report parse-tree steps-to-run))
       :else
         (if (confirm-run parse-tree steps-to-run)
           (run-steps-async parse-tree steps-to-run))))))

(defn- running-under-nailgun?
  "Returns truthy if and only if this JVM process is running under a
   Nailgun server."
  []
  (when-let [java-cmd (-> (System/getProperties)
                          (get "sun.java.command"))]
    (.endsWith ^String java-cmd "nailgun.NGServer")))

(defn- shutdown [exit-code]
  (throw+ {:exit-code exit-code}))

(defn parse-cli-vars [vars-str split-regex-str]
  (when-not (empty? vars-str)
    (let [pairs (str/split vars-str (re-pattern split-regex-str))]
      (reduce
       (fn [acc pair]
         (let [spl (str/split pair #"=" -1)]
           (if (not= (count spl) 2)
             (do
               (println "Invalid variable definition in -v or --vars:" pair)
               (shutdown -1)))
           (conj acc (str/split pair #"="))))
         {}
       pairs))))

(defn build-vars []
  (let [split-regex-str (or
                          (*options* :split-vars-regex)
                          DEFAULT-VARS-SPLIT-REGEX-STR)]
    (merge
      (into {} (System/getenv))
      (parse-cli-vars (:vars *options*) split-regex-str)
      (into {} (for [v (:var *options*)]
                 (str/split v #"=")))
      (when-let [base (:base *options*)]
        {"BASE" base}))))

(defn- with-workflow-file
  "Reads the workflow file from command-line options, parses it,
   and passes the parse tree to the provided function 'f'."
  [f]
  (let [filename (:workflow *options*)
        filename (if-not (fs/directory? filename)
                   filename
                   (let [workflow-file (str filename
                                            (if (not= (last filename) \/) "/")
                                            "Drakefile")]
                     (println "Checking for" workflow-file)
                     workflow-file))]
    (if-not (fs/exists? filename)
      (throw+ {:msg (str "cannot find file or directory: " filename
                         " (use --help for documentation)")})
      ;; Drake will execute all commands in the same directory where
      ;; the workflow file is located in, but preserve the current
      ;; working directory on exit
      (let [abs-filename (fs/absolute-path filename)]
        ;; WARNING: since one can't change working directory in Java
        ;; what this call does is making all subsequent calls to fs.core
        ;; behave as if the current directory was the one specified here.
        ;; But all other calls (for example, java.lang.Runtime.exec) would
        ;; still behave the same way.
        ;;
        ;; Consequently, please do not use:
        ;;    (spit filename "text")
        ;; use:
        ;;    (spit (fs/file filename) "text")
        ;;
        ;; Then the file will be created in the correct location.
        (fs/with-cwd (fs/parent abs-filename)
          (let [parse-tree (parse-file abs-filename (build-vars))
                steps (map (fn [step]
                             (assoc step :id (str (java.util.UUID/randomUUID))))
                           (:steps parse-tree)) ; add unique ID to each step
                steps (into [] steps)
                parse-tree (assoc parse-tree :steps steps)]
            (f parse-tree)))))))

(defn configure-logging
  []
  (let [loglevel (cond
                   (:trace *options*) :trace
                   (:debug *options*) :debug
                   (:quiet *options*) :error
                   :else :info)
        logfile (:logfile *options*)
        logfile (if (fs/absolute? logfile)
                  logfile
                  (fs/file (fs/parent (:workflow *options*))
                           logfile))]
    (log4j/set-loggers! "drake"
                        {:out logfile
                         :level loglevel
                         :pattern "%d %p %m%n"}
                        "drake"
                        {:name "console"
                         :pattern "%m%n"
                         :level loglevel})))

(defn- confirm-move
  ;; TODO(artem) doc
  [outputs]
  (if (:auto *options*)
    true
    (do
      (println "The following directories will be moved:")
      (doseq [[from to] outputs]
        (println (format "  %s -> %s" from to)))
      (user-confirms?))))

(defn- merge-branch
  [parse-tree targets]
  (let [branch (:merge-branch *options*)
        ;; TODO(artem) potential copy-paste with run, try to eliminate
        target-steps (select-steps parse-tree targets)
        ;; Collect selected steps' outputs, if they exist in the branch
        ;; We also need to normalize output filenames and add branch suffixes
        ;; to them
        steps (map (comp (:steps parse-tree) :index)
                   target-steps)
        all-outputs (mapcat :outputs steps)
        ;; vector of tuples [from, to]
        outputs-for-move (filter identity
                                 (map #(let [branched (str % "#" branch)]
                                         (if (fs di/data-in? branched)
                                           [branched %]
                                           nil)) all-outputs))]
    (if (empty? outputs-for-move)
      (println "Nothing to do.")
      (if (confirm-move outputs-for-move)
        (do
          (doseq [[from to] outputs-for-move]
            (println (format "Moving %s to %s..." from to))
            ;; from and to always share a filesystem
            (let [fs (first (get-fs from))]
              (di/rm fs (path-filename to))
              (di/mv fs (path-filename from) (path-filename to))))
          (println "Done."))))))

(defn- check-for-conflicts
  [opts]
  (let [groups [#{:print :auto}
                #{:print :preview}
                #{:branch :merge-branch}
                #{:debug :trace :quiet}]
        crossovers [[#{:quiet :step-delay} #{:print :preview}]]
        option-list (fn [opts] (str/join ", " (map #(str "--" (name %)) opts)))
        complain (fn [msg]
                   (println msg)
                   (println "use --help for documentation")
                   (shutdown -1))]
    ;; mutually exclusive
    (dorun (map #(let [used (set/intersection % (into #{} (keys opts)))]
                   (if (> (count used) 1)
                     (complain
                      (str "The following options cannot be used together: "
                           (option-list used)))))
                groups))
    ;; the ones on the left not compatible with ones on the right
    (dorun (map #(let [used (map (partial set/intersection
                                          (into #{} (keys opts))) %)]
                   (if (every? (complement empty?) used)
                     (complain
                      (str "Option(s) " (option-list (first used))
                           " cannot be used together with option(s) "
                           (option-list (second used))))))
                crossovers))))

(defn drake
  "Runs Drake's CLI.

   This can be called from the REPL for development purposes. You should include
   the following option:
     --auto (otherwise the interactive user confirmation will hang on you)
   You don't need --auto if you use --preview.

   Examples:
     (drake \"--version\")
     (drake \"--preview\" \"-w\" \"demos/factual\" \"+...\")
     (drake \"--auto\" \"-w\"
            \"some/workflow-file.drake\" drake \"+...\" \"-^D\" \"-=B\")

   TODO: log messages don't show up on the REPL (but printlns do).
         Can this be fixed?"
  [& args]
  (let [;; We ignore 80 character limit here, since clojopts is a macro
        ;; and calls to (str) do not work inside a clojopts call
        options (try
                  (binding [clojopts/*stop-at-first-non-option* true]
                    (clojopts
                     "drake"
                     args
                     (with-arg workflow w
                       "Name of the workflow file to execute; if a directory, look for Drakefile there."
                       :type :str
                       :user-name "file-or-dir-name")
                     (with-arg jobs j
                       "Specifies the number of jobs (commands) to run simultaneously. Defaults to 1"
                       :type :int
                       :user-name "jobs-num")
                     (no-arg auto a
                             "Do not ask for user confirmation before running steps.")
                     (no-arg preview P
                             "Prints the steps that would run, then stops.")
                     (with-arg base
                       "Specifies BASE directory. Takes precedence over environment."
                       :type :str
                       :user-name "dir-name")
                     (with-arg vars v
                       "Add workflow variable definitions. For example -v X=1,Y=2,FILE=a.csv"
                       :type :str
                       :user-name "name-value-pairs")
                     (with-arg var
                       "Set a workflow variable."
                       :type :str
                       :group :list
                       :user-name "value")
                     (with-arg branch b
                       "Specifies a working branch (see spec for details)."
                       :type :str
                       :user-name "name")
                     (with-arg merge-branch
                       "Merges the specified targets (by default, all) of the given branch to the main branch. Data files are overwritten, backup files are merged (see spec for details)."
                       :type :str
                       :user-name "name")
                     (no-arg print p
                             "Runs Drake in \"print\" mode. Instead of executing steps, Drake just prints inputs, outputs and tags of each step that is scheduled to run to stdout. This is useful if some outside actions need to be taken before or after running Drake. Standard target matching rules apply. Inputs are prepended by I, outputs by O, and input and output tags by %I and %O respectively. It also outputs \"S\" to signify beginning of each step.")
                     (with-arg logfile l
                       "Specify the log file. If not absolute, will be relative to the workflow file, default is drake.log in the directory of the workflow file."
                       :type :str
                       :user-name "filename")
                     (no-arg repl r
                             "Supports REPL based running of Drake; foregoes JVM shutdown, et. al.")
                     (with-arg step-delay
                       "Specifies a period of time, in milliseconds, to wait after completion of each step. Some file systems have low timestamp resolution, and small steps can proceed so quickly that outputs of two or more steps can share the same timestamp, and will be re-built on a subsequent run of Drake. Also, if the clocks on HDFS and local filesystem are not perfectly synchronized, timestamped evaluation can break down. Specifying a delay can help in both cases."
                       :type :int
                       :user-name "ms")
                     (with-arg plugins
                       "Specifies a plugins configuration file. All dependencies listed in the file will be added to the classpath, and steps that call non-built-in protocols will look for protocol implementations in those dependencies."
                       :type :file
                       :user-name "filename")
                     (with-arg aws-credentials s
                       "Specifies a properties file containing aws credentials. The access_id should be in a property named 'access_key', while the secret part of the key should be in a property names 'secret_key'. Other values in the properties file are ignored."
                       :type :str
                       :user-name "properties-file")
                     (no-arg quiet q
                             "Suppress all Drake's output.")
                     (no-arg debug
                             "Turn on verbose debugging output.")
                     (no-arg trace
                             "Turn on even more verbose debugging output.")
                     (no-arg version
                             "Show version information.")
                     (with-arg tmpdir
                       "Specifies the temporary directory for Drake files (by default, .drake/ in the same directory the main workflow file is located)."
                       :type :str
                       :user-name "tmpdir")
                     (with-arg split-vars-regex
                       "Specifies a regex to split up the --vars argument (by default, a regex that splits on commas except commas within double quotes)."
                       :type :str
                       :user-name "regex")))
                  (catch IllegalArgumentException e
                    (println
                     (str "\nUnrecognized option: "
                          "did you mean target exclusion?\nto build "
                          "everything except 'target'"
                          " run:\n  drake ... -target\n"
                          "or:\n  drake -- -target"))
                    (shutdown -1)))
        targets (:clojopts/more options)
        ;; if a flag is specified, clojopts adds the corresponding key
        ;; to the option map with nil value. here we convert them to true.
        ;; also, the defaults are specified here.
        options (into DEFAULT-OPTIONS
                      (for [[k v] options] [k (if (nil? v) true v)]))]
    (flush) ;; we need to do it for help to always print out
    (let [targets (or (not-empty targets) ["=..."])]
      (when (:version options)
        (println "Drake Version" VERSION "\n")
        (shutdown 0))
      (when (some #{"--help"} args)
        (shutdown 0))

      (check-for-conflicts options)
      (set-options options)

      (configure-logging)
      (memo/memo-clear! drake.parser/shell-memo)

      (debug "Drake" VERSION)
      (debug "Clojure version:" *clojure-version*)
      (debug "parsed options:" options)
      (debug "parsed targets:" targets)

      (try+
        (load-plugin-deps (:plugins *options*))
        (let [fn (if (empty? (:merge-branch options)) run merge-branch)]
          (with-workflow-file #(fn % targets)))
        (shutdown 0)
        (catch map? m
          (error (str "drake: " (:msg m)))
          (shutdown (get m :exit-code 1)))
        (catch Exception e
          (error (stack-trace-str e))
          (shutdown 1))))))

(defn -main
  "Runs drake, and catches (shutdown) exceptions to cleanly shut down."
  [& args]
  (try+
    (apply drake args)
    (catch :exit-code {:keys [exit-code]}
      (when (not (true? (:repl *options*)))
        (if (running-under-nailgun?)
          (debug (str "core/shutdown: Running under Nailgun; "
                      "not calling (shutdown-agents)"))
          (do
            (debug "core/shutdown: Running standalone; calling (shutdown-agents)")
            (shutdown-agents)))
        (System/exit exit-code)))))

(defn run-opts [opts]
  (let [opts (merge {:auto true} opts)]
    (set-options opts)
    (configure-logging)
    (memo/memo-clear! drake.parser/shell-memo)

    (debug "Drake" VERSION)
    (info "Clojure version:" *clojure-version*)
    (info "Options:" opts)

    (load-plugin-deps (:plugins *options*))
    (with-workflow-file #(run % (:targetv opts)))))

(defn -run_opts
  "Explicitly for use from Java"
  [opts]
  (run-opts (into DEFAULT-OPTIONS
                  (for [[k v] opts] [(keyword k) v]))))

(defn -run_opts_with_event_bus
  "Explicitly for use from Java"
  [opts event_bus]
  (let [opts (merge {:guava-event-bus event_bus} opts)]
    (run-opts (into DEFAULT-OPTIONS
                    (for [[k v] opts] [(keyword k) v])))))

(defn run-workflow
  "This can be called from the REPL or Clojure code as a way of
   using this ns as a library. Runs in auto mode, meaning there
   won't be an interactive user confirmation before running steps.

   You must specify a non-empty :targetv.

   Examples:
     (run-workflow \"demos/factual\" :targetv [\"+...\"])
     (run-workflow \"demos/factual\" :targetv [\"+...\"] :branch \"MYBRANCH\")
     (run-workflow \"some/workflow-file.drake\" :targetv [\"+...\" \"-^D\" \"-=B\"]
                   :branch \"MYBRANCH\" :preview true)

   TODO: log messages don't show up on the REPL (but printlns do).
         Can this be fixed?"
  ([workflow & {:as opts}]
     (run-opts (merge DEFAULT-OPTIONS
                      {:workflow workflow}
                      opts)))
   ([]
    (run-opts DEFAULT-OPTIONS)))
