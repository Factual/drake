(ns drake.event
  (:require [clojure.string :as str]
            [cheshire.core :refer :all]
            [clj-logging-config.log4j :as log4j])
  (:use [clojure.tools.logging :only [info debug trace error]]
        [slingshot.slingshot :only [try+ throw+]]
        sosueme.throwables)) 

(gen-class
  :name drake.event.DrakeEvent
  :prefix DrakeEvent-
  :methods [[getTimestamp [] long]
            [getState [] String]]) 

(gen-class
  :name drake.event.DrakeEventWorkflowBegin
  :extends drake.event.DrakeEvent
  :prefix DrakeEvent-
  :methods [[getSteps [] String]]
  :state state
  :init init) 

(gen-class
  :name drake.event.DrakeEventWorkflowEnd
  :extends drake.event.DrakeEvent
  :prefix DrakeEvent-
  :state state
  :init init) 

(gen-class
  :name drake.event.DrakeEventStepBegin
  :extends drake.event.DrakeEvent
  :prefix DrakeEvent-
  :methods [[getStep [] String]]
  :state state
  :init init) 

(gen-class
  :name drake.event.DrakeEventStepEnd
  :extends drake.event.DrakeEvent
  :prefix DrakeEvent-
  :methods [[getStep [] String]]
  :state state
  :init init) 

(gen-class
  :name drake.event.DrakeEventStepError
  :extends drake.event.DrakeEvent
  :prefix DrakeEvent-
  :methods [[getStep [] String] 
            [getStepError [] String]]
  :state state
  :init init)

(defn DrakeEvent-init
  []
  [[] (atom {})])

(defn DrakeEvent-getTimestamp
  [this]
  (:timestamp @(.state this)) )

(defn DrakeEvent-getState
  [this]
  (generate-string @(.state this)) )

(defn DrakeEvent-getSteps
  [this]
  (generate-string (:steps @(.state this))))

(defn DrakeEvent-getStep
  [this]
  (generate-string (:step @(.state this))))

(defn DrakeEvent-getStepError
  [this]
  (:error @(.state this)) )

(defn EventWorkflowBegin
  [steps]
  (let [event (drake.event.DrakeEventWorkflowBegin.)
        state (.state event)]
    (reset! state 
            {:type "worfklow-begin"
             :timestamp (System/currentTimeMillis)
             :steps steps})
    event))

(defn EventWorkflowEnd
  []
  (let [event (drake.event.DrakeEventWorkflowEnd.)
        state (.state event)]
    (reset! state 
            {:type "workflow-end"
             :timestamp (System/currentTimeMillis)})
    event))

(defn EventStepBegin
  [step]
  (let [event (drake.event.DrakeEventStepBegin.)
        state (.state event)]
    (reset! state 
            {:type "step-begin"
             :timestamp (System/currentTimeMillis)
             :step step})
    event))

(defn EventStepEnd
  [step]
  (let [event (drake.event.DrakeEventStepEnd.)
        state (.state event)]
    (reset! state 
            {:type "step-end"
             :timestamp (System/currentTimeMillis)
             :step step})
    event))

(defn EventStepError
  [step error]
  (let [event (drake.event.DrakeEventStepError.)
        state (.state event)]
    (reset! state 
            {:type "step-error"
             :timestamp (System/currentTimeMillis)
             :step step
             :error error})
    event))


