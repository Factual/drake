(ns drake.protocol
  (:require [clojure.string :as str]
            [fs.core :as fs]
            digest)
  (:use [slingshot.slingshot :only [throw+]]
        [clojure.tools.logging :only [debug]]
        [clojure.java.io :only [writer]]
        drake.shell
        drake.utils))

(defprotocol Protocol
  (cmds-required? [this])
  (run [this step]))

(deftype ProtocolUnsupported []
  Protocol
  (run [_ _]
    (throw+ {:msg "unsupported protocol"})))

(def ^:private ^:dynamic *protocols*
  {"clojure" (ProtocolUnsupported.)})

(defn register-protocols! [& args]
  (def ^:private ^:dynamic *protocols* (apply assoc *protocols* args)))

(defn get-protocol-name [step]
  ((:opts step) :protocol "shell"))

(defn get-protocol [step]
  (let [name (get-protocol-name step)
        protocol (*protocols* name)]
    (if-not protocol
      (throw+ {:msg (str "unknown protocol: " name)})
      protocol)))

(defn create-cmd-file
  "A commonly used function for protocols such as 'shell', 'ruby' or 'python'.

   Given the step, puts step's commands into a file under .drake/ directory
   with the filename consisting of the protocol name followed by the MD5 of
   the commands. Only creates the file if it doesn't already exists,
   serving as a simple cache.

   Returns the filename."
  [{:keys [cmds] :as step}]
  (let [filename (format ".drake/%s-%s"
                         (get-protocol-name step)
                         (digest/md5 (apply str cmds)))]
    ;; we need to use fs.core/file here, since fs.core/with-cwd only changes the
    ;; working directory for fs.core namespace
    (if-not (fs/exists? filename)
      (spit (fs/file filename) (str (str/join "\n" cmds) "\n")))
    filename))

(defn log-file
  "Given a step, returns the log file for stdout or stderr (prefix) for the
   child process."
  [{:keys [dir]} prefix]
  (if-not (fs/exists? dir)
    (fs/mkdirs dir))
  ;; we need to use fs.core/file here, since fs.core/with-cwd only changes the
  ;; working directory for fs.core namespace
  (fs/file (str dir "/" prefix "-" start-time-filename)))

(defn run-interpreter
  "Common implementation for most of interpreter protocols, that is, when
   the step commands are put in a script file and an interpreter is called on it,
   for example, python, ruby or shell (bash).

   Caches the script file using MD5, and intercepts and stores child process'
   stdout and stderr in the standard location."
  [{:keys [vars opts] :as step} interpreter args]
  (let [script-filename (create-cmd-file step)
        stdout (log-file step "stdout")
        stderr (log-file step "stderr")]
    (apply shell (concat [interpreter]
                         args
                         [script-filename
                          :env vars
                          :die true
                          :no-stdin (:no-stdin opts)
                          :out [System/out (writer stdout)]
                          :err [System/err (writer stderr)]]))
    (debug "run-interpreter: finished running" (relative-path script-filename))
    (debug "run-interpreter: stdout saved to" (relative-path stdout))
    (debug "run-interpreter: stderr saved to" (relative-path stderr))))
