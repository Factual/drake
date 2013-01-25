(defproject drake "0.0.1"
  :description "data workflow"
  :url "https://github.com/Factual/drake"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  ;; foursquare's repo is needed for c4
  :repositories {"foursquareapijava" "http://foursquare-api-java.googlecode.com/svn/repository"}

  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/tools.cli "0.2.1"]
                 [org.clojure/tools.logging "0.2.3"]
                 [clj-logging-config "1.9.6"]
                 [fs "1.3.2"]
                 [factual/jlk-time "0.1"]
                 [digest "1.4.0"]
                 [slingshot "0.10.2"]
                 [factual/fnparse "2.3.0"]
                 [factual/sosueme "0.0.15"]

                 ;; for HDFS support
                 [hdfs-clj "0.1.0"]
                 ;; you may need to change this to be compatible with your cluster.
                 [org.apache.hadoop/hadoop-core "0.20.2"]

                 ;; for c4
                 [com.cemerick/pomegranate "0.0.13"]
                 [factql "1.0.3"]
                 [clojure-csv/clojure-csv "2.0.0-alpha2"]
                 [fi.foyt/foursquare-api "1.0.2"]
                 [cheshire "4.0.1"]
                 [ordered "1.3.1"]
                 [retry "1.0.2"]
                 ;;;; for yelp lib
                 [org.scribe/scribe "1.2.0"]
                 ;;;; for facebook lib
                 [org.clojars.krisajenkins/clj-facebook-graph "0.4.4"]
                 ;;;; for google places API
                 [com.google.api-client/google-api-client "1.8.0-beta"]
                 ;;;;;; forced downgrade, otherwise we get an error about hostname and SSL certificate.
                 ;;;;;; https://issues.apache.org/jira/browse/HTTPCLIENT-1125
                 [org.apache.httpcomponents/httpclient "4.1.1"]]
  :test-selectors {:regression   :regression
                   :default      (complement :regression)
                   :all          (constantly true)}
  :main drake.core
  :uberjar-name "drake.jar"
  :aot :all)
