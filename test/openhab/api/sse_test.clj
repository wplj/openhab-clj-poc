(ns openhab.api.sse-test
  "Tests for SSE frame formatting and event-stream cleanup behavior."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [openhab.api.sse :as sse]
            [openhab.events :as events])
  (:import [java.time Instant]))

(defn- data-line [frame]
  (some->> (str/split-lines frame)
           (filter #(str/starts-with? % "data: "))
           first
           (drop 6)
           (apply str)))

(defn- parse-frame-data [frame]
  (json/parse-string (data-line frame) true))

(defn- wait-until [pred]
  (let [deadline (+ (System/currentTimeMillis) 500)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do
                (Thread/sleep 10)
                (recur))))))

(deftest heartbeat-frame-is-an-sse-comment
  (is (= ":\n\n" (sse/heartbeat-frame))))

(deftest event-frame-uses-event-name-and-json-safe-data
  (let [event {:event/type :item/state-changed
               :event/name "ItemStateChangedEvent"
               :event/topic "openhab/items/AP_FanSpeed/statechanged"
               :item-name "AP_FanSpeed"
               :state-type :number
               :state 5
               :changed-at (Instant/parse "2026-05-06T10:15:30Z")
               :tags #{:b :a}}
        frame (sse/event-frame event)
        data (parse-frame-data frame)]
    (is (str/starts-with? frame "event: ItemStateChangedEvent\n"))
    (is (str/ends-with? frame "\n\n"))
    (is (= "ItemStateChangedEvent" (:type data)))
    (is (= "openhab/items/AP_FanSpeed/statechanged" (:topic data)))
    (is (= "item/state-changed" (get-in data [:payload :event/type])))
    (is (= "number" (get-in data [:payload :state-type])))
    (is (= "2026-05-06T10:15:30Z" (get-in data [:payload :changed-at])))
    (is (= ["a" "b"] (get-in data [:payload :tags])))))

(deftest event-stream-sends-events-and-stops-cleanly
  (let [bus (events/make-bus 32)
        sent (atom [])
        stop! (sse/start-event-stream! bus
                                       (fn [frame]
                                         (swap! sent conj frame)
                                         true)
                                       {:heartbeat-ms nil})]
    (events/publish! bus {:event/type :thing/added
                          :thing-id "t1"})
    (is (wait-until #(= 1 (count @sent))))
    (is (= "ThingAddedEvent" (:type (parse-frame-data (first @sent)))))
    (stop!)
    (events/publish! bus {:event/type :thing/added
                          :thing-id "t2"})
    (Thread/sleep 50)
    (is (= 1 (count @sent)))))

(deftest event-stream-stops-when-send-fails
  (let [bus (events/make-bus 32)
        send-count (atom 0)]
    (sse/start-event-stream! bus
                             (fn [_frame]
                               (swap! send-count inc)
                               false)
                             {:heartbeat-ms nil})
    (events/publish! bus {:event/type :thing/added
                          :thing-id "t1"})
    (is (wait-until #(= 1 @send-count)))
    (events/publish! bus {:event/type :thing/added
                          :thing-id "t2"})
    (Thread/sleep 50)
    (is (= 1 @send-count))))

(deftest event-stream-stops-when-send-throws
  (let [bus (events/make-bus 32)
        send-count (atom 0)]
    (sse/start-event-stream! bus
                             (fn [_frame]
                               (swap! send-count inc)
                               (throw (ex-info "Send failed" {})))
                             {:heartbeat-ms nil})
    (events/publish! bus {:event/type :thing/added
                          :thing-id "t1"})
    (is (wait-until #(= 1 @send-count)))
    (events/publish! bus {:event/type :thing/added
                          :thing-id "t2"})
    (Thread/sleep 50)
    (is (= 1 @send-count))))
