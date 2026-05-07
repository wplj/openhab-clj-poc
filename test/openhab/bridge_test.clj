(ns openhab.bridge-test
  "Tests for bridge helper operations over the runtime transition path."
  (:require [clojure.test :refer [deftest is]]
            [openhab.bridge :as bridge]
            [openhab.events :as events]
            [openhab.profile :as profile]
            [openhab.registry :as registry]
            [openhab.runtime :as runtime]
            [openhab.thing :as thing]
            [openhab.transition :as transition]))

(defn- make-context []
  {:registry (registry/make-registry)
   :bus (events/make-bus 32)
   :effect-dispatcher (constantly nil)})

(defn- apply! [context transition-fn & args]
  (apply runtime/apply-transition!
         (:registry context)
         (:bus context)
         (:effect-dispatcher context)
         transition-fn
         args))

(deftest add-child-registers-child-under-bridge
  (let [context (make-context)]
    (apply! context transition/add-thing (thing/make-bridge "bridge-1" :bridge))
    (bridge/add-child! context "bridge-1" (thing/make-thing "child-1" :sensor))
    (is (= "bridge-1" (:bridge-id (registry/get-thing (:registry context) "child-1"))))
    (is (= #{"child-1"}
           (set (map :thing-id (bridge/get-children context "bridge-1")))))))

(deftest remove-child-unregisters-child
  (let [context (make-context)]
    (apply! context transition/add-thing (thing/make-bridge "bridge-1" :bridge))
    (bridge/add-child! context "bridge-1" (thing/make-thing "child-1" :sensor))
    (bridge/remove-child! context "bridge-1" "child-1")
    (is (nil? (registry/get-thing (:registry context) "child-1")))))

(deftest remove-child-rejects-child-owned-by-another-bridge
  (let [context (make-context)]
    (apply! context transition/add-thing (thing/make-bridge "bridge-1" :bridge))
    (apply! context transition/add-thing (thing/make-bridge "bridge-2" :bridge))
    (bridge/add-child! context "bridge-2" (thing/make-thing "child-1" :sensor))
    (let [result (bridge/remove-child! context "bridge-1" "child-1")]
      (is (= {:ok false :reason :not-a-child} (:result result)))
      (is (some? (registry/get-thing (:registry context) "child-1")))
      (is (= "bridge-2" (:bridge-id (registry/get-thing (:registry context) "child-1")))))))

(deftest offline-bridge-status-propagates-to-children
  (let [context (make-context)]
    (apply! context transition/add-thing (-> (thing/make-bridge "bridge-1" :bridge)
                                                  (assoc-in [:runtime :status] (thing/status :online))))
    (bridge/add-child! context "bridge-1" (-> (thing/make-thing "child-1" :sensor)
                                               (assoc :bridge-id "bridge-1")
                                               (assoc-in [:runtime :status] (thing/status :online))))
    (bridge/add-child! context "bridge-1" (-> (thing/make-thing "child-2" :sensor)
                                               (assoc :bridge-id "bridge-1")
                                               (assoc-in [:runtime :status] (thing/status :online))))
    (bridge/propagate-status! context "bridge-1" (thing/status :offline))
    (is (= :offline (thing/status-value (registry/get-thing (:registry context) "bridge-1"))))
    (is (= :offline (thing/status-value (registry/get-thing (:registry context) "child-1"))))
    (is (= :offline (thing/status-value (registry/get-thing (:registry context) "child-2"))))))

(deftest online-bridge-status-does-not-rewrite-children
  (let [context (make-context)]
    (apply! context transition/add-thing (thing/make-bridge "bridge-1" :bridge))
    (bridge/add-child! context "bridge-1" (-> (thing/make-thing "child-1" :sensor)
                                               (assoc-in [:runtime :status] (thing/status :offline))))
    (bridge/propagate-status! context "bridge-1" (thing/status :online))
    (is (= :online (thing/status-value (registry/get-thing (:registry context) "bridge-1"))))
    (is (= :offline (thing/status-value (registry/get-thing (:registry context) "child-1"))))))

(deftest start-own-channel-polling-reports-bridge-channels-and-marks-online
  (let [context (assoc (make-context) :profiles (profile/make-registry))]
    (apply! context transition/add-thing
            (assoc (thing/make-bridge "bridge-1" :bridge)
                   :channels {:status {:access :ro :channel-type :string}}))
    (let [handle (bridge/start-own-channel-polling!
                   context
                   {:bridge-id       "bridge-1"
                    :fetch-fn        (constantly {:status "ok"})
                    :interval-ms     60000
                    :initial-fetch?  true})]
      (try
        (is (= :online (thing/status-value (registry/get-thing (:registry context) "bridge-1"))))
        (finally
          (bridge/stop! context "bridge-1" handle))))))

(deftest stop-marks-bridge-and-children-offline
  (let [context (assoc (make-context) :profiles (profile/make-registry))]
    (apply! context transition/add-thing
            (assoc-in (thing/make-bridge "bridge-1" :bridge)
                      [:runtime :status] (thing/status :online)))
    (bridge/add-child! context "bridge-1"
                       (assoc-in (thing/make-thing "child-1" :sensor)
                                 [:runtime :status] (thing/status :online)))
    (let [handle (bridge/start-own-channel-polling!
                   context
                   {:bridge-id      "bridge-1"
                    :fetch-fn       (constantly {})
                    :interval-ms    60000
                    :initial-fetch? false})]
      (bridge/stop! context "bridge-1" handle))
    (is (= :offline (thing/status-value (registry/get-thing (:registry context) "bridge-1"))))
    (is (= :offline (thing/status-value (registry/get-thing (:registry context) "child-1"))))))
