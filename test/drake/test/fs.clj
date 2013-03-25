(ns drake.test.fs
  (:refer-clojure :exclude [file-seq])
  (:require [fs.core :as fs]
            [aws.sdk.s3 :as s3]
            )

  (:import [drake.fs MockFileSystem LocalFileSystem HDFS S3])
  (:use drake.fs
        drake.options
        clojure.test
        [clojure.string :only [join split]])
        )

;; TODO(aaron)
;; Tests that modify local file system are strictly speaking not unittests,
;; they are regression tests, it might not be wise to run them with
;; "lein test"

(def DIR "/tmp/drake-test-fs")

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

