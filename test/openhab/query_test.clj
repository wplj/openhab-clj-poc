(ns openhab.query-test
  (:require [clojure.test :refer [deftest is testing]]
            [openhab.item :as item]
            [openhab.link :as link]
            [openhab.query :as query]
            [openhab.registry :as registry]
            [openhab.thing :as thing])
  (:import [java.time Instant]))

(def changed-at (Instant/parse "2026-05-06T10:15:30Z"))

(defn- sample-state []
  (let [bridge (thing/make-bridge "ap-bridge-1" :ap-bridge)
        device (assoc (thing/make-thing "ap-1" :air-purifier)
                      :bridge-id "ap-bridge-1"
                      :channels {:fan-speed {:access :rw :channel-type :number}
                                 :temp      {:access :ro :channel-type :number}}
                      :runtime {:status (thing/status :online)
                                :reported {:fan-speed 3
                                           :temp 21}
                                :desired {:fan-speed {:value 5
                                                      :age 0
                                                      :command-id "cmd-1"}}})
        fan-item (assoc (item/make-item "AP_FanSpeed" "Number")
                        :state 5
                        :state-type :number
                        :last-change changed-at)
        temp-item (assoc (item/make-item "AP_Temp" "Number")
                         :state 21
                         :state-type :number)]
    (-> (registry/empty-state)
        (registry/put-thing bridge)
        (registry/put-thing device)
        (registry/put-item fan-item)
        (registry/put-item temp-item)
        (registry/put-link (link/make-link "AP_FanSpeed" "ap-1" :fan-speed))
        (registry/put-link (link/make-link "AP_Temp" "ap-1" :temp)))))

(deftest empty-registry-queries-return-stable-empty-shapes
  (let [state (registry/empty-state)]
    (is (nil? (query/item state "missing")))
    (is (nil? (query/thing state "missing")))
    (is (= [] (query/items state)))
    (is (= [] (query/things state)))
    (is (= [] (query/links state)))
    (is (= {:things []
            :items []
            :links []}
           (query/system-snapshot state)))))

(deftest queries-accept-registry-atoms-and-plain-state-values
  (let [state (sample-state)
        reg (atom state)]
    (is (= (query/system-snapshot state)
           (query/system-snapshot reg)))))

(deftest item-query-returns-public-item-shape-with-links
  (let [state (sample-state)]
    (is (= {:item-name "AP_FanSpeed"
            :item-type "Number"
            :tags []
            :group-names []
            :links [{:item-name "AP_FanSpeed"
                     :thing-id "ap-1"
                     :channel-id :fan-speed
                     :profile "system:default"}]
            :state 5
            :state-type :number
            :last-change changed-at}
           (query/item state "AP_FanSpeed")))))

(deftest thing-query-hides-desired-internals-and-derives-channel-state
  (let [state (sample-state)]
    (testing "child thing"
      (is (= {:thing-id "ap-1"
              :thing-type :air-purifier
              :bridge-id "ap-bridge-1"
              :bridge? false
              :status {:value :online}
              :channels [{:access :rw
                          :channel-type :number
                          :channel-id :fan-speed
                          :linked-items ["AP_FanSpeed"]
                          :pending? true
                          :reported-value 3
                          :desired-value 5
                          :effective-value 5}
                         {:access :ro
                          :channel-type :number
                          :channel-id :temp
                          :linked-items ["AP_Temp"]
                          :pending? false
                          :reported-value 21
                          :effective-value 21}]}
             (query/thing state "ap-1"))))
    (testing "bridge thing"
      (is (= {:thing-id "ap-bridge-1"
              :thing-type :ap-bridge
              :bridge? true
              :status {:value :initializing}
              :channels []
              :child-thing-ids ["ap-1"]}
             (query/thing state "ap-bridge-1"))))))

(deftest thing-query-omits-channel-values-before-first-report
  (let [state (-> (registry/empty-state)
                  (registry/put-thing
                   (assoc (thing/make-thing "dev-1" :sensor)
                          :channels {:power {:access :rw :channel-type :boolean}})))]
    (is (= {:thing-id "dev-1"
            :thing-type :sensor
            :bridge? false
            :status {:value :initializing}
            :channels [{:access :rw
                        :channel-type :boolean
                        :channel-id :power
                        :linked-items []
                        :pending? false}]}
           (query/thing state "dev-1")))))

(deftest link-queries-are-stable-and-index-backed
  (let [state (sample-state)]
    (is (= [{:item-name "AP_FanSpeed"
             :thing-id "ap-1"
             :channel-id :fan-speed
             :profile "system:default"}
            {:item-name "AP_Temp"
             :thing-id "ap-1"
             :channel-id :temp
             :profile "system:default"}]
           (query/links state)))
    (is (= [{:item-name "AP_FanSpeed"
             :thing-id "ap-1"
             :channel-id :fan-speed
             :profile "system:default"}]
           (query/links-for-item state "AP_FanSpeed")))
    (is (= [{:item-name "AP_Temp"
             :thing-id "ap-1"
             :channel-id :temp
             :profile "system:default"}]
           (query/links-for-channel state "ap-1" :temp)))))
