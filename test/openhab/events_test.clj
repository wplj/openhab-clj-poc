(ns openhab.events-test
  "Tests for event decoration and bus subscription modes."
  (:require [clojure.core.async :refer [alts!! timeout]]
            [clojure.test :refer [deftest is]]
            [openhab.events :as events]
            [openhab.item :as item]
            [openhab.link :as link]
            [openhab.profile :as profile]
            [openhab.registry :as registry]
            [openhab.runtime :as runtime]
            [openhab.thing :as thing]
            [openhab.transition :as transition]))

(defn- await-event
  ([ch] (await-event ch 500))
  ([ch timeout-ms]
   (first (alts!! [ch (timeout timeout-ms)]))))

(defn- no-op-effect [_] nil)

(deftest runtime-publishes-thing-added-event
  (let [reg (registry/make-registry)
        bus (events/make-bus 32)
        sub-ch (events/subscribe! bus :thing/added 8)]
    (runtime/apply-transition! reg bus no-op-effect transition/add-thing (thing/make-thing "t1" :sensor))
    (let [ev (await-event sub-ch)]
      (is (some? ev))
      (is (= :thing/added (:event/type ev)))
      (is (= "ThingAddedEvent" (:event/name ev)))
      (is (= "openhab/things/t1/added" (:event/topic ev))))
    (events/unsubscribe! bus :thing/added sub-ch)))

(deftest runtime-publishes-item-state-events-with-keyword-type-and-topic
  (let [reg (registry/make-registry)
        bus (events/make-bus 32)
        sub-ch (events/subscribe! bus :item/state-changed 8)
        thing (-> (thing/make-thing "dev-1" :fan)
                  (assoc :channels {:fan-speed {:access :rw :channel-type :number}})
                  (assoc-in [:runtime :status] (thing/status :online))
                  (assoc-in [:runtime :reported] {:fan-speed 3}))
        itm (item/make-item "FanSpeed" "Number")
        profiles (profile/make-registry)]
    (runtime/apply-transition! reg bus no-op-effect transition/add-thing thing)
    (runtime/apply-transition! reg bus no-op-effect transition/add-item itm)
    (runtime/apply-transition! reg bus no-op-effect transition/add-link profiles (link/make-link "FanSpeed" "dev-1" :fan-speed) java.time.Instant/EPOCH)
    (let [ev (await-event sub-ch)]
      (is (some? ev))
      (is (= :item/state-changed (:event/type ev)))
      (is (= "ItemStateChangedEvent" (:event/name ev)))
      (is (= "openhab/items/FanSpeed/statechanged" (:event/topic ev)))
      (is (= 3 (:state ev))))
    (events/unsubscribe! bus :item/state-changed sub-ch)))

(deftest exact-topic-subscriptions-still-work
  (let [reg (registry/make-registry)
        bus (events/make-bus 32)
        topic "openhab/things/t1/added"
        sub-ch (events/subscribe-topic! bus topic 8)]
    (runtime/apply-transition! reg bus no-op-effect transition/add-thing (thing/make-thing "t1" :sensor))
    (let [ev (await-event sub-ch)]
      (is (some? ev))
      (is (= topic (:event/topic ev)))
      (is (= :thing/added (:event/type ev))))
    (events/unsubscribe-topic! bus topic sub-ch)))



(deftest command-failed-events-have-name-and-topic
  (let [bus    (events/make-bus 32)
        sub-ch (events/subscribe! bus :command/failed 8)]
    (events/publish! bus {:event/type :command/failed
                          :thing-id "dev-1"
                          :command-id "cmd-1"
                          :channel-ids #{:fan-speed}})
    (let [ev (await-event sub-ch)]
      (is (= :command/failed (:event/type ev)))
      (is (= "CommandFailedEvent" (:event/name ev)))
      (is (= "openhab/things/dev-1/commands/cmd-1/failed" (:event/topic ev))))
    (events/unsubscribe! bus :command/failed sub-ch)))

(deftest all-event-subscriptions-receive-decorated-events
  (let [bus (events/make-bus 32)
        sub-ch (events/subscribe-all! bus 8)]
    (events/publish! bus {:event/type :thing/added
                          :thing-id "t1"})
    (let [ev (await-event sub-ch)]
      (is (= :thing/added (:event/type ev)))
      (is (= "ThingAddedEvent" (:event/name ev)))
      (is (= "openhab/things/t1/added" (:event/topic ev))))
    (events/unsubscribe-all! bus sub-ch)
    (events/publish! bus {:event/type :thing/added
                          :thing-id "t2"})
    (is (nil? (await-event sub-ch 50)))))
