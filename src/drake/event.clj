(ns drake.event
  (:require [clojure.string :as str]
            [clj-logging-config.log4j :as log4j])
  (:use [clojure.tools.logging :only [info debug trace error]]
        [slingshot.slingshot :only [try+ throw+]]
        sosueme.throwables)) 

(gen-class
  :name drake.event.DrakeEvent
  :prefix DrakeEvent-
  :methods [[getTimestamp [] long]]
  ) 
(gen-class
  :name drake.event.DrakeEventWorkflowBegin
  :extends drake.event.DrakeEvent
  :prefix DrakeEvent-
  :methods [[getSteps [] long]]
  :state state
  :init init
  ) 
(gen-class
  :name drake.event.DrakeEventWorkflowEnd
  :extends drake.event.DrakeEvent
  :prefix DrakeEvent-
  :state state
  :init init
  ) 
(gen-class
  :name drake.event.DrakeEventStepBegin
  :extends drake.event.DrakeEvent
  :prefix DrakeEvent-
  :methods [[getStepId [] String]
            [getStepDesc [] String]]
  :state state
  :init init
  ) 
(gen-class
  :name drake.event.DrakeEventStepEnd
  :extends drake.event.DrakeEvent
  :prefix DrakeEvent-
  :methods [[getStepId [] String]
            [getStepDesc [] String]]
  :state state
  :init init
  ) 
(gen-class
  :name drake.event.DrakeEventStepError
  :extends drake.event.DrakeEvent
  :prefix DrakeEvent-
  :methods [[getStepId [] String]
            [getStepDesc [] String] 
            [getStepError [] String]]
  :state state
  :init init
  )

(defn DrakeEvent-init
  []
  [[] (atom {})])

(defn DrakeEvent-getTimestamp
  [this]
  (:timestamp @(.state this)) 
  )

(defn DrakeEvent-getSteps
  [this]
  (:timestamp @(.state this)) 
  )

(defn DrakeEvent-getStepId
  [this]
  (:step-id @(.state this)) 
  )

(defn DrakeEvent-getStepDesc
  [this]
  (:step-desc @(.state this)) 
  )


(defn DrakeEvent-getStepError
  [this]
  (:step-error @(.state this)) 
  )



