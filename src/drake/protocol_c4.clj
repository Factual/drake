(ns drake.protocol-c4
  "Implements a c4 protocol for Drake steps. Sexy.
   https://github.com/Factual/c4"
  (:require [c4.core :as c4]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [cemerick.pomegranate :as pom]
            [sosueme.conf :as conf]
            [fs.core :as fs]
            [factql.core :as factql]
            [c4.apis.foursquare :as foursquare]
            [c4.apis.facebook :as facebook]
            [c4.apis.yelp :as yelp]
            [c4.apis.google :as google]
            [sosueme.conf :as conf]
            [fs.core :as fs]
            [retry.core :as retry])
  (:use [clojure.tools.logging :only [debug]]
        [slingshot.slingshot :only [throw+]]
        factql.core
        drake.protocol))


;;
;; Execution integration between Drake and c4
;;

;; Supports wiring up a drake step to c4 w/o the need to explicitly
;; substitute in the input and output files
(def ^:dynamic *in-file*)
(def ^:dynamic *out-file*)
(defn files! [in out]
  (def ^:dynamic *in-file* in)
  (def ^:dynamic *out-file* out))

;; Supports explicitly setting the columns expected out of a c4 step
;;   (columns! "Factual ID" "Foursquare ID")
;; If the new file will have new columns (added by the drake step), the
;; call to new-columns! must be done beforehand (e.g., the first line of
;; the drake c4 step)
(def ^:dynamic *columns* nil)
(defn columns! [& col-names]
  (def ^:dynamic *columns* col-names))

(defn <rows>
  "Returns a lazy seq of rows from *in-file*. The lazy seq should be fully
   consumed, otherwise the file handle to *in-file* will not be closed.
   TODO(aaron): there should be a way to turn off resume"
  []
  (c4/row-seq-resume *in-file* *out-file*))

(defn write
  "Overwrites out-file with the data from rows, formatted appropriately
   based on file type."
  [rows]
  (c4/write-file! *in-file* rows *out-file* *columns*))

