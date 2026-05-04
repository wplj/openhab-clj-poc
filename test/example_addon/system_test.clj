(ns example-addon.system-test
  (:require [clojure.test :refer [deftest is testing]]
            [example-addon.api :as api]
            [example-addon.system :as system]
            [openhab.registry :as registry]
            [openhab.reporting :as reporting]))

(defn- reset-api! []
  (api/reset-state! {"ap-1" {:fan-speed 3
                             :temp 21
                             :mode "auto"}}))

(defn- item-state [ctx item-name]
  (:state (registry/get-item (:registry ctx) item-name)))

(defn- thing-status [ctx thing-id]
  (get-in (registry/get-thing (:registry ctx) thing-id)
          [:runtime :status :value]))

(deftest start-projects-initial-device-state
  (reset-api!)
  (let [ctx (system/start! {:interval-ms 60000})]
    (try
      (is (= :online (thing-status ctx "ap-1")))
      (is (= 3 (item-state ctx "AP_FanSpeed")))
      (is (= 21 (item-state ctx "AP_Temp")))
      (is (= "auto" (item-state ctx "AP_Mode")))
      (finally
        (system/stop! ctx)))))

(deftest item-command-updates-device-and-converges-on-next-report
  (reset-api!)
  (let [ctx (system/start! {:interval-ms 60000})]
    (try
      (testing "command dispatch writes desired state, projects optimistically, and sends the effect"
        (let [result (system/dispatch-command! ctx "AP_FanSpeed" 5)]
          (is (true? (:ok result)))
          (is (= 5 (item-state ctx "AP_FanSpeed")))
          (is (= 5 (:fan-speed (api/fetch! "ap-1"))))
          (is (= 5 (get-in (registry/get-thing (:registry ctx) "ap-1")
                           [:runtime :desired :fan-speed :value])))))
      (testing "a follow-up report makes device truth converge and clears desired"
        (reporting/report-channels! ctx "ap-1" (api/fetch! "ap-1"))
        (is (= 5 (item-state ctx "AP_FanSpeed")))
        (is (= {} (get-in (registry/get-thing (:registry ctx) "ap-1")
                          [:runtime :desired]))))
      (finally
        (system/stop! ctx)))))

(deftest read-only-item-command-is-rejected-without-changing-device
  (reset-api!)
  (let [ctx (system/start! {:interval-ms 60000})]
    (try
      (is (= {:ok false :reason :channel-read-only}
             (system/dispatch-command! ctx "AP_Temp" 99)))
      (is (= 21 (item-state ctx "AP_Temp")))
      (is (= 21 (:temp (api/fetch! "ap-1"))))
      (finally
        (system/stop! ctx)))))

(deftest missing-device-starts-offline-without-projected-state
  (api/reset-state! {})
  (let [ctx (system/start! {:interval-ms 60000})]
    (try
      (is (= :offline (thing-status ctx "ap-1")))
      (is (nil? (item-state ctx "AP_FanSpeed")))
      (finally
        (system/stop! ctx)))))