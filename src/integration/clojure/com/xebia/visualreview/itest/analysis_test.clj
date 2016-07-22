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

(ns com.xebia.visualreview.itest.analysis-test
  (:require [clojure.test :refer :all]
            [com.xebia.visualreview.api-test :as api]
            [com.xebia.visualreview.mock :as mock]
            [com.xebia.visualreview.itest-util :refer [start-server stop-server]]))

(def project-name "Test Project A")
(def suite-name "Test suite")
(def meta-info {:os      "LINUX"
                :version "31.4.0"})
(def properties {:browser    "firefox"
                 :resolution "1024x786"})

(def image-ids (atom {}))

(defn post-run-with-screenshots [& {:as fns}]
  (let [run-id (-> (api/post-run! project-name suite-name) :body :id)]
    (doseq [[k v] fns]
      (swap! image-ids assoc-in [run-id k] (-> (v run-id meta-info properties) :body :id)))
    run-id))

(defn setup-project []
  (api/put-project! {:name project-name})
  (post-run-with-screenshots :chess mock/upload-chess-image-1 :tapir mock/upload-tapir :line mock/upload-line-image-1 :zd mock/upload-zd-image-1 :zdp10 mock/upload-zd-image-1-p10 :qr mock/upload-qr-image-1 :qraa mock/upload-qr-image-1-aa :qraa2 mock/upload-qr-image-1-aa2))

(defn- content-type [response]
  (get-in response [:headers "Content-Type"]))

(use-fixtures :each mock/logging-fixture mock/rebind-db-spec-fixture mock/setup-screenshot-dir-fixture mock/setup-db-fixture mock/test-server-fixture)

(deftest analysis
  (setup-project)

  (testing "Analysis status"
    (is (= 1 (count (:body (api/get-runs project-name suite-name)))) "There is one run")
    (let [[chess-diff tapir-diff] (-> (api/get-analysis 1) :body :diffs)]
      (is (= "pending" (:status chess-diff)) "The chess diff is pending")
      (is (= "pending" (:status tapir-diff)) "The tapir diff is pending")

      (is (nil? (-> chess-diff :before)) "There is no baseline for the chess image yet")
      (is (nil? (-> tapir-diff :before)) "There is no baseline for the chess image yet")))

  (testing "Diff approval process"
    (let [response (api/update-diff-status! 1 1 "ejected")]
      (is (= 422 (:status response)) "Unprocessable entity")
      (is (re-find #"must be .*rejected" (:body response)))
      (are [run-id diff-id http-status new-status] (let [r (api/update-diff-status! run-id diff-id new-status)]
                                                     (and (= (:status r) http-status)
                                                          (= (-> r :body :status) new-status)))
        1 1 201 "rejected"
        1 1 201 "accepted"
        1 2 201 "accepted"
        1 3 201 "accepted"
        1 4 201 "accepted"
        1 5 201 "accepted"
        1 5 201 "accepted"
        1 6 201 "accepted"
        1 7 201 "accepted")))

  (testing "New run with different tapir image"
    (let [run-id (post-run-with-screenshots :chess mock/upload-chess-image-1 :tapir mock/upload-tapir-hat)
          {:keys [analysis diffs]} (:body (api/get-analysis run-id))
          [chess-diff tapir-diff] diffs]
      (are [k v] (= (k analysis) v)
        :id 2
        :baselineNode 1
        :runId 2)

      (is (= "pending" (:status tapir-diff)) "The tapir diff is pending")
      (is (= 8.89 (:percentage tapir-diff)))
      (is (> (-> tapir-diff :after :id) (-> tapir-diff :before :id)) "The tapir image differs from its previous version")

      (is (zero? (:percentage chess-diff)) "The chess diff is unchanged")
      (is (= "accepted" (:status chess-diff)) "The chess diff is automatically accepted")

      (is (= 201 (:status (api/update-diff-status! run-id (:id tapir-diff) "rejected"))) "Rejecting the tapir diff")))

  (testing "Third run with a different chess image"
    (let [run-id (post-run-with-screenshots :chess mock/upload-chess-image-2 :tapir mock/upload-tapir-hat)
          [chess-diff tapir-diff] (-> (api/get-analysis run-id) :body :diffs)]
      (is (= (:tapir (@image-ids (- run-id 2))) (-> tapir-diff :before :id)) "The tapir diff is compared with the version from the first run, as the second version")
      (is (= "pending" (:status tapir-diff)) "The tapir diff is pending")
      (is (= 8.89 (:percentage tapir-diff)) "The percentage difference is the same as in the first run")

      (is (= 1.03 (:percentage chess-diff)) "The chess image is changed with respect to the previous run")
      (is (= "pending" (:status chess-diff)) "The chess diff is pending")
      (is (= (:chess (@image-ids (dec run-id))) (-> chess-diff :before :id)) "The chess image is compared to the previous run")))

  (testing "No precision will fail comparison with unseen pixel difference"
    (let [run-id (post-run-with-screenshots :zd mock/upload-zd-image-2)
          [zd-diff] (-> (api/get-analysis run-id) :body :diffs)]
      (is (= "pending" (:status zd-diff)) "The zd diff is pending")))

  (testing "Precision will pass comparison if pixel difference is within specified value"
    (let [run-id (post-run-with-screenshots :zdp10 mock/upload-zd-image-2-p10)
          [zdp10-diff] (-> (api/get-analysis run-id) :body :diffs)]
      (is (= "accepted" (:status zdp10-diff)) "The zd diff is automatically accepted")))

  (testing "No anti aliasing will fail comparison if image has a slight blur"
    (let [run-id (post-run-with-screenshots :qr mock/upload-qr-image-2)
          [qr-diff] (-> (api/get-analysis run-id) :body :diffs)]
      (is (= "pending" (:status qr-diff)) "The qr diff is pending")))

  (testing "Anti aliasing will pass comparison if image has a slight blur"
    (let [run-id (post-run-with-screenshots :qr mock/upload-qr-image-2-aa)
          [qraa-diff] (-> (api/get-analysis run-id) :body :diffs)]
      (is (= "accepted" (:status qraa-diff)) "The zd diff is automatically accepted")))

  (testing "Anti aliasing will still fail comparison if image has a diff"
    (let [run-id (post-run-with-screenshots :qr mock/upload-qr-image-2-aa2)
          [qraa2-diff] (-> (api/get-analysis run-id) :body :diffs)]
      (is (= "pending" (:status qraa2-diff)) "The qr diff is pending")))
  )
