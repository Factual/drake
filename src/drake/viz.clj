(ns drake.viz
  (:require [drake.utils :as util]
            [flatland.useful.map :as map]))

(defmacro dot [f & args]
  `(@(ns-resolve '~'rhizome.dot '~f) ~@args))

(defmacro viz [f & args]
  `(@(ns-resolve '~'rhizome.viz '~f) ~@args))

(defn step-tree [parse-tree steps-to-run]
  (let [run? (into {} (map (juxt :index identity) steps-to-run))
        depends (fn [graph k vs]
                  (update-in graph [k] (fnil into []) vs))
        steps (:steps parse-tree)
        {:keys [graph built forced]}
        ,,(reduce (fn [acc {:keys [built forced input outputs]}]
                    (-> acc
                        (update-in [:graph] depends input outputs)
                        (update-in [:built] into (when built outputs))
                        (update-in [:forced] into (when forced outputs))))
                  {:graph {}, :built #{}, :forced #{}}
                  (for [[i {:keys [inputs outputs]}] (map-indexed vector steps)
                        :let [built (run? i),
                              forced (and built
                                          (= "forced" (:cause (run? i))))]
                        input inputs]
                    (map/keyed [built forced input outputs])))
        target-name (util/strip-base parse-tree)]
    (dot graph->dot (distinct (apply concat (keys graph)
                                     (vals graph)))
         graph
         :node->descriptor (fn [target]
                             (merge {:label (target-name target)
                                     :fillcolor "palegreen"}
                                    (when (built target)
                                      {:style "filled"})
                                    (when (forced target)
                                      {:penwidth 3}))))))