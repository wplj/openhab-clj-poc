(ns example-addon.system-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [example-addon.api :as api]
            [example-addon.system :as system]
            [openhab.events :as events]
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

(defn- item-state-topic [item-name]
  (str "openhab/items/" item-name "/statechanged"))

(defn- await-event-matching [ch pred]
  (let [deadline (async/timeout 500)]
    (loop []
      (let [[event port] (async/alts!! [ch deadline])]
        (cond
          (= port deadline) nil
          (pred event) event
          :else (recur))))))

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

(deftest async-start-registers-structure-before-first-report
  (reset-api!)
  (let [ctx (system/start! {:interval-ms 60000
                            :initial-fetch? false})]
    (try
      (is (= :initializing (thing-status ctx "ap-1")))
      (is (nil? (item-state ctx "AP_FanSpeed")))
      (reporting/report-channels! ctx "ap-1" (api/fetch! "ap-1"))
      (is (= :online (thing-status ctx "ap-1")))
      (is (= 3 (item-state ctx "AP_FanSpeed")))
      (finally
        (system/stop! ctx)))))

(deftest manual-report-publishes-status-and-item-events
  (api/reset-state! {})
  (let [ctx          (system/start! {:interval-ms 60000})
        status-topic "openhab/things/ap-1/statuschanged"
        status-ch    (events/subscribe-topic! (:bus ctx) status-topic 8)
        item-chs     (into {}
                           (map (fn [item-name]
                                  [item-name
                                   (events/subscribe-topic! (:bus ctx)
                                                            (item-state-topic item-name)
                                                            8)]))
                           ["AP_FanSpeed" "AP_Temp" "AP_Mode"])]
    (try
      (api/reset-state! {"ap-1" {:fan-speed 3
                                 :temp 21
                                 :mode "auto"}})
      (reporting/report-channels! ctx "ap-1" (api/fetch! "ap-1"))
      (let [status-event (await-event-matching status-ch #(= :online (get-in % [:status :value])))
            item-events  (mapv (fn [[item-name state]]
                                  (await-event-matching (get item-chs item-name)
                                                        #(and (= item-name (:item-name %))
                                                              (= state (:state %)))))
                                {"AP_FanSpeed" 3
                                 "AP_Temp" 21
                                 "AP_Mode" "auto"})]
        (is (= "ThingStatusInfoChangedEvent" (:event/name status-event)))
        (is (= "openhab/things/ap-1/statuschanged" (:event/topic status-event)))
        (is (= :offline (get-in status-event [:old-status :value])))
        (is (= :online (get-in status-event [:status :value])))
        (is (= #{"AP_FanSpeed" "AP_Temp" "AP_Mode"}
               (set (map :item-name item-events))))
        (is (= #{3 21 "auto"} (set (map :state item-events)))))
      (finally
        (events/unsubscribe-topic! (:bus ctx) status-topic status-ch)
        (doseq [[item-name ch] item-chs]
          (events/unsubscribe-topic! (:bus ctx) (item-state-topic item-name) ch))
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

(deftest item-command-publishes-optimistic-item-event
  (reset-api!)
  (let [ctx        (system/start! {:interval-ms 60000})
        item-topic (item-state-topic "AP_FanSpeed")
        item-ch    (events/subscribe-topic! (:bus ctx) item-topic 8)]
    (try
      (is (true? (:ok (system/dispatch-command! ctx "AP_FanSpeed" 5))))
      (let [event (await-event-matching item-ch #(and (= "AP_FanSpeed" (:item-name %))
                                                      (= 5 (:state %))))]
        (is (= "ItemStateChangedEvent" (:event/name event)))
        (is (= "openhab/items/AP_FanSpeed/statechanged" (:event/topic event)))
        (is (= "AP_FanSpeed" (:item-name event)))
        (is (= 3 (:old-state event)))
        (is (= 5 (:state event))))
      (finally
        (events/unsubscribe-topic! (:bus ctx) item-topic item-ch)
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
