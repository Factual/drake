(ns drake.steps
  "This module implement target dependencies and selection.

  The main function is select-steps, and targets are represented
  as structured described in process-qualifiers documentation.
  The process begins by creating these target structures from target
  strings (^A, +B, -^C etc.), then matching each target against the steps
  (each target can match multiple steps, for example, in regexp matching),
  and then expanding each step according to its tree mode (down-tree, up-tree
  or this step only).

  Then this final big list of steps is processed one-by-one to make sure
  dependants are always run after their dependencies, as well as process
  exclusions (see add-step function)."
  (:use [slingshot.slingshot :only [throw+]]
        drake.utils
        [drake.fs :only [remove-extra-slashes normalized-path]])
  (:require [clojure.string :as str]
            [fs.core :as fs]))

(defn step-str
  "Returns a string representation of the step as a comma-separated
   list of its outputs and tags.
   TODO(artem) tags are not supported now."
  [step]
  (str/join ", " (step :outputs)))

(defn add-dependencies
  "Given a parse tree after string substitutions, adds step dependencies to
   its steps (see parse.clj main comment).

   Also adds input and output maps to the parse-tree.

   This function is automatically invoked from parse/parse-linevs.
   It is in this module since all the functions below heavily depend
   on the state created by it."
  [raw-parse-tree]
  (let [steps (:steps raw-parse-tree)
        ;; goes over steps and maps values returned by function f (a sequence)
        ;; into a lists of step step indexes that have them, for example, if
        ;; f returns list of inputs, the output map could be:
        ;;   { "/a" [0 2] "/b" [3] }
        step-index-map (fn [f] (reverse-multimap
                                (map-indexed vector (map f steps))))
        ;; returns a function that maps over a given key
        map-key (fn [f key] #(map f (% key)))

        input-tags-map (step-index-map :input-tags)
        output-tags-map (step-index-map :output-tags)

        method-map (step-index-map #(if-let [method (get-in % [:opts :method])]
                                      [method] []))

        normalized-input-map (step-index-map (map-key normalized-path :inputs))
        normalized-output-map (step-index-map (map-key normalized-path :outputs))

        build-variants (fn [variants] (map step-index-map variants))
        output-map-lookup-regexp
          (apply merge-multimaps-distinct
                 (build-variants [:raw-outputs
                                  (map-key remove-extra-slashes :raw-outputs)
                                  :outputs
                                  (map-key remove-extra-slashes :outputs)]))
        output-map-lookup (merge-multimaps-distinct output-map-lookup-regexp
                                                    normalized-output-map)
        ;;_ (prn output-map-lookup)
        ]
    (assoc raw-parse-tree
      :output-map-lookup output-map-lookup
      :output-map-lookup-regexp output-map-lookup-regexp
      :output-tags-map output-tags-map
      :method-map method-map
      ;; this basically calculates dependencies
      ;; (mapv is used to convert it to vector for indexed lookup in the future)
      :steps (mapv
              #(assoc %
                 :parents (apply
                           concat-distinct
                           (concat (map normalized-output-map
                                        (map normalized-path (% :inputs)))
                                   (map output-tags-map
                                        (% :input-tags))))
                 :children (apply
                            concat-distinct
                            (concat (map normalized-input-map
                                         (map normalized-path (% :outputs)))
                                    (map input-tags-map
                                         (% :output-tags)))))
              steps))))

;; No way to get to MAX_PATH from Java
;; Leave some characters for unique suffixes and for files inside
(def ^:private MAX_PATH 200)

(defn calc-step-dirs
  "Given the parse-tree, calculate each step's directory for keeping
   log and temporary files. Ensures that directory names are unique
   and not too long.

   Returns the parse tree with added :dir to each step."
  [{:keys [steps] :as parse-tree}]
  (let [drake-dir (fs/absolute-path ".drake")]
    (if (> (count drake-dir) (dec MAX_PATH))
      (throw+ {:msg (format "workflow directory name %s is too long."
                            drake-dir)}))
    (let [cut #(.substring % 0 (min (count %) MAX_PATH))
          dirs (map (fn [{:keys [raw-outputs output-tags] :as step}]
                      (cut (str drake-dir "/"
                                ;; e.g. "output1,dir1_dir2_output2,tag1"
                                (str/join "," (map #(str/replace % #"/" "_")
                                                   (concat raw-outputs
                                                           output-tags))))))
                    steps)
          ;; { "dir1" [0] "dir2" [1 2] }
          dir-indexed (reverse-multimap (map-indexed vector (map vector dirs)))
          ]
      (reduce (fn [tree [dir steps]]
                ;; add .0, .1, etc. but only if needed,
                ;; i.e. more than 1 directory with the same name
                (let [single (= 1 (count steps))]
                  (reduce (fn [tree [count step-index]]
                            (assoc-in tree [:steps step-index :dir]
                                      (str dir (if-not single
                                                 (format ".%d" count) ""))))
                          tree (map-indexed vector steps))))
              parse-tree dir-indexed))))

