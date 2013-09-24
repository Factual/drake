(ns drake.event
  (:require [cheshire.core :as json]
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
  (json/generate-string @(.state this)))

(defn DrakeEvent-getSteps
  [this]
  (json/generate-string (:steps @(.state this))))

(defn DrakeEvent-getStep
  [this]
  (json/generate-string (:step @(.state this))))

(defn DrakeEvent-getStepError
  [this]
  (json/generate-string (:error @(.state this))))

(defn setup-event-state
  [event state-props]
  (let [state (.state event)]
    (reset! state
            (assoc state-props :timestamp (System/currentTimeMillis))))
  event)

(defn EventWorkflowBegin
  [steps]
  (setup-event-state (drake.event.DrakeEventWorkflowBegin.)
                     {:type "worfklow-begin"
                      :steps steps}))

(defn EventWorkflowEnd
  []
  (setup-event-state (drake.event.DrakeEventWorkflowEnd.)
                     {:type "workflow-end"}))

(defn EventStepBegin
  [step]
  (setup-event-state (drake.event.DrakeEventStepBegin.)
                     {:type "step-begin"
                      :step step}))

(defn EventStepEnd
  [step]
  (setup-event-state (drake.event.DrakeEventStepEnd.)
                     {:type "step-end"
                      :step step}))

(defn EventStepError
  [step ^Throwable error]
  (setup-event-state (drake.event.DrakeEventStepError.)
                     {:type "step-error"
                      :step step
                      :error (.toString error)}))


