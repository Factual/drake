(defproject factual/drake "0.1.4-SNAPSHOT"
  :description "Drake: the data processing workflow tool (a.k.a. 'make for data')"
  :url "https://github.com/Factual/drake"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/tools.logging "0.2.3"]
                 [clj-logging-config "1.9.6"]
                 [clojopts/clojopts "0.3.2"]
                 [fs "1.3.2"]
                 [factual/jlk-time "0.1"]
                 [digest "1.4.0"]
                 [slingshot "0.10.2"]
                 [factual/fnparse "2.3.0"]
                 [commons-codec/commons-codec "1.6"]
                 [factual/sosueme "0.0.15"]
                 [factual/c4 "0.1.2"]
                 ;; for HDFS support
                 [hdfs-clj "0.1.0"]
                 ;; you may need to change this to be compatible with your cluster
                 [org.apache.hadoop/hadoop-core "0.20.2"]
                 ;; for AWS S3 support
                 [clj-aws-s3 "0.3.3"]]
  :test-selectors {:regression   :regression
                   :default      (complement :regression)
                   :all          (constantly true)}
  :main drake.core
  :uberjar-name "drake.jar"
  :aot :all)