;; TODO(artem): Templates are not supported now
(defn- find-target-steps
  "Given a target, returns a list of steps indexes that match this target.
   See process-qualifiers function to see how a target is prepared."
  [parse-tree {:keys [name match-type match-string]}]
  (let [[map-to-use map-to-use-regexp]
          (match-type {:tag    [:output-tags-map :output-tags-map]
                       :method [:method-map :method-map]
                       :output [:output-map-lookup :output-map-lookup-regexp]})
        dots (= match-string "...")
        regexp-search (= \@ (first match-string))
        everything (and (not regexp-search)
                        dots (= match-type :output))
        targets (if everything
                  ;; optimization for a frequent case
                  (range 0 (count (parse-tree :steps)))
                  (if-not (or regexp-search dots)
                    (apply concat-distinct
                           (vals (select-keys
                                  (parse-tree map-to-use)
                                  (if-not (= match-type :output)
                                    [match-string]
                                    ;; if matching by filenames,
                                    ;; match by all variants - given,
                                    ;; slash-normalized, and full-normalized
                                    [match-string
                                     (remove-extra-slashes match-string)
                                     (normalized-path match-string)]))))
                    (let [re (if-not dots (re-pattern (clip match-string)))]
                      ;; sorting to preserve matching in the order of definition
                      (sort (mapcat (fn [[output index]]
                                      (if (or dots (re-find re output)) index))
                                    (parse-tree map-to-use-regexp))))))]
    (if-not (empty? targets)
      targets
      (throw+ {:msg (str "target not found: " name)}))))

(defn- expand-step-recur
  "The recursive function used in expand-step (see below).
   current-chain is the current set of targets and is used
   in cycle detection.

   current-chain is a vector right now, a hashset would be faster,
   but I would like to preserve the order for being able to print
   a nice error message."
  [tree-steps index up-tree current-chain]
  ;;(prn index)
  (let [step (tree-steps index)
        current-chain-and-me (conj current-chain index)]
    (if (not= -1 (.indexOf current-chain index))
      (throw+ {:msg (str "cycle dependency detected: "
                         (str/join " -> " (map #(step-str (tree-steps %))
                                               current-chain-and-me)))}))
    (let [all-but-me (mapcat #(expand-step-recur tree-steps %
                                                 up-tree current-chain-and-me)
                             (step (if up-tree :parents :children)))]
      ;; conj appends an element to the back of the vector (which
      ;; is what we need for up-tree mode), but to the beginning
      ;; of the sequence (which is what we need for down-tree)
      (conj (if up-tree (into [] all-but-me) all-but-me) index))))

(defn- expand-step
  "Given a step index, return an ordered list of all steps involved
   into building the given step.

   Tree mode can be either :down, :only or nil for default (up-tree)."
  [parse-tree index tree-mode]
  ;;(prn (parse-tree :steps))
  (if (= tree-mode :only)
    [index]
    (expand-step-recur (parse-tree :steps) index (nil? tree-mode) [])))

