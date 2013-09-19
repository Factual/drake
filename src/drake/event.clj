(ns drake.event
  (:require [clojure.string :as str]
            [clj-logging-config.log4j :as log4j])
  (:use [clojure.tools.logging :only [info debug trace error]]
        [slingshot.slingshot :only [try+ throw+]]
        sosueme.throwables)) 

(gen-class
  :name drake.event.DrakeEvent
  :prefix DrakeEvent-
  :methods [[getTimestamp [] long]]) 

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
            [getStepError [] Throwable]]
  :state state
  :init init)

(defn DrakeEvent-init
  []
  [[] (atom {})])

(defn DrakeEvent-getTimestamp
  [this]
  (:timestamp @(.state this)) )

(defn DrakeEvent-getSteps
  [this]
  (:steps @(.state this)))

(defn DrakeEvent-getStep
  [this]
  (:step @(.state this)) )

(defn DrakeEvent-getStepError
  [this]
  (:error @(.state this)) )

(defn EventWorkflowBegin
  [steps]
  (let [event (drake.event.DrakeEventWorkflowBegin.)
        state (.state event)]
    (reset! state 
            {:timestamp (System/currentTimeMillis)
             :steps steps})
    event))

(defn EventWorkflowEnd
  []
  (let [event (drake.event.DrakeEventWorkflowEnd.)
        state (.state event)]
    (reset! state 
            {:timestamp (System/currentTimeMillis)})
    event))

(defn EventStepBegin
  [step]
  (let [event (drake.event.DrakeEventStepBegin.)
        state (.state event)]
    (reset! state 
            {:timestamp (System/currentTimeMillis)
             :step step})
    event))

(defn EventStepEnd
  [step]
  (let [event (drake.event.DrakeEventStepEnd.)
        state (.state event)]
    (reset! state 
            {:timestamp (System/currentTimeMillis)
             :step step})
    event))

(defn EventStepError
  [step ^Throwable error]
  (let [event (drake.event.DrakeEventStepError.)
        state (.state event)]
    (reset! state 
            {:timestamp (System/currentTimeMillis)
             :step step
             :error error})
    event))


