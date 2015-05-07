;;;; Drake Events
;;;;
;;;; Define classes and constructors intended for communicating
;;;; Drake events to a calling program so that the calling program
;;;; can track the progress of a workflow.

(ns drake.event
  (:require [cheshire.core :as json]))

;;; Class definitions

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

;;; Class methods

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

;;; Class "constructors"

(defn setup-event-state
  [event state-props]
  (let [state (.state event)]
    (reset! state
            (assoc state-props :timestamp (System/currentTimeMillis))))
  event)

(defn EventWorkflowBegin
  "Constructor function for EventWorkflowBegin class.
  Takes a JSON array of step hashes."
  [steps]
  (setup-event-state (drake.event.DrakeEventWorkflowBegin.)
                     {:type "workflow-begin"
                      :steps steps}))

(defn EventWorkflowEnd
  "Constructor function for EventWorkflowEnd class."
  []
  (setup-event-state (drake.event.DrakeEventWorkflowEnd.)
                     {:type "workflow-end"}))

(defn EventStepBegin
  "Constructor function for EventStepBegin class.
  Takes a JSON hash with all the step info."
  [step]
  (setup-event-state (drake.event.DrakeEventStepBegin.)
                     {:type "step-begin"
                      :step step}))

(defn EventStepEnd
  "Constructor function for EventStepEnd class.
  Takes a JSON hash with all the step info."
  [step]
  (setup-event-state (drake.event.DrakeEventStepEnd.)
                     {:type "step-end"
                      :step step}))

(defn EventStepError
  "Constructor function for EventStepError class.
  Takes a JSON hash with all the step info and a
  Throwable that represents the error."
  [step ^Throwable error]
  (setup-event-state (drake.event.DrakeEventStepError.)
                     {:type "step-error"
                      :step step
                      :error (.toString error)}))