(def all-dependencies
  "A memoized function to expand the step and all its dependencies
   downwards."
  (memoize #(into #{} (expand-step %1 %2 :down))))

(defn- clip-only
  [str charset]
  "Removes the first character from the string, iff it is present
   in charset (a set of characters), and returns a tuple:
     [ new-string, removed-character ]

   If the first character of the string is not present in charset,
   returns [str nil]"
  (let [ch (first str)]
    (if (charset ch)
      [(clip str) ch]
      [str nil])))

(defn- process-qualifiers
  "Given a list of target names, calculates build and tree modes
   for them. Returns a list of structures of the following format:

   { :name '%@target'        ;; target name stripped out of tree and build modes,
                             ;; could still be a partial name or a regexp,
                             ;; and have tag and method symbols
     :tree  :down            ;; :down, :only, or nil for default (up)
     :build :exclude         ;; :forced, :exclude or nil for default
                             ;; (timestamped)
     :match-type :tag        ;; :output, :tag or :method
     :match-string '@target' ;; the final match string (no tag/method symbols)
   }"
  [target-names]
  (map #(let [[clipped-name build-mode] (clip-only % #{\+ \-})
              [clipped-name tree-mode]  (clip-only clipped-name #{\^ \=})
              [match-type match-string]
                (cond
                  (= \% (first clipped-name))
                    [:tag (clip clipped-name)]
                  (= "()" (.substring clipped-name
                                      (max 0 (- (count clipped-name) 2))))
                    [:method (.substring clipped-name 0
                                         (- (count clipped-name) 2))]
                  :else
                    [:output clipped-name])]
          {:name  clipped-name
           :build ({\+ :forced \- :exclude} build-mode)
           :tree  ({\= :only \^ :down} tree-mode)
           :match-type match-type
           :match-string match-string})
       target-names))

(defn- match-target-steps
  "Given the list of targets returned by process-qualifiers,
   expands each target (which can match several steps)
   into one or more records which have
   :index instead of :name, but inherit the same build and tree modes."
  [parse-tree targets]
  (mapcat (fn [{:keys [build tree match-type] :as target}]
            (map #(hash-map :index %
                            :build build
                            :tree tree
                            :match-type match-type)
                 (find-target-steps parse-tree target)))
          targets))

(defn- expand-targets
  "Given the list of targets with indexes as returned by
   match-target-steps, expands targets down- or up-tree as specified.
   Tree-mode is no longer relevant, but all new targets inherit the
   build mode."
  [parse-tree targets]
  (mapcat (fn [{:keys [index build tree match-type] :as target}]
            (map #(hash-map :index %
                            :build build
                            ;; only the originally specified step preserves
                            ;; match-type, not its dependencies
                            :match-type (if (= index %)
                                          match-type
                                          :output))
                 (expand-step parse-tree index tree)))
          targets))

(defn- add-step
  "We will be creating a list of steps by starting with an empty list
   and adding steps to it one by one. For each step to be added (new-step),
   we will iterate over the current list and for each step that's
   already there we will check:
     1) if new-step is in the exclusion mode (-A), remove the current step
        if it's the same as the new-step, otherwise:
     2a) if the current step is a direct or indirect dependant of new-step,
         new-step has to be inserted in front of it.
     2b) if the current step is the same as the new-step,
         set the forced-build mode to be an OR of the two.

   This algorithm is N^2, and there are some optimizations that could be
   done (like keeping a hash-map etc), but since the number of steps is
   expected to be small, we're not concerned with optimization right now."
  [parse-tree current-steps new-step]
  (let [step-count (count current-steps)
        {:keys [index build match-type]} new-step
        exclusion (= build :exclude)
        ;; below is also potentially slow as it's done
        ;; for every new step to be added, it can be optimized
        ;; but should do for now
        dependencies (all-dependencies parse-tree index)]
    (loop [i 0]
      (if (= i step-count)     ;; reached the end?
        (if exclusion
          current-steps
          (conj current-steps new-step))
        (let [{old-index :index old-build :build old-match-type :match-type}
                (current-steps i)]
          ;; is it the same step? then adjust the forced-build
          (if (= index old-index)
            (if exclusion
              (delete current-steps i)
              (assoc current-steps i
                     {:index index
                      :build (if (or (= build :forced)
                                     (= old-build :forced))
                               :forced)
                      ;; same logic for tag and methods as for build - if it is
                      ;; specified directly, the step will be built
                      ;; (method takes precedence here)
                      :match-type (some (set [old-match-type match-type])
                                        [:method :tag :output])}))
            ;; is it a dependency? then insert in front of it
            (if (and (not exclusion) (dependencies old-index))
              (insert current-steps i new-step)
              (recur (inc i)))))))))

(defn- check-output-conflicts
  "Checks that all selected steps have unique outputs.
   Returns the steps given or throws an exception."
  [parse-tree steps]
  (reduce #(if (%1 %2)
             (throw+ {:msg (str "duplicated output: " %2)})
             (conj %1 %2))
          #{}
          (map normalized-path
               (mapcat #(get-in parse-tree [:steps (% :index) :outputs]) steps)))
  steps)

(defn select-steps
  "Given a parse tree and an array of target selection expressions,
   returns an ordered list of step indexes to be run."
  [parse-tree target-names]
  (let [steps (expand-targets parse-tree
                (match-target-steps parse-tree
                  (process-qualifiers target-names)))]
    (check-output-conflicts parse-tree
                            (reduce #(add-step parse-tree %1 %2) [] steps))))
