; in source dir run like this
; $ lein repl
; (load-file "tree.clj")
; (drake.tree/main "--workflow" "workflow.d" "--jobs" "2")

(ns drake.tree
  (:use [loom.graph]
        [loom.io]
        [loom.label]
        [loom.attr]
        clojopts.core
        )

)
(import
  '(java.util.concurrent Semaphore)
)

; get labels for each steps (uses :dir for a label)
(defn- get-labels [parse-tree]
  (->>
    parse-tree
    (:steps)
    (map-indexed (fn [idx step] 
      [idx (clojure.string/join ": " (list (step :dir) idx))]))
    (flatten)
  )
)

; get :cmds for each steps
(defn- get-commands [parse-tree]
  (->>
    parse-tree
    (:steps)
    (map-indexed (fn [idx step] 
      [idx (step :cmds)]))
    (reduce (fn [map-out [idx cmd]] (assoc map-out idx cmd)) {} )
  )
)

; store :cmds in each graph nodes
(defn- apply-commands-to-graph [parse-tree g]
  (let [cmds (get-commands parse-tree)]
    (reduce 
      (fn [graph-out [cmd-key cmd-value]]  (add-attr graph-out cmd-key :cmds cmd-value) ) g cmds)
  )
)

; formats args in a format suitable for loom/graph
(defn- parse-tree-to-graph-args [parse-tree]
  (->>
    parse-tree
    (:steps)
    (map-indexed (fn [idx step] 
      [idx (->> step
                (:children)
                (apply vector)
          )]))
    (into {})
  )
)  

; triggers future callbacks
(defn- trigger-futures [coll]
  (do
    (println "in trigger-futures")
    (doseq [future coll]
      (do
        (future)
      ) coll)
  )
)

; waits for all the promises to be fullfilled
(defn- await-promises [promises] 
  (doseq [[n promise] promises] (deref promise))
)

; converts a drake parse-tree to a loom graph structure
(defn parse-tree-to-graph [parse-tree]
  ;create graph
  (let [g (->>
    (parse-tree-to-graph-args parse-tree)
    (digraph)
   )]
   (->>
     ;apply labels to graph
     (apply  
      add-labeled-nodes 
      (flatten (list g (get-labels parse-tree)))
     )
     
     ;apply cmds as an attribute
     (apply-commands-to-graph parse-tree)
     
     ;transpose graph
     (transpose)
   )
  )
)



(defn main-options-parsed [options]
  (def jobs-semaphore (new Semaphore (options :jobs)))
  (println "number of jobs -j: " jobs-semaphore)
  
  ; parse the file into a graph
  (def g (parse-tree-to-graph (drake.parser/parse-file (options :workflow) {})))
  
  ; each node in the graph promises a value at the end of the command
  ; structure is a map of {node => promise}
  (def promises
    (zipmap (nodes g) (map (fn [node] (promise)) (nodes g)))
  )

  ; spawn a future func for each promise
  ; structure is a map of {node => func(future)}
  (def futures 
    (->>
      (map
        (fn [[n promise]]
         (fn [] 
            (future (do
              ; wait for parent promises to be delivered
              (doseq [i (incoming g n)] (deref (promises i)))
              
              ; acquire a semaphore from the --jobs
              (.acquire jobs-semaphore)
              
              
              ; fake a command running
              ; in reality this should fork the real command but not sure how to do it with drake functions
              ; TODO implement run commands in drake
              (println "runnning" n "depending on"  (incoming g n))
              (Thread/sleep 5000)

              ; releases a semaphore from the --jobs
              (.release jobs-semaphore)
              
              ; delivers a promise of 0
              (deliver promise 0)

              
            ))
         )
        ) 
        promises
      )
      (apply list)
    )
  )

  ; triggers all futures func
  (trigger-futures futures)

  ; awaits all promises before exiting the process
  (await-promises promises)

  ; remove an attribute on all nodes of a graph
  (defn- remove-attribute [attr-key g]
    (reduce (fn [graph-out n] (remove-attr graph-out n attr-key)) g (nodes g))
  )

  ; view graph in graphviz
  ; for some reason, having the :cmds attribute in the graph messes up the graph viz rendering so we remove it
  (defn- view-graph [g]
    (->> g
      (remove-attribute :cmds)
      (view)
    )
  )

  ; view graph in graphviz
  (view-graph g) 
)


(defn main
  "Runs Drake's (tree) CLI.
   A subset of what drake can do to demonstrate asynchronous execution
   
   Examples:
     (-main \"--workflow\" \"workflow.d\")
     (-main \"--workflow\" \"workflow.d\"  \"--jobs\" \"2\")
     (-main \"--workflow\" \"workflow.d\"  \"--jobs\" \"16\")

   TODO: hook into drake's core code"
  [& args]
  (let [[opts targets] (drake.core/split-command-line (into [] args))
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
                   (with-arg jobs
                       "Specifies the number of jobs (commands) to run simultaneously. Defaults to 1"
                       :type :int
                       :user-name "jobs-num")
                  )
                  (catch IllegalArgumentException e
                    (println e 
                      (str "\nUnrecognized option: "
                           "did you mean target exclusion?\nto build "
                           "everything except 'target'"
                           " run:\n  drake ... -target"))
                    ; (System/exit -1)
                    ))
           ;; if a flag is specified, clojopts adds the corresponding key
           ;; to the option map with nil value. here we convert them to true.
           ;; also, the defaults are specified here.
           options (into {:workflow "./Drakefile"
                          :logfile "drake.log"
                          :jobs 1}
                         (for [[k v] options] [k (if (nil? v) true v)]))]

  (flush)    ;; we need to do it for help to always print out
  (main-options-parsed options)
  
  )  
)
