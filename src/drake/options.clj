(ns drake.options
  (:require [clojopts.ui :as clojopts]
            [clojopts.core :refer [clojopts with-arg no-arg optional-arg]])
  (:refer-clojure :exclude [file-seq]))

;; This namespace is responsible for the keeping track of
;; options. These were previously housed in the drake.core namespace,
;; which made them inaccessible to other namespaces in the drake
;; project.

(def ^:dynamic *options* {})
(defn set-options [opts]
  (def ^:dynamic *options* opts))

(def PLUGINS-FILE "plugins.edn")
(def DEFAULT-OPTIONS {:workflow "./Drakefile"
                      :logfile "drake.log"
                      :jobs 1
                      :plugins PLUGINS-FILE
                      :tmpdir ".drake"})

(defn parse-command-line-options [args]
  (let [options
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
           (optional-arg graph g
             "Runs Drake in \"graph\" mode. Instead of executing steps, Drake just draws a graph of all the inputs and outputs in the workflow, with color-coding to indicate which will be run. The graph is saved to a file named drake.png in the current directory. Files which will be built are colored green, and those which were forced will be outlined in black as well. You can specify --graph=dot to output a .dot file instead of outputting a png, or --graph=show to display the image on screen.")
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
        targets (:clojopts/more options)
        ;; if a flag is specified, clojopts adds the corresponding key
        ;; to the option map with nil value. here we convert them to true.
        ;; also, the defaults are specified here.
        options (into DEFAULT-OPTIONS
                      (for [[k v] options] [k (if (nil? v) true v)]))]
    [options targets]))
