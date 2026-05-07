(ns openhab.reporting-test
  "Tests for channel reporting as the adapter between polling and transitions."
  (:require [clojure.test :refer [deftest is]]
            [openhab.item :as item]
            [openhab.link :as link]
            [openhab.profile :as profile]
            [openhab.registry :as registry]
            [openhab.reporting :as reporting]
            [openhab.runtime :as runtime]
            [openhab.thing :as thing]
            [openhab.transition :as transition]))

(defn- profiles []
  (-> (profile/make-registry)
      (profile/register-codec [:fan :fan-speed]
                              {:to-state (fn [v] {:state v :state-type :number})
                               :from-state identity})))

(defn- context []
  {:registry (registry/make-registry)
   :bus nil
   :profiles (profiles)
   :effect-dispatcher nil})

(defn- apply! [{:keys [registry bus effect-dispatcher]} transition-fn & args]
  (apply runtime/apply-transition! registry bus effect-dispatcher transition-fn args))

(defn- install-device! [ctx]
  (apply! ctx
          transition/add-thing
          (-> (thing/make-thing "dev-1" :fan)
              (assoc :channels {:fan-speed {:access :rw :channel-type :number}}))))

(defn- install-item-link! [ctx]
  (apply! ctx transition/add-item (item/make-item "FanSpeed" "Number"))
  (apply! ctx transition/add-link (:profiles ctx) (link/make-link "FanSpeed" "dev-1" :fan-speed) java.time.Instant/EPOCH))

(deftest mark-offline-sets-thing-status-offline
  (let [ctx (context)]
    (install-device! ctx)
    (reporting/report-channels! ctx "dev-1" {:fan-speed 4})
    (is (= :online (thing/status-value (registry/get-thing (:registry ctx) "dev-1"))))
    (reporting/mark-offline! ctx "dev-1")
    (is (= :offline (thing/status-value (registry/get-thing (:registry ctx) "dev-1"))))))

(deftest report-channels-projects-items-and-marks-thing-online
  (let [ctx (context)]
    (install-device! ctx)
    (install-item-link! ctx)
    (reporting/report-channels! ctx "dev-1" {:fan-speed 4})
    (is (= :online (thing/status-value (registry/get-thing (:registry ctx) "dev-1"))))
    (is (= 4 (:state (registry/get-item (:registry ctx) "FanSpeed"))))))

(deftest initial-fetch-makes-startup-ready-before-returning
  (let [ctx (context)
        fetch-count (atom 0)]
    (install-device! ctx)
    (install-item-link! ctx)
    (let [handle (reporting/start-channel-polling!
                   ctx
                   {:thing-id "dev-1"
                    :fetch-fn (fn []
                                (swap! fetch-count inc)
                                {:fan-speed 7})
                    :interval-ms 60000
                    :initial-fetch? true})]
      (try
        (is (= 1 @fetch-count))
        (is (= :online (thing/status-value (registry/get-thing (:registry ctx) "dev-1"))))
        (is (= 7 (:state (registry/get-item (:registry ctx) "FanSpeed"))))
        (is (= true (get-in handle [:initial-result :ok])))
        (is (= true (get-in handle [:initial-result :result :ok])))
        (finally
          (reporting/stop-channel-polling! handle))))))

(deftest initial-fetch-failure-marks-thing-offline
  (let [ctx (context)]
    (install-device! ctx)
    (let [handle (reporting/start-channel-polling!
                   ctx
                   {:thing-id "dev-1"
                    :fetch-fn (fn [] (throw (ex-info "fetch failed" {})))
                    :interval-ms 60000
                    :initial-fetch? true})]
      (try
        (is (= :offline (thing/status-value (registry/get-thing (:registry ctx) "dev-1"))))
        (is (= false (get-in handle [:initial-result :ok])))
        (is (instance? clojure.lang.ExceptionInfo (:error (:initial-result handle))))
        (finally
          (reporting/stop-channel-polling! handle))))))


