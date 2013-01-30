(ns drake.utils
  (:require [jlk.time.core :as time]
            [clojure.string :as str]
            [fs.core :as fs])
  (:use ordered.set))

;; TODO(artem)
;; The several functions below in between + lines are not Drake-specific and
;; have to be considered to be put in a common library

;; ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
(defn clip
  "Returns string s without its first character."
  [s]
  (.substring s 1))

(defn delete [vc pos]
  "Removes an element from vector by index in the most effecient way.
   Doesn't do anything if pos == -1."
  (if (= pos -1)
    vc
    (vec (concat
          (subvec vc 0 pos)
          (subvec vc (inc pos))))))

(defn insert [vc pos value]
  "Inserts an element into the vector into the specified position
   in the most effecient way."
  (into (conj (subvec vc 0 pos) value)
        (subvec vc pos (count vc))))

(defn inc-if [cond value]
  (if cond (inc value) value))

(defn concat-distinct
  "Concatinates given sequences, removing all duplicates but
   preserving the order. Ignores nil values.
   Example: (concat-distinct [2 2 4] [4 4 5 2] [3 3] [4]) -> '(2 4 5 3)"
  [& seqs]
  (into (ordered-set) (flatten (apply concat seqs))))

(defn reverse-multimap
  "Given a map to vectors, reverse keys and values, i.e.:
      (reverse-multimap [[:key1 [1 2 3]] [:key2 [3 4]] [:key3 [1 2]]]) ->
      {1 [:key1 key3] 2 [:key1 key3] 3 [:key1 :key2] 4 [:key2]}
   Either a map or a sequence of tuples can be given, if the latter,
   preserves the key order (e.g. :key1 will always be before :key2 in the
   value vectors from the example above)."
  [m]
  (reduce
   (fn [result [key values]]
     (reduce #(assoc %1 %2 (conj (%1 %2 []) key)) result values))
   {} m))

(defn merge-multimaps-distinct
  "Given a list of maps to sequences, merges them into one,
   removing duplicates from values and preserving the value order
   according to the order of the arguments, for example:
     (merge-multimaps-distinct
       {:key1 [1 2 3] :key2 [3]} {:key1 [3 4 1] :key2 [3] :key3 []}) ->
     {:key1 '(1 2 3 4) :key2 '(3) :key3 '()}"
  [& maps]
  (apply merge-with concat-distinct maps))

;; ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

(defn now-filename
  "Returns a filename representing current local time with second resolution."
  []
  (time/convert (time/now) (time/formatter "YYYY-MM-dd_HH.mm.ss_Z")))

(def start-time-filename (now-filename))

(defn relative-path
  [file]
  (.substring (.getAbsolutePath (fs/file file))
              (inc (count (.getAbsolutePath fs/*cwd*)))))
