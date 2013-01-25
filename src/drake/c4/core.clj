(ns drake.c4.core
  (:require [clojure.string :as str]
            [fs.core :as fs]
            [clojure-csv.core :as csv]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [ordered.set :as ords]
            [ordered.map :as ordm]
            [clojure.java.shell :as sh])
  (:use [clojure.tools.logging :only [warn info debug error]]))


(defn partial-file
  "Returns the file to use as the partial file to build, until it's
   ready to be moved to the final input file, base-file."
  [base-file]
  (fs/file (str base-file "~partial")))

(defn tilde-file [in-file out-file word]
  (let [dir  (fs/parent in-file)
        name (str (fs/base-name out-file) "__" (fs/base-name in-file) "~" word)]
    (fs/file dir name)))

(defn marker-file
  "Returns the file to use as the marker file when reading from in-file and
   writing to out-file. The marker file will hold the index of the last
   successfully processed line from in-file. This will allow the process to
   pick up from that line in the case of a resume."
  [in-file out-file]
  (tilde-file in-file out-file "mark"))

(defn skip-log
  "Returns the file to use as the log of rows skipped due to runtime errors."
  [in-file out-file]
  (tilde-file in-file out-file "skipped"))

(defn safe-parse-int
  "Safe parse int that ignores non-numeric characters. Makes it easy to get
   a numeric value from a 'wc' return or a file slurp."
  [s]
  (Integer/parseInt (re-find #"[0-9]+" s)))

(defn count-lines
  "Returns the number of lines in file, as an int."
  [file]
  (safe-parse-int (:out (sh/sh "wc" "-l" (str file)))))

(defn make-row-from-hashmap
  "Returns line adorned with row metadata. Assumes line is already a
   hash-map representing row data.

   The returned hash-map will be adorned with metadata about the line:
     :lineno  the numeric index of the line.

   The metadata associated with the returned row is important. Throughout
   this library an assumption is made that all rows have such metadata."
  [m lineno]
  (with-meta
    m
    {:lineno lineno}))

(defn row-seq-from-hashmaps
  "Returns a lazy seq of rows, using each line in lines as the underlying data for
   each row.

   Each row will be adorned with metadata, such as line number.

   lines is expected to be a seq of hash-maps"
  [lines]
  (keep-indexed
   #(make-row-from-hashmap %2 %1)
   lines))

(defn row-seq-from-linevs
  "Returns a lazy seq of rows, using each line in lines as the underlying data for
   each row.

   Each row will be adorned with metadata, such as line number.

   lines is expected to be a sequence of vectors of row data."
  [lines headers]
  (keep-indexed
   (fn [lineno linev]
     (let [m (apply ordm/ordered-map (interleave headers linev))]
       (make-row-from-hashmap m lineno)))
     lines))

(defn lazy-open
  "Provides a lazy seq of all lines in file.

   parser-fn must take an open reader and return a lazy seq of rows that are parsed
   from the lines of reader.

   The underlying file resource will be closed as soon as the returned lazy seq is
   fully consumed. The returned lazy seq should be fully consumed; otherwise the
   underlying file resource will not be closed.

   We want to use this approach so that c4 user code can conveniently
   treat all the rows in a file as a lazy seq and not need worry about
   resource management. This allows c4 user code to naturally take
   advantage of all the seq functions provided out of the box by
   Clojure.

   The basic lazy-open pattern is shamelessly stolen from Andrew Cooke via:
   http://stackoverflow.com/questions/4118123/read-a-very-large-text-file-into-a-list-in-clojure

   As Andrew explains: this approach has the advantage that you can
   process the stream of data 'elsewhere' without keeping everything
   in memory, but it also has an important disadvantage - the file is
   not closed until the end of the stream is read.  If you are not
   careful you may open many files in parallel, or even forget to
   close them (by not reading the stream completely)."
[file parser-fn]
  (defn helper [rows rdr]
    (lazy-seq
      (if-not (empty? rows)
        (cons (first rows) (helper (next rows) rdr))
        (.close rdr))))
  (let [rdr (clojure.java.io/reader file)
        rows (parser-fn rdr)]
    (helper rows rdr)))

(defn row-seq-csv
  "Returns a lazy seq of rows from CSV file.

   The underlying file resource will be closed as soon as the returned lazy seq is
   fully consumed. The returned lazy seq should be fully consumed; otherwise the
   underlying file resource will not be closed."
  [file headers]
  (row-seq-from-linevs
   (lazy-open
    file
    #(csv/parse-csv %))
   headers))

(defn row-seq-tsv
  "Returns a lazy seq of rows from TSV file.

   headerv is used for naming the keys of each row.

   The underlying file resource will be closed as soon as the returned lazy seq is
   fully consumed. The returned lazy seq should be fully consumed; otherwise the
   underlying file resource will not be closed."
  [file headerv]
  (row-seq-from-linevs
   (lazy-open
    file
    #(csv/parse-csv % :delimiter \tab))
   headerv))

(defn row-seq-json
  "Returns a lazy seq of rows from JSON file.

   The lazy seq should be fully consumed; otherwise the underlying file resource
   will not be closed."
  [file]
  (row-seq-from-hashmaps
   (lazy-open
    file
    (fn [reader] (json/parsed-seq reader)))))

(defn nils-to-blanks
  [row]
  (zipmap (keys row)
          (map
           #(if (nil? %) "" %)
           (vals row))))

(defn join-sv-with
  "Takes a row and a row joiner function, and returns a properly joined String, using headerv for
   column inclusion and order."
  [row headers join-fn]
  (let [row (nils-to-blanks row)
        linev (reduce
               (fn [linv col-name]
                 (conj linv (get row col-name "")))
               []
               headers)]
    (join-fn linev)))

(defn join-csv
  "Returns the CSV formatted representation of row, using headers for column inclusion and order."
  [row headers]
  (join-sv-with row headers #(csv/write-csv [%])))

(defn join-tsv
  "Returns the TSV formatted representation of row, using headers for column inclusion and order."
  [row headers]
  (join-sv-with row headers #(csv/write-csv [%] :delimiter \tab)))

;;TODO(aaron): row-seq-* could possibly be refactored/collapsed?
;;TODO(aaron): need to do anything special with non-String values, e.g. from JSON?
;;TODO(aaron): will lineno and line work properly with big ugly files, like
;;             John's sample CSV, that vi treats as one big line?
;;TODO(aaron): from Artem: "Should probably have header-func here, [] in case of JSON, "read the first line" in other cases, etc."
(def
  ^{:doc "Defines the file formats supported by c4:
            :row-seq-fn   A fn that takes the file and returns a lazy seq of parsed rows.
                          Each row will be a proper c4 row, i.e. have metadata, etc.
            :joiner       A fn that takes a row and headers and returns the properly formatted line
                          representation of that row. some joiners may handle a nil headers, but others
                          may not. A non-nil headers means, 'use exactly these columns'.
            :header?      A boolean, true iff the file type requires a header."}
  FILE-TYPES
  {:TSV  {:row-seq-fn  (fn [file headers] (row-seq-tsv file headers))
          :joiner      join-tsv
          :header?     true}
   :CSV  {:row-seq-fn    (fn [file headers] (row-seq-csv file headers))
          :joiner        join-csv
          :header?       true}
   :JSON {:row-seq-fn  (fn [file headers] (row-seq-json file))
          :joiner      (fn [row headers]
                         (str (json/generate-string
                               (if headers
                                 (select-keys row headers)
                                 row)) "\n"))
          :header?     false}})

(defn chop-branch-name
  "Given a file extension, removes the Drake branch name if there is one.
      .txt => .txt
      .txt#MYBRANCH => .txt"
  [ext]
  (if (.contains ext "#")
    (apply str (butlast (str/split ext #"#")))
    ext))

(defn actual-ext
  "Determines the effective extension of file. Considerations:
   * the file may be a partial file, e.g.: 'my-out.txt~partial'
   * the file may have a branch name appended, e.g.: 'my-out.txt#MYBRANCH'"
  [file]
  (let [ext   (fs/extension file)
        ext   (chop-branch-name ext)
        ext   (if (.endsWith ext "~partial")
                (subs ext 0 (- (count ext) 8))
                ext)]
    (.toLowerCase ext)))

;;TODO(aaron): somewhere, validate the we recognize the extension
(defn get-file-type [file]
  (FILE-TYPES
   (condp = (actual-ext file)
     ".tab"  :TSV
     ".tsv"  :TSV
     ".csv"  :CSV
     ".json" :JSON)))

(defn expect-header? [file]
  (:header? (get-file-type file)))

(defn get-joiner
  "Returns the appropriate row joiner for file, based on the file's
   apparent file type."
  [file]
  (:joiner (get-file-type file)))

(defn header-file-for [file]
  (fs/file
   (str file ".header")))

(defn write-headers-for!
  "Creates the .header file for file.
   headers is assumed to be an ordered seq of column names."
  [file headers]
  (spit (header-file-for file)
        (str/join "\n" headers)))

;;TODO(aaron): from Artem: "Gotta check columns for validity, at least rudimentary checking such as non-empty and
;;                         do not contain commas, tabs and quotes. Fail out on an invalid column name"
(defn read-headers-for
  "Returns an ordered set of column names from the .header file for file.
   Assumes there's a [file].header file.
   Assumes header file is formatted like:

   colName1\n
   colName2\n
   colName3\n
   ..."
  [file]
  (let [header-file (header-file-for file)]
    (apply ords/ordered-set (->
                             (slurp header-file)
                             str/trim
                             (str/split #"\n")))))

(defn conj-headers
  "Returns the unique set of column names, given headers (a set of already-known
   column names), and row (a row to sample for possibly as-yet-unseen column names)."
  [headers row]
  (apply conj headers (keys row)))

(defn- write-rows-helper
  "Writes formatted rows to the opened writer, using headers and joiner to format
   appropriately. Returns the set of unique column names seen."
  [rows headers joiner writer mark-file total-line-cnt]
  (if (empty? rows)
    headers
    (do
      (let [line (joiner (first rows) headers)
            lineno (:lineno (meta (first rows)))]
        ;; TODO(aaron): Showing the user this message, and spit'ing the marker, on every line is
        ;; quite expensive and noisy for large files. If we're going to deal with very large
        ;; files, these things may need to be more clever. E.g., only messaging the user
        ;; every N lines, and only saving the marker every N lines or N seconds.
        (.write writer line)
        (spit mark-file lineno)
        (let [headers (if (:frozen (meta headers))
                        headers
                        (conj-headers headers (first rows)))]
          (recur (next rows) headers joiner writer mark-file total-line-cnt))))))

(defn write-rows
  "Writes rows to out-file.

   If headers is nil, all columns will be included in the output. If headers is not nil, only
   the columns included in headers will be included in the output.

   Returns the column names that were included in the output, as an ordered set, or nil if
   there are no non-nil rows.

   Filters out nil rows; that is, nil rows are not written to the output file.
   If there are no non-nil rows, this is essentially a no-op; no output file is
   created, and returns nil."
  [rows in-file out-file headers mark-file total-line-cnt]
  (let [rows (filter identity rows)
        out-file (fs/file out-file)]
    (when-not (empty? rows)
      (let [out-file-type  (get-file-type out-file)
            joiner         (get-joiner out-file)
            ;; if headers provided, stick to those.
            ;; if no headers provided, start with the columns from the first row and expect
            ;; the helper to discover columns as it goes
            headers         (if headers
                              (with-meta headers {:frozen true})
                              (conj-headers (ords/ordered-set) (first rows)))]
        ;; Setting ':append true' since we might be resuming onto a partial file
        ;; Assumes that any previous output file was properly removed already, if necessary.
        (with-open [writer (io/writer out-file :append true)]
          (write-rows-helper rows headers joiner writer mark-file total-line-cnt))))))

(defn row-seq
  "Returns a lazy seq of rows from file.

   Each row in the returned seq will be adorned with a metadata hash-map,
   including:
     :lineno  the numeric index of the line.

   TODO(aaron): document file resource closing contract"
  [file]
  (let [file-type  (get-file-type file)
        row-seq-fn (:row-seq-fn file-type)
        headers (when (expect-header? file)
                  (read-headers-for file))]
    (row-seq-fn file headers)))

(defn get-last-line-ndx
  "Returns the index of the last line that was successfully processed, based on
   the contents of makr-file.

   It may be more ideal, from a performance perspective, to track position in
   the file, rather than line number. However, we're using line number here
   as a quick-and-dirty solution."
  [mark-file]
  (when (fs/exists? mark-file)
    (safe-parse-int (slurp mark-file))))

(defn row-seq-resume
  "Same as row-seq, but will automatically resume from the next un-processed line in
   in-file if a last line marker is found."
  ([in-file out-file mark-file]
     (if-let [last-line-ndx (get-last-line-ndx mark-file)]
       (do
         ;; Tell user which line will be picked up next; user sees line indexes as 1-based
         (info "Resuming at line " (str in-file ":" (+ 2 last-line-ndx)))
         ;; Skip lines that were already processed
         (drop (inc last-line-ndx) (row-seq in-file)))
       (row-seq in-file)))
  ([in-file out-file]
    (row-seq-resume in-file out-file (marker-file in-file out-file)))

  )

(defn write-file!
  "Overwrites out-file with the data from rows, formatted appropriately based on file type.

   Writes to a .partial file until all processing is done, then moves the .partial file to
   out-file.

   Supports resumability by keeping a .mark file to keep track of the last line processed
   from in-file. Should their be a failure that causes processing to crash out, the .mark
   file will hold the zero-based index of the last line from in-file that was successfully
   processed into out-file. When write-file! is called again for the same in-file/out-file
   combination, it will notice there is a .mark file, and resume with the next unprocessed
   line from in-file.

   If headers is not nil, it will be used for:
     1) which columns are written when writing rows
     2) column ordering when order is relevant
     3) the .header file created to go with out-file, when required (e.g., for a CSV file)

  If headers is nil and out-file is of a format requiring a header, the headers will be
  built by collecting all unique column names seen while writing rows."
  ([in-file rows out-file headers]
     (fs/delete out-file)
     (let [mark-file        (marker-file in-file out-file)
           partial-out-file (partial-file out-file)
           ;; TODO(aaron): Counting lines this way can be wasteful.
           total-line-cnt   (count-lines in-file)
           headers-written  (write-rows rows in-file partial-out-file headers mark-file total-line-cnt)]
       (when (expect-header? out-file)
         (write-headers-for! out-file headers-written))
       (fs/rename partial-out-file out-file)
       (fs/delete mark-file)))
  ([in-file rows out-file]
     (write-file! in-file rows out-file nil)))

(defn skip-error [in-file out-file row lineno e]
  (warn (format "Skipping line %s due to error:" lineno (.getMessage e)))
  (spit (skip-log in-file out-file) (str lineno ": " row "\n") :append true))

(defn write-rows-robustly
  "Does the heavy lifting for xform-lines-robustly"
  [in-file f out-file opts]
  (let [out-file (fs/file out-file)
        out-file-type  (get-file-type out-file)
        joiner         (get-joiner out-file)
        headers        (:headers opts)
        mark-file      (:mark-file opts)
        total-line-cnt (:total-line-cnt opts)
        sensed-headers (when-not headers (atom (ords/ordered-set)))]
    ;; Setting ':append true' since we might be resuming onto a partial file
    ;; Assumes that any previous output file was properly removed already, if necessary.
    (with-open [writer (io/writer out-file :append true)]
      (doseq [in-row (row-seq-resume in-file out-file mark-file)]
        (let [lineno (:lineno (meta in-row))]
          ;; TODO(aaron): Showing the user this message, and spit'ing the marker, on every line is
          ;; quite expensive and noisy for large files. If we're going to deal with very large
          ;; files, these things may need to be more clever. E.g., only messaging the user
          ;; every N lines, and only saving the marker every N lines or N seconds.
          (info "c4: Doing line " (str (inc lineno) "/" total-line-cnt))
          (try
            (when-let [out-row (f in-row)]
              (when sensed-headers
                (swap! sensed-headers #(conj-headers % out-row)))
              (.write writer (joiner out-row (or headers @sensed-headers)))
              (.flush writer))
            (catch Throwable e
              (if (:skip-errors opts)
                (skip-error in-file out-file in-row lineno e)
                (throw e))))
          (spit mark-file lineno))))
    (or headers @sensed-headers)))

(defn xform-lines-robustly
  "Reads rows from in-file, applies f to each, and writes the results to out-file.

   If calling f on an input row returns nil, no output row will be created for that
   input row. That is, nil output is skipped.

   Supports resume by tracking ~partial and ~mark files.

   Uses opts:
     :headers     If headers is nil, all columns will be included in the output. If headers is not nil, only
                  the columns included in headers will be included in the output.
     :skip-errors If set to true, runtime errors encountered when processing an input row will be
                  skipped over and trackedin a ~skipped file.

   Returns the column names that were included in the output, as an ordered set, or nil if
   there are no non-nil rows."
  ([in-file f out-file opts]
     (fs/delete out-file)
     (let [mark-file        (marker-file in-file out-file)
           partial-out-file (partial-file out-file)
           opts (merge {:mark-file mark-file
                        ;; TODO(aaron): Counting lines this way can be wasteful.
                        :total-line-cnt (count-lines in-file)}
                       opts)
           headers-written  (write-rows-robustly in-file f partial-out-file opts)]
       (when (expect-header? out-file)
         (write-headers-for! out-file headers-written))
       (fs/rename partial-out-file out-file)
       (fs/delete mark-file))))



;;
;; For testing
;;

#_(defn pprint-rows
  "Pretty prints the metadata and row value for the 2nd data row in file."
  [file amt]
  (let [rows  (row-seq file)]
    (doseq [row (take amt rows)]
    (clojure.pprint/pprint row))))

(defn count-rows
  "Consumes entire sequence, keeping a count.
   Returns final count. A good test to see that
   we don't get OOME on massive files."
  [file]
  (let [c (atom 0)]
    (doseq [r (row-seq file)]
      (swap! c inc))
    @c))
