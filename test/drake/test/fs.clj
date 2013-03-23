(ns drake.test.fs
  (:refer-clojure :exclude [file-seq])
  (:require [fs.core :as fs]
            [aws.sdk.s3 :as s3]
            )

  (:import [drake.fs MockFileSystem LocalFileSystem HDFS S3])
  (:use [drake.fs]
        drake.options
        [clojure.test]
        [clojure.string :only [join split]])
        )

;; TODO(aaron)
;; Tests that modify local file system are strictly speaking not unittests,
;; they are regression tests, it might not be wise to run them with
;; "lein test"

(def DIR "/tmp/drake-test-fs")

(def S3-BUCKET "civ-test-drake")
(def S3-PREFIX "test")


;; TODO(howech)
;; s3 tests require permissions over a particular bucket. To get them to run, you may have to 
;; fiddle with them a little to get it set up for your bucket and your credentials.
;;
;; ;; This is a hack to quicly load credentials. The file loaded should contain something like
;; ;; the following
;; ;;  { :access-key "<AWSACCESSKEY>" :secret-key "<AWSSECRETKEY>" }
;;(def ^:private s3-test-credentials
;;  (memoize #(load-file "/home/chris/creds.clj")))
;; 
;;(defn setup-s3
;;  "Initializes a s3 filesystem in particular order mod for testing:
;;
;;  [DIR]
;;    ├── A       1
;;    └── Y       (implicit)
;;      ├── A     2
;;      ├── B     3
;;      ├── C     4
;;      └── _logs 5"
;;  []
;;  
;;  (let [A (join "/" (list S3-PREFIX "A"))
;;        Y (join "/" (list S3-PREFIX "Y"))
;;        YA (join "/" (list S3-PREFIX "Y" "A"))
;;        YB (join "/" (list S3-PREFIX "Y" "B"))
;;        YC (join "/" (list S3-PREFIX "Y" "C"))
;;        ;; should be ignored
;;        _logs (join "/" (list S3-PREFIX "Y" "_logs"))
;;        ;; Writing to S3 is somewhat non-deterministic as to exactly when the
;;        ;; timestamps are dated. The following is fudge factor to delay "long enough"
;;        ;; between writes to ensure that the writes are in order. 
;;        delay 300]
;;    
;;    (set-options (into *options* { :aws-credentials "/home/chris/.s3cfg" } ))
;;
;;    (s3/delete-object (s3-test-credentials) S3-BUCKET A)
;;    (s3/delete-object (s3-test-credentials) S3-BUCKET Y)
;;    (s3/delete-object (s3-test-credentials) S3-BUCKET YA)
;;    (s3/delete-object (s3-test-credentials) S3-BUCKET YB)
;;    (s3/delete-object (s3-test-credentials) S3-BUCKET YC)
;;    (s3/delete-object (s3-test-credentials) S3-BUCKET _logs)
;;
;;    (s3/put-object (s3-test-credentials) S3-BUCKET A  "A"  )
;;    (. Thread (sleep delay))
;;    (s3/put-object (s3-test-credentials) S3-BUCKET YA "YA" )
;;    (. Thread (sleep delay))
;;    (s3/put-object (s3-test-credentials) S3-BUCKET YB "YB" )
;;    (. Thread (sleep delay))
;;    (s3/put-object (s3-test-credentials) S3-BUCKET YC "YC" )
;;    (. Thread (sleep delay))
;;    (s3/put-object (s3-test-credentials) S3-BUCKET _logs "_logs")
;;    ))

(defn setup-local
  "Initializes a local filesystem with specific mod times for testing:

  [DIR]
    ├── A    1
    └── Y    2
      ├── A  8
      ├── B 12
      └── C [THE-FUTURE]"
  []
  (fs/delete-dir DIR)
  (fs/mkdir DIR)

  (let [A (fs/file DIR "A")
        Y (fs/mkdir (fs/file DIR "Y"))
        YA (fs/file DIR "Y/A")
        YB (fs/file DIR "Y/B")
        YC (fs/file DIR "Y/C")
        ;; should be ignored
        _logs (fs/file DIR "Y/_logs")]

    (fs/touch A)
    (.setLastModified A 1000)

     (fs/touch YA)
    (.setLastModified YA 8000)

    (fs/touch YB)
    (.setLastModified YB 12000)

    (fs/touch YC)
    (.setLastModified YC
                      (+ 10000 (System/currentTimeMillis)))

    (fs/touch _logs)
    (.setLastModified _logs
                      (+ 20000 (System/currentTimeMillis)))))

(deftest test-mock-oldest-in
  (is (= "A" (:path (oldest-in "test:A"))))
  (is (= "Y/A" (:path (oldest-in "test:Y")))))

(deftest test-mock-newest-in
  (is (= "A"   (:path (newest-in "test:A"))))
  (is (= "Y/C" (:path (newest-in "test:Y")))))

(deftest test-local-oldest-in
  (setup-local)
  (is (= "/tmp/drake-test-fs/A"
         (:path (oldest-in "/tmp/drake-test-fs")))))

(deftest test-local-newest-in
  (setup-local)
  (is (= "/tmp/drake-test-fs/Y/C"
         (:path (newest-in "/tmp/drake-test-fs")))))

;;(deftest test-s3-oldest-in
;;  (setup-s3)
;;  (is (= (str "/" (join "/" (list  S3-BUCKET S3-PREFIX "A")))
;;         (:path (oldest-in (join "/" (list "s3:/" S3-BUCKET S3-PREFIX)))))))

;;(deftest test-s3-newest-in
;;  (setup-local)
;;  (is (= (str "/" (join "/" (list S3-BUCKET S3-PREFIX "Y" "C")))
;;         (:path (newest-in (join "/" (list "s3:/" S3-BUCKET S3-PREFIX)))))))