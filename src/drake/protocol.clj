(ns drake.protocol
  (:require [clojure.string :as str]
            [fs.core :as fs]
            digest
            [drake.plugins :as plugins])
  (:use [slingshot.slingshot :only [throw+]]
        [clojure.tools.logging :only [debug]]
        [clojure.java.io :only [writer]]
        drake.shell
        drake.utils
        drake.options))

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

(defn from-plugins
  "Returns the reified protocol loaded from installed plugins.
   Returns nil if no protocol with protocol name is found in plugins.

   The returned protocol's cmds-required? will be set based on the
   :no-cmds-required metadata entry as set in the function definition in
   the corresponding plugin. If there's no :no-cmds-required set, then
   cmds-required? defaults to true."
  [protocol-name]
  (when-let [f (plugins/get-plugin-fn protocol-name)]
    (reify Protocol
      (cmds-required? [_] (not (:no-cmds-required (meta f))))
      (run [_ step] (f step)))))

(defn get-protocol
  "Returns the protocol indicated by step. Looks first at built-in protocols,
   then looks in loaded plugins. Throws an exception if not found."
  [step]
  (let [name (get-protocol-name step)]
    (or (*protocols* name)
        (from-plugins name)
        (throw+ {:msg (str "unknown protocol: " name)}))))

(defn create-cmd-file
  "A commonly used function for protocols such as 'shell', 'ruby' or 'python'.

   Given the step, puts step's commands into a file under --tmpdir directory
   with the filename consisting of the protocol name followed by the MD5 of
   the commands. Only creates the file if it doesn't already exists,
   serving as a simple cache.

   Returns the filename."
  [{:keys [cmds] :as step}]
  (let [filename (format "%s/%s-%s"
                         (*options* :tmpdir)
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
