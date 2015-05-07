(ns drake.utils
  (:require [jlk.time.core :as time]
            [clojure.string :as str]
            [fs.core :as fs])
  (:require [ordered.set :as ordered]))

;; TODO(artem)
;; The several functions below in between + lines are not Drake-specific and
;; have to be considered to be put in a common library

;; ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
(defn clip
  "Returns string s without its first character."
  [s]
  (subs s 1))

(defn ensure-final-newline
  [^String s]
  (if (.endsWith s "\n")
    s
    (str s "\n")))

(defn concat-distinct
  "Concatinates given sequences, removing all duplicates but
   preserving the order. Ignores nil values.
   Example: (concat-distinct [2 2 4] [4 4 5 2] [3 3] [4]) -> '(2 4 5 3)"
  [& seqs]
  (into (ordered/ordered-set) (flatten (apply concat seqs))))

(defn reverse-multimap
  "Given a map to vectors, reverse keys and values, i.e.:
      (reverse-multimap [[:key1 [1 2 3]] [:key2 [3 4]] [:key3 [1 2]]]) ->
      {1 [:key1 key3] 2 [:key1 key3] 3 [:key1 :key2] 4 [:key2]}
   Either a map or a sequence of tuples can be given, if the latter,
   preserves the key order (e.g. :key1 will always be before :key2 in the
   value vectors from the example above)."
  [m]
  (apply merge-with into {}
         (for [[key values] m
               value values]
           {value [key]})))

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

(defn now
  "Returns current time in milliseconds"
  []
  (.getTime (java.util.Date.)))

(defmacro with-time-elapsed
  "Runs the given code, calculating how much time it took (in ms),
   and calls 'func' with this value."
  [func & body]
  `(let [start# (now)
         value# ~@body]
     (~func (- (now) start#))
     value#))

(defmacro in-ms
  "To be used with with-time-elapsed. Returns a function which constructs
   message of the form 'what' finished in X seconds, and then logs it
   using 'func' (which can be 'debug', 'info' etc.). This needs to be a macro
   because debug/info/warn are also macros."
  [func what]
  `(fn [elapsed#] (~func (format "%s finished in %.2fs"
                                 ~what (/ elapsed# 1000.0)))))

(defn relative-path
  [file]
  (subs (.getAbsolutePath (fs/file file))
        (inc (count (.getAbsolutePath (fs/file fs/*cwd*))))))
