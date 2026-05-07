(ns openhab.api.view-test
  "Tests for API-safe value conversion and public read-model serialization."
  (:require [clojure.test :refer [deftest is]]
            [openhab.api.view :as view]
            [openhab.item :as item]
            [openhab.link :as link]
            [openhab.query :as query]
            [openhab.registry :as registry]
            [openhab.thing :as thing])
  (:import [java.time Instant]
           [java.util UUID]))

(def changed-at (Instant/parse "2026-05-06T10:15:30Z"))

(defn- sample-state []
  (let [bridge (assoc (thing/make-bridge "ap-bridge-1" :ap-bridge)
                      :runtime {:status (thing/status :online
                                                     :description "Bridge connected")
                                :reported {}
                                :desired {}})
        device (assoc (thing/make-thing "ap-1" :air-purifier)
                      :bridge-id "ap-bridge-1"
                      :properties {:vendor "Acme"}
                      :config {:refresh-ms 60000}
                      :channels {:fan-speed {:access :rw :channel-type :number}
                                 :temp      {:access :ro :channel-type :number}
                                 :mode      {:access :rw :channel-type :string}}
                      :runtime {:status (thing/status :online :detail :none)
                                :reported {:fan-speed 3
                                           :temp 21}
                                :desired {:fan-speed {:value 5
                                                      :age 0
                                                      :command-id "cmd-1"}}})
        fan-item (assoc (item/make-item "AP_FanSpeed" "Number")
                        :label "Fan speed"
                        :metadata {"ui" {:value :visible
                                         :config {:order 1}}}
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

(deftest api-value-converts-edn-to-json-safe-values
  (let [id (UUID/fromString "550e8400-e29b-41d4-a716-446655440000")]
    (is (= {"plain" "x"
            "kw" "online"
            "namespaced" "openhab/send-command"
            "nested" [{"a" 1} "fan-speed"]
            "set" ["a" "b"]
            "symbol" "openhab.api.view-test/example"
            "instant" "2026-05-06T10:15:30Z"
            "uuid" "550e8400-e29b-41d4-a716-446655440000"}
           (view/api-value {:plain "x"
                            :kw :online
                            :namespaced :openhab/send-command
                            :nested [{:a 1} :fan-speed]
                            :set #{:b :a}
                            :symbol 'openhab.api.view-test/example
                            :instant changed-at
                            :uuid id})))))

(deftest api-value-rejects-unsupported-objects
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Unsupported API value"
                        (view/api-value (Object.)))))

(deftest api-value-rejects-non-json-numbers
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Unsupported API number"
                        (view/api-value 1/3)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Unsupported API number"
                        (view/api-value Double/NaN)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Unsupported API number"
                        (view/api-value Double/POSITIVE_INFINITY))))

(deftest api-value-rejects-non-json-floats
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Unsupported API number"
                        (view/api-value Float/NaN)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Unsupported API number"
                        (view/api-value Float/POSITIVE_INFINITY))))

(deftest link-view-serializes-channel-id
  (is (= {:item-name "AP_FanSpeed"
          :thing-id "ap-1"
          :channel-id "fan-speed"
          :profile "system:default"}
         (view/link {:item-name "AP_FanSpeed"
                     :thing-id "ap-1"
                     :channel-id :fan-speed
                     :profile "system:default"}))))

(deftest item-view-serializes-links-state-and-metadata
  (let [state (sample-state)
        item-view (-> state
                      (query/item "AP_FanSpeed")
                      view/item)]
    (is (= {:item-name "AP_FanSpeed"
            :item-type "Number"
            :label "Fan speed"
            :tags []
            :group-names []
            :links [{:item-name "AP_FanSpeed"
                     :thing-id "ap-1"
                     :channel-id "fan-speed"
                     :profile "system:default"}]
            :metadata {"ui" {"value" "visible"
                             "config" {"order" 1}}}
            :state 5
            :state-type "number"
            :last-change "2026-05-06T10:15:30Z"}
           item-view))))

(deftest item-view-preserves-explicit-nil-optional-fields
  (is (= {:item-name "NilItem"
          :item-type "String"
          :tags []
          :group-names []
          :links []
          :label nil
          :category nil}
         (view/item {:item-name "NilItem"
                     :item-type "String"
                     :tags []
                     :group-names []
                     :links []
                     :label nil
                     :category nil}))))

(deftest thing-view-serializes-status-channels-and-values
  (let [state (sample-state)
        thing-view (-> state
                       (query/thing "ap-1")
                       view/thing)]
    (is (= {:thing-id "ap-1"
            :thing-type "air-purifier"
            :bridge-id "ap-bridge-1"
            :bridge? false
            :properties {"vendor" "Acme"}
            :config {"refresh-ms" 60000}
            :status {:value "online"
                     :detail "none"}
            :channels [{:access "rw"
                        :channel-type "number"
                        :channel-id "fan-speed"
                        :linked-items ["AP_FanSpeed"]
                        :pending? true
                        :reported-value 3
                        :desired-value 5
                        :effective-value 5}
                       {:access "rw"
                        :channel-type "string"
                        :channel-id "mode"
                        :linked-items []
                        :pending? false}
                       {:access "ro"
                        :channel-type "number"
                        :channel-id "temp"
                        :linked-items ["AP_Temp"]
                        :pending? false
                        :reported-value 21
                        :effective-value 21}]}
           thing-view))))

(deftest bridge-thing-view-serializes-children-and-status-description
  (let [state (sample-state)
        bridge-view (-> state
                        (query/thing "ap-bridge-1")
                        view/thing)]
    (is (= {:thing-id "ap-bridge-1"
            :thing-type "ap-bridge"
            :bridge? true
            :status {:value "online"
                     :description "Bridge connected"}
            :channels []
            :child-thing-ids ["ap-1"]}
           bridge-view))))

(deftest thing-view-preserves-explicit-nil-bridge-id
  (is (= {:thing-id "dev-1"
          :thing-type "sensor"
          :bridge? false
          :bridge-id nil
          :status {:value "initializing"}
          :channels []}
         (view/thing {:thing-id "dev-1"
                      :thing-type :sensor
                      :bridge? false
                      :bridge-id nil
                      :status {:value :initializing}
                      :channels []}))))

(deftest system-snapshot-view-serializes-query-snapshot
  (let [api-view (view/system-snapshot (query/system-snapshot (sample-state)))]
    (is (= ["ap-1" "ap-bridge-1"] (mapv :thing-id (:things api-view))))
    (is (= ["AP_FanSpeed" "AP_Temp"] (mapv :item-name (:items api-view))))
    (is (= ["fan-speed" "temp"] (mapv :channel-id (:links api-view))))))
