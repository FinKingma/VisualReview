;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Copyright 2015 Xebia B.V.
;
; Licensed under the Apache License, Version 2.0 (the "License")
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;     http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(ns com.xebia.visualreview.mock
  (:require [clojure.java.jdbc :as j]
            [clojure.java.io :as jio]
            [clojure.tools.logging :as log]
            [com.xebia.visualreview.io :as io]
            [com.xebia.visualreview.service.persistence.database :as db]
            [com.xebia.visualreview.api-test :as api]
            [com.xebia.visualreview.itest-util :as util])
  (:import [java.io File]
           [java.nio.file Files Paths SimpleFileVisitor FileVisitResult Path LinkOption]
           [java.nio.file.attribute BasicFileAttributes]))

(def ^:dynamic *conn* {:classname      "org.h2.Driver"
                       :subprotocol    "h2"
                       :subname        "file:./target/temp/vrtest.db;TRACE_LEVEL_FILE=2"
                       :user           ""
                       :init-pool-size 1
                       :max-pool-size  1})

(defn- path-exists? [^Path path]
  (Files/exists path (into-array LinkOption nil)))

(defn delete-recursively!
  "Deletes all files and subdirectories recursively. Will not follow or delete symlinks."
  [filename]
  (let [path (Paths/get filename (into-array String nil))]
    (when (path-exists? path)
      (let [file-visitor (proxy [SimpleFileVisitor] []
                           (preVisitDirectory [_ ^BasicFileAttributes attrs]
                             (if (.isSymbolicLink attrs)
                               FileVisitResult/SKIP_SUBTREE
                               FileVisitResult/CONTINUE))
                           (visitFile [file ^BasicFileAttributes attrs]
                             (when-not (.isSymbolicLink attrs)
                               (Files/delete file))
                             FileVisitResult/CONTINUE)
                           (postVisitDirectory [dir _]
                             (Files/delete dir)
                             FileVisitResult/CONTINUE))]
        (Files/walkFileTree path file-visitor)))))

(def test-screenshot-dir "target/temp/screenshots")

(defn setup-db []
  (log/info "Setting up test database")
  (j/with-db-connection [conn *conn*]
    (j/execute! conn ["DROP ALL OBJECTS"])
    (db/update-db-schema! conn)))

(defn test-server-fixture [f]
  (util/start-server)
  (f)
  (util/stop-server))

(defn setup-screenshot-dir-fixture [f]
  (log/debug "Rebinding screenshot dir to" test-screenshot-dir)
  (with-redefs [io/screenshots-dir test-screenshot-dir]
    (delete-recursively! io/screenshots-dir)
    (.mkdirs ^File (jio/file io/screenshots-dir))
    (f)))

(defn rebind-db-spec-fixture [f]
  (log/debug "Rebinding db spec to" (:subname *conn*))
  (with-redefs [db/conn *conn*]
    (f)))

(defn setup-db-fixture [f]
  (log/info "Setting up mock db")
  (setup-db)
  (f))

(defn logging-fixture [f]
  (binding [log/*tx-agent-levels* #{:warn}]
  (f)))

(def compare-settings {:precision    "0"})
(def compare-settings10 {:precision    "10"})
(def compare-settings-aa {:anti-aliasing    true})

(defn upload-tapir [run-id meta props]
  (api/upload-screenshot! run-id {:file "tapir.png" :meta meta :properties props :screenshotName "Tapir" :compareSettings compare-settings}))
(defn upload-tapir-hat [run-id meta props]
  (api/upload-screenshot! run-id {:file "tapir_hat.png" :meta meta :properties props :screenshotName "Tapir" :compareSettings compare-settings}))
(defn upload-chess-image-1 [run-id meta props]
  (api/upload-screenshot! run-id {:file "chess1.png" :meta meta :properties props :screenshotName "Kasparov vs Topalov - 1999" :compareSettings compare-settings}))
(defn upload-chess-image-2 [run-id meta props]
  (api/upload-screenshot! run-id {:file "chess2.png" :meta meta :properties props :screenshotName "Kasparov vs Topalov - 1999" :compareSettings compare-settings}))
(defn upload-zd-image-1 [run-id meta props]
  (api/upload-screenshot! run-id {:file "ZD_1.png" :meta meta :properties props :screenshotName "Referral" :compareSettings compare-settings}))
(defn upload-zd-image-2 [run-id meta props]
  (api/upload-screenshot! run-id {:file "ZD_2.png" :meta meta :properties props :screenshotName "Referral" :compareSettings compare-settings}))
(defn upload-zd-image-1-p10 [run-id meta props]
  (api/upload-screenshot! run-id {:file "ZD_1.png" :meta meta :properties props :screenshotName "Referral-p10" :compareSettings compare-settings10}))
(defn upload-zd-image-2-p10 [run-id meta props]
  (api/upload-screenshot! run-id {:file "ZD_2.png" :meta meta :properties props :screenshotName "Referral-p10" :compareSettings compare-settings10}))
(defn upload-qr-image-1 [run-id meta props]
  (api/upload-screenshot! run-id {:file "aliased_1.png" :meta meta :properties props :screenshotName "mountain" :compareSettings compare-settings}))
(defn upload-qr-image-2 [run-id meta props]
  (api/upload-screenshot! run-id {:file "aliased_2.png" :meta meta :properties props :screenshotName "mountain" :compareSettings compare-settings}))
(defn upload-qr-image-1-aa [run-id meta props]
  (api/upload-screenshot! run-id {:file "aliased_1.png" :meta meta :properties props :screenshotName "mountain-AA" :compareSettings compare-settings-aa}))
(defn upload-qr-image-2-aa [run-id meta props]
  (api/upload-screenshot! run-id {:file "aliased_2.png" :meta meta :properties props :screenshotName "mountain-AA" :compareSettings compare-settings-aa}))
(defn upload-qr-image-1-aa2 [run-id meta props]
  (api/upload-screenshot! run-id {:file "aliased_1.png" :meta meta :properties props :screenshotName "mountain-AA2" :compareSettings compare-settings-aa}))
(defn upload-qr-image-2-aa2 [run-id meta props]
  (api/upload-screenshot! run-id {:file "aliased_4.png" :meta meta :properties props :screenshotName "mountain-AA2" :compareSettings compare-settings-aa}))
(defn upload-line-image-1 [run-id meta props]
  (api/upload-screenshot! run-id {:file "noticeSmallDiff_1.png" :meta meta :properties props :screenshotName "Line" :compareSettings compare-settings}))
(defn upload-line-image-2 [run-id meta props]
  (api/upload-screenshot! run-id {:file "noticeSmallDiff_2.png" :meta meta :properties props :screenshotName "Line" :compareSettings compare-settings}))