;; Handles resolving specified dependencies at runtime, loading them unto
;; the classpath.
;; Example use, in a Drake/c4 step:
;;
;; (load-deps! [factual/sosueme "0.0.13"]
;;             [factual/juiceful "1.1.3"])
;; (require '[sosueme.druid :as druid])
;; (require '[juiceful.utils :as utils])
(def REPOS {"factual"
            {:url "http://maven.corp.factual.com/nexus/content/groups/public"
             :update :always}})
(defmacro load-deps! [& deps]
  (let [coords (vec deps)]
    `(pom/add-dependencies :coordinates '~coords
                           :repositories REPOS)))


;;
;; Wire a Drake step to c4
;;

(defn assert-single-files [{:keys [vars]}]
  ;;TODO(aaron): this should result in a nicer message to user, including
  ;;             step's line number
  (let [input-cnt (vars "INPUTN")
        output-cnt (vars "OUTPUTN")]
    (when (not= input-cnt 1)
      (throw+ {:msg "c4: your c4 steps need exactly 1 input"}))
    (when (not= output-cnt 1)
      (throw+ {:msg "c4: your c4 steps need exactly 1 output"}))))

(defn dot-factual-file [name]
  (-> (fs/home)
      (fs/file ".factual")
      (fs/file name)))

(defn dot-factual-file-exists? [name]
  (fs/exists? (dot-factual-file name)))

(defn init-if! [service auth-fn]
  (let [auth-file (str service "-auth.yaml")]
    (if (dot-factual-file-exists? auth-file)
      (do
        (auth-fn (conf/dot-factual auth-file))
        (debug "c4: Found auth for" service))
      (debug "c4: Did not find" auth-file "; not initiating" service))))

(defn do-auths! []
  (init-if! "factual"     #(factql/init! %))
  (init-if! "yelp"        #(yelp/init! %))
  (init-if! "foursquare"  #(foursquare/init! %))
  (init-if! "facebook"    #(facebook/init! %))
  (init-if! "google"      #(google/init! %)))

(defn add-ns [clj-str]
  (str "(ns drake.protocol-c4)\n" clj-str))

(defn eval-with-ns [clj-str]
  (-> clj-str
      add-ns
      load-string))

(defn wrap-row-fn [clj-str]
  (format "(fn [row] %s)" clj-str))

(defn wrap-rows-def [clj-str]
  (format "(let [rows (<rows>)] %s)" clj-str))

(defn exec-row-xform
  "Uses step's Clojure code as a row xform, applies it to every row in the
   input file and writes the results to the output file.

   Expects the step's Clojure code to hold the body of an anonymous function
   which transforms a single row. This body can access the row hash-map via
   the predefined var 'row'. The body is expected to return the transformed
   row as a hash-map, or nil if the row is to be skipped.

   Copies each row's metadata onto the resulting row.

   Example step Clojure code:
     (assoc row 'FullName' (str (row 'FirstName') (row 'LastName')))"
  [step]
  (let [cmds (:cmds step)
        clj-str (str/join "\n" (:cmds step))
        f (-> clj-str
              wrap-row-fn
              eval-with-ns)
        opts {:headers *columns*
              :skip-errors (= true (get-in step [:opts :skip_errors]))}]
    (c4/xform-lines-robustly *in-file* f *out-file* opts)))

(defn exec-rows
  "Executes clj-str as the definition of a sequence of output rows, and writes
   the resulting rows to the output file.

   Expects clj-str to define the sequence of output rows. clj-str can refer to
   the input rows via 'rows'.

   Example step Clojure code:
     (map
       (fn [row]
         (assoc row 'FullName' (str (row 'FirstName') (row 'LastName'))))
       rows)"
  [step]
  (let [clj-str (str/join "\n" (:cmds step))]
    (-> clj-str
        wrap-rows-def
        eval-with-ns
        write)))

;; TODO(aaron): this is for compatability with original c4 protocol.
;;              Remove once no one is using it anymore.
;;              Artem: ideally we'd have (write file) and (read file)
;;              functions that return etc. and exec-row-xform and exec-rows
;;              would call them
(defn exec-c4 [step]
  (let [clj-str (str/join "\n" (:cmds step))]
    (eval-with-ns clj-str)))

(defn setup!
  "Performs setup tasks common to all c4 protocols"
  [step]
  (let [in-file  (get-in step [:vars "INPUT"])
        out-file (get-in step [:vars "OUTPUT"])]
  (assert-single-files step)
  (files! in-file out-file)
  (do-auths!)))

(defn exec-or-passthru
  "Runs f on step if step has non-empty commands, otherwise does a pass-through
   (i.e., copy) of the input rows.

   The pass-through behaviour is useful when we wish to easily translate from
   one format to another (the formats are indicated by input and output names,
   so no need for any actual commands)."
  [step f]
  (setup! step)
  (if (every? str/blank? (:cmds step))
    (write (<rows>))
    (f step)))

(defn- register-c4-protocol!
  [[protocol-name func]]
  (register-protocols! protocol-name
                       (reify Protocol
                         (cmds-required? [_] false)
                         (run [_ step]
                           (exec-or-passthru step func)))))

(dorun (map register-c4-protocol! [["c4" exec-c4]
                                   ["c4row" exec-row-xform]
                                   ["c4rows" exec-rows]]))

;; DESIGN TODO(aaron):
;; Error handling, skipping, backoff/retry, resume, log errors?
;;   Need to catch/rethrow with information about the line being processed,
;;     including the raw line
;; Consider flexible query syntax for things like limit, offsets,
;;     random-lines, etc.
;; Allow for rapid prototyping of c4 chunks of code. E.g.:
;;   (defn test-step [in-seq-or-file & c4-code)  ;; output is sent to stdout, or
;;     optional out seq for unit tests
;; Crosswalking, Resolving, etc., should be supported by a natural,
;;   fluid syntax that makes
;;   it intuitive to quickly code up a Crosswalk, Resolve, etc., via c4.
;;    Consider John saying,
;;   "Oh, you should Crosswalk that with the Open Table ID before falling
;;    back to a Resolve."
