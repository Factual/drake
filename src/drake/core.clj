(ns drake.core
  (:refer-clojure :exclude [file-seq])
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clj-logging-config.log4j :as log4j]
            [fs.core :as fs]
            ;; register protocols
            drake.protocol_interpreters
            drake.protocol_c4
            drake.protocol_eval)
  (:use [clojure.tools.logging :only [info debug trace error]]
        [slingshot.slingshot :only [try+ throw+]]
        clojopts.core
        sosueme.throwables
        drake.stdin
        drake.steps
        drake.fs
        [drake.protocol :only [get-protocol-name get-protocol]]
        drake.parser
        drake.options
        drake.utils)
  (:gen-class :methods [#^{:static true} [run_opts [java.util.Map] void]]))

(def VERSION "0.1.3")

;; TODO(artem)
;; Optimize for repeated BASE prefixes (we can't just show it
;; without base, since it can be ambiguous)
;; also in (confirm-run), (confirm-merge), (step-dirnname)
;; TODO(artem) Tags are not supported here
(defn- step-string
  "Returns step's symbolic representation for printing."
  [step]
  (str (str/join ", " (concat (map #(str "%" %) (step :output-tags))
                              (step :outputs)))
       " <- "
       (str/join ", " (concat (map #(str "%" %) (step :input-tags))
                              (step :inputs)))))

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
  (let [branch (*options* :branch)
        branch-adjusted-outputs (if (empty? branch)
                                  outputs
                                  (map #(str % "#" branch) outputs))
        branch-adjusted-inputs (if (empty? branch)
                                 inputs
                                 (map #(if (or add-to-all
                                               (fs data-in? (str % "#" branch)))
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
    (map #(if (.startsWith %1 prefix) (.substring %1 prefix-len) %1) cmds)))

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
        method (methods (opts :method))
        method-mode (opts :method-mode)
        cmds (if (or (not method) (= method-mode "replace"))
               cmds
               (if (= method-mode "append")
                 (concat (method :cmds) cmds)
                 (method :cmds)))
        vars (if-not method vars (merge (method :vars) vars))
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
    (if (and (empty? cmds) (.cmds-required? (get-protocol step)))
      (throw+ {:msg
               (format "protocol '%s' requires non-empty commands, for step: %s "
                       (get-protocol-name step)
                       (step-string step))}))
    (assoc step
      :inputs normalized-inputs
      :outputs normalized-outputs
      :cmds cmds
      :vars vars
      :opts (if-not method opts (merge (method :opts) opts)))))

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
        empty-inputs (filter #(not (fs data-in? %)) inputs)
        no-outputs (empty? outputs)]
    (trace "should-build? forced:" forced)
    (trace "should-build? match-type:" match-type)
    (trace "should-build? triggered:" triggered)
    (trace "should-build? inputs: " inputs)
    (trace "should-build? outputs: " outputs)
    (trace "should-build? empty inputs: " empty-inputs)
    (trace "should-build? no-outputs: " no-outputs)
    (if (and (not (empty? empty-inputs)) (or fail-on-empty (not triggered)))
      (throw+ {:msg (str "no input data found in locations: "
                         (str/join ", " empty-inputs))})
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
            (some #(not (fs data-in? %)) outputs)) "missing output"
       ;; timecheck disabled
       (= false (get-in step [:opts :timecheck])) nil
       ;; built as a dependency?
       triggered "projected timestamped"
       ;; no-input steps are always rebuilt
       (empty? inputs) "no-input step"
       :else
       (let [input-files (mapv newest-in inputs)
             newest-input (apply max (map :mod-time input-files))
             output-files (mapv oldest-in (filter #(fs data-in? %) outputs))]
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
             (let [step-map ((parse-tree :steps) index)
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
               (keep-indexed vector steps-to-run)]
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
                                 ((parse-tree :steps) index)
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
  (if (*options* :auto)
    true
    (do
      (println (steps-report parse-tree steps-to-run))
      (user-confirms?))))

(defn- spit-step-vars [{:keys [vars dir] :as step}]
  (let [filename (str dir "/vars-" start-time-filename)
        contents (apply str
                        "Environment vars set by Drake:\n\n"
                        (map #(str (key %) "=" (val %) "\n") vars))]
    (if-not (fs/exists? dir)
      (fs/mkdirs dir))
    ;; we need to use fs.core/file here, since fs.core/with-cwd only changes the
    ;; working directory for fs.core namespace
    (spit (fs/file filename) contents)
    (debug "step's vars saved to" (relative-path filename))))

(defn- run-step
  "Runs one step performing all necessary checks, returns
   true if the step was actually run; false if skipped."
  [parse-tree step-number {:keys [index build match-type]}]
  (let [step ((parse-tree :steps) index)
        inputs (step :inputs)]
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
          step (prepare-step-for-run step parse-tree)
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
        ;; save all variable values in .drake directory
        (spit-step-vars step)
        (with-time-elapsed
          #(let [wait (- (*options* :step-delay 0) %)]
             (info (format "--- done in %.2fs%s"
                           (/ % 1000.0)
                           (if-not (> wait 0)
                             ""
                             (do (. Thread (sleep wait))
                                 (format " + waited %dms" wait))))))
          (.run (get-protocol step) step)))
      should-build)))

(defn- run-steps [parse-tree steps]
  "Runs steps in order given as an array of their indexes"
  (if (empty? steps)
    (info "Nothing to do.")
    (do
      (info (format "Running %d steps..." (count steps)))
      (let [steps-run (reduce (fn [x [i step]]
                                (inc-if (run-step parse-tree i step) x))
                              0 (keep-indexed vector steps))]
        (info "")
        (info (format "Done (%d steps run)." steps-run))))))

(defn print-steps
  "Prints inputs and outputs of steps to run."
  [parse-tree steps-to-run]
  (dorun (map
          (fn [step]
            (do
              (println "S")
              (doseq [[prefix key] [["I" :inputs]
                                    ["%I" :input-tags]
                                    ["O" :outputs]
                                    ["%O" :output-tags]]]
                (dorun (map #(println (str prefix \tab %)) (step key))))))
          (map (:steps parse-tree) (map :index steps-to-run)))))

(defn run
  "Runs Drake with the specified parse-tree and an array of target
   selection expressions."
  [parse-tree targets]
  (let [steps (parse-tree :steps)
        target-steps (select-steps parse-tree targets)]
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
           (run-steps parse-tree steps-to-run))))))

(defn- running-under-nailgun?
  "Returns truthy if and only if this JVM process is running under a
   Nailgun server."
  []
  (when-let [java-cmd (-> (System/getProperties)
                          (get "sun.java.command"))]
    (.endsWith java-cmd "nailgun.NGServer")))

(defn- shutdown [exit-code]
  (when (not (true? (:repl *options*)))
    (if (running-under-nailgun?)
      (debug (str "core/shutdown: Running under Nailgun; "
                  "not calling (shutdown-agents)"))
      (do
        (debug "core/shutdown: Running standalone; calling (shutdown-agents)")
        (shutdown-agents)))
    (System/exit exit-code)))

(defn parse-cli-vars [vars-str]
  (when-not (empty? vars-str)
    (let [pairs (str/split vars-str #",")]
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
  (merge
   (into {} (System/getenv))
   (parse-cli-vars (*options* :vars))
   (when-let [base (*options* :base)]
     {"BASE" base})))

(defn- with-workflow-file
  "Reads the workflow file from command-line options, parses it,
   and passes the parse tree to the provided function 'f'."
  [f]
  (let [filename (*options* :workflow)
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
          (let [parse-tree (parse-file abs-filename (build-vars))]
            (f parse-tree)))))))

(defn split-command-line
  "Splits command-line options into two parts: options and targets.
   The first word which does not start with '-' and not an option
   parameter is considered the start of the list of targets.
   Returns a tuple of vectors."
  [args]
  (let [non-flag-long #{"--workflow" "--branch" "--merge-branch"
                        "--logfile" "--vars" "--base"
                        "--aws-credentials" "--step-delay"}
        non-flag-short #{\w \b \l \v \s}]
    (loop [i 0]
      (if (>= i (count args))
        [args []]
        (let [curarg (args i)]
          (if (or (non-flag-long curarg)
                  (and (>= (count curarg) 2)      ; starts with a single dash,
                       (= (first curarg) \-)      ; last letter is in
                       (not= (second curarg) \-)  ; non-flag-short
                       (non-flag-short (last curarg))))
              (recur (+ i 2))
              (if (= \- (first (args i)))
                (recur (inc i))
                (split-at i args))))))))

(defn- configure-logging
  []
  (let [level-map {:debug :debug
                   :trace :trace
                   :quiet :error}
        loglevel (if-let [level (first (apply set/intersection
                                              (map #(into #{} (keys %))
                                                   [level-map *options*])))]
                   (level-map level)
                   :info)
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
  (if (*options* :auto)
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
        steps (map (parse-tree :steps) (map :index target-steps))
        all-outputs (mapcat :outputs steps)
        ;; vector of tuples [from, to]
        outputs-for-move (filter identity
                                 (map #(let [branched (str % "#" branch)]
                                         (if (fs data-in? branched)
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
              (.rm fs (path-filename to))
              (.mv fs (path-filename from) (path-filename to))))
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

(defn -main
  "Runs Drake's CLI.

   This can be called from the REPL for development purposes. You should include
   the following options:
     --repl (otherwise your REPL will likely be killed by Drake's exit)
     --auto (otherwise the interactive user confirmation will hang on you)
   You don't need --auto if you use --preview.

   Examples:
     (-main \"--repl\" \"--version\")
     (-main \"--repl\" \"--preview\" \"-w\" \"demos/factual\" \"+...\")
     (-main \"--repl\" \"--auto\" \"-w\"
            \"some/workflow-file.drake\" drake \"+...\" \"-^D\" \"-=B\")

   TODO: log messages don't show up on the REPL (but printlns do).
         Can this be fixed?"
  [& args]
  (let [[opts targets] (split-command-line (into [] args))
        ;; We ignore 80 character limit here, since clojopts is a macro
        ;; and calls to (str) do not work inside a clojopts call
        options (try
                  (clojopts
                   "drake"
                   opts
                   (with-arg workflow w
                     "Name of the workflow file to execute; if a directory, look for Drakefile there."
                     :type :str
                     :user-name "file-or-dir-name")
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
                     "Show version information."))
                  (catch IllegalArgumentException e
                    (println
                      (str "\nUnrecognized option: "
                           "did you mean target exclusion?\nto build "
                           "everything except 'target'"
                           " run:\n  drake ... -target"))
                    (shutdown -1)))
        ;; if a flag is specified, clojopts adds the corresponding key
        ;; to the option map with nil value. here we convert them to true.
        ;; also, the defaults are specified here.
        options (into {:workflow "./Drakefile"
                       :logfile "drake.log"}
                      (for [[k v] options] [k (if (nil? v) true v)]))]
    (flush)    ;; we need to do it for help to always print out
    (let [targets (if (empty? targets) ["=..."] targets)]
      (when (options :version)
        (println "Drake Version" VERSION "\n")
        (shutdown 0))

      (check-for-conflicts options)
      (set-options options)
      (configure-logging)

      (debug "Drake" VERSION)
      (debug "Clojure version:" *clojure-version*)
      (debug "parsed options:" options)
      (debug "parsed targets:" targets)

      (try+
       (let [fn (if (empty? (:merge-branch options)) run merge-branch)]
         (with-workflow-file #(fn % targets)))
       (shutdown 0)
       (catch map? m
         (error (str "drake: " (m :msg)))
         (shutdown (or (get m :exit) 1)))
       (catch Exception e
         (.printStackTrace e)
         (error (stack-trace-str e))
         (shutdown 1))))))

(defn run-opts [opts]
  (let [opts (merge {:auto true} opts)]
    (set-options opts)
    (with-workflow-file #(run % (:targetv opts)))))

(defn -run_opts
  "Explicitly for use from Java"
  [opts]
  (run-opts (into {} (for [[k v] opts] [(keyword k) v]))))

(defn run-workflow
  ([workflow & {:as opts}]
     (run-opts (merge opts {:workflow workflow})))
   ([]
    (run-opts {})))

#_(defn run-workflow
  "This can be called from the REPL or Clojure code as a way of
   using this ns as a library. Runs in auto mode, meaning there
   won't be an interactive user confirmation before running steps.

   Specify an empty targetv to get the same result as running Drake
   with no targets.

   Examples:
     (run-workflow \"demos/factual\" [])
     (run-workflow \"demos/factual\" [\"+...\"])
     (run-workflow \"demos/factual\" [\"+...\"] :branch \"MYBRANCH\")
     (run-workflow \"some/workflow-file.drake\" [\"+...\" \"-^D\" \"-=B\"]
                   :branch \"MYBRANCH\" :preview true)

   TODO: log messages don't show up on the REPL (but printlns do).
         Can this be fixed?"
  [workflow targetv & {:as opts}]
  (set-options
   (merge opts
          {:workflow workflow
           ;; Prevent interactive user confirmation. We can later
           ;; refactor the run function to be more library-like,
           ;; rather than cli-like.
           :auto true}))
  (with-workflow-file #(run % targetv)))
