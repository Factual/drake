(ns clj-frontend.demo
  (:use drake.clj-frontend))

;; minimal workflow example

;; Define a workflow called minimal-workflow.
(def minimal-workflow
  (->
   (new-workflow)                       ;Create a new workflow
   (cmd-step                            ;Add a command step with the
                                        ;following arguments
    ["out"]                             ;Array of outputs
    []                                  ;Array of inputs
    ["echo \"We are writing to a file here\" > $OUTPUT"] ;Array of commands
    )))

;; To see what will happen without running the workflow uncomment the
;; following line or run it at in the repl:

;; (run-workflow minimal-workflow :preview true)

;; Use the following line to actually run the workflow

;; (run-workflow minimal-workflow)


;; A more advanced example

(def advanced-workflow
  (->
   (new-workflow)
   (cmd-step
    ["out1"
     "out2"]
    []
    ["echo \"This is the first output.\" > $OUTPUT0"
     "echo \"This is the second output.\" > $OUTPUT1"] ;multiple commands
    {:timecheck false})                   ; the last argument to step and method is an option map
   (method
    "test_method"
    ["echo \"Here we are using a method.\" > $OUTPUT"])
   (method-step
    ["out_method"]                      ;outputs
    []                                  ;inputs
    "test_method")                      ;method name
   (set-var "test_var" "TEST_VAR_VALUE") ;var name, var value
   (set-var "output_three" "out3")
   (cmd-step
    ["$[output_three]"]                 ;inputs and outputs can have
                                        ;$[XXX] substitution
    ["out1" "%a_tag"]                   ;tags are allowed in inputs
                                        ;and outputs
    ;; $[XXX] substitution is allowed in commands.
    ["echo \"This is the third output.\" > $OUTPUT"
     "echo \"test_var is set to $test_var - $[test_var].\" >> $OUTPUT"
     "echo \"The file $INPUT contains:\" | cat - $INPUT >> $[OUTPUT]"])
   (cmd-step
    ["output"]
    []
    ["echo $MSG > $OUTPUT"]
    {:timecheck false,
     :vars {"MSG" "My Message"}}))) ; you can also set vars in the option map

;; (run-workflow advanced-workflow :preview true)
;; (run-workflow advanced-workflow)

;; Example with reduce

;; Let's say you want to take several raw data sources from the
;; internet and for each source you want to create a directory,
;; download some data into it, and do several processing steps on the
;; data. We will express this as a map called dir->url-map between the
;; directory names we want to create and the raw data sources we want
;; to process.

(def dir->url-map
  "Hash map of:
  Directory Names => URLs"
  {"Dir1" "http://url1"
   "Dir2" "http://url2"
   "Dir3" "http://url3"})

;; Now we need a function that takes an existing workflow and adds new
;; steps to it for each directory => url pair from our data-map.


(defn download-and-process
  "I take an existing workflow and download and process the data at
  url into the directory dir"
  [w-flow [dir url]]                    ;note the argument
                                        ;destructuring
  (-> w-flow
      (base "")                         ;make sure we are in top
                                        ;directory
      (cmd-step
       [dir]
       []
       ["mkdir -p $OUTPUT"])
      (base dir)                        ;move into dir for our
                                        ;subsequent commands
      (cmd-step
       ["raw_data"]
       []
       ["wget -O $OUTPUT "  url]        ;get the data
       {:timecheck false})
      (cmd-step
       ["sorted_data"]
       ["raw_data"]
       ["sort -o $OUTPUT"])            ;sort the data
      ;; more steps can be added here
      ))

;; Finally we can use `reduce` with `download-and-process` to add
;; several workflow steps for each dir => url pair in dir->url-map.

(def reduce-workflow
  (reduce
   download-and-process
   (new-workflow)
   dir->url-map))

;; (run-workflow reduce-workflow :preview true)

;; this is a fake workflow in that the interet data doesn't exist so
;; we can't acutally run it
