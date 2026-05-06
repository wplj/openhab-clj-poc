(ns openhab.api.http-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [openhab.api.http :as http]
            [openhab.effects :as effects]
            [openhab.events :as events]
            [openhab.item :as item]
            [openhab.link :as link]
            [openhab.profile :as profile]
            [openhab.registry :as registry]
            [openhab.thing :as thing]))

(def expected-json-content-type "application/json; charset=utf-8")

(defn- sample-state []
  (let [bridge (thing/make-bridge "ap-bridge-1" :ap-bridge)
        device (assoc (thing/make-thing "ap-1" :air-purifier)
                      :bridge-id "ap-bridge-1"
                      :channels {:fan-speed {:access :rw :channel-type :number}
                                 :temp      {:access :ro :channel-type :number}}
                      :runtime {:status (thing/status :online)
                                :reported {:fan-speed 3
                                           :temp 21}
                                :desired {}})
        fan-item (assoc (item/make-item "AP_FanSpeed" "Number")
                        :state 3
                        :state-type :number)
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

(defn- sample-profiles []
  (-> (profile/make-registry)
      (profile/register-codec [:air-purifier :fan-speed]
                              {:to-state (fn [v] {:state v :state-type :number})
                               :from-state identity})
      (profile/register-codec [:air-purifier :temp]
                              {:to-state (fn [v] {:state v :state-type :number})
                               :from-state identity})))

(defn- request
  ([handler method uri]
   (request handler method uri nil))
  ([handler method uri body]
   (handler (cond-> {:request-method method
                     :uri uri}
              (some? body) (assoc :body (java.io.ByteArrayInputStream.
                                         (.getBytes body java.nio.charset.StandardCharsets/UTF_8)))))))

(defn- body [response]
  (json/parse-string (:body response) true))

(defn- registry-ctx [state]
  {:registry (atom state)})

(defn- command-ctx [state send-fn]
  (let [reg (atom state)
        bus (events/make-bus)
        ctx {:registry reg
             :bus bus
             :profiles (sample-profiles)}
        dispatch-effect (effects/make-dispatcher
                         {:openhab/send-command (effects/send-command-handler send-fn)}
                         ctx)]
    (assoc ctx :effect-dispatcher dispatch-effect)))

(deftest get-system-returns-json-snapshot
  (let [handler (http/handler (registry-ctx (sample-state)))
        response (request handler :get "/api/system")]
    (is (= 200 (:status response)))
    (is (= expected-json-content-type (get-in response [:headers "Content-Type"])))
    (is (= ["ap-1" "ap-bridge-1"]
           (mapv :thing-id (:things (body response)))))
    (is (= ["AP_FanSpeed" "AP_Temp"]
           (mapv :item-name (:items (body response)))))
    (is (= ["fan-speed" "temp"]
           (mapv :channel-id (:links (body response)))))))

(deftest get-things-and-items-return-json-lists
  (let [handler (http/handler (registry-ctx (sample-state)))]
    (testing "things"
      (let [response (request handler :get "/api/things")]
        (is (= 200 (:status response)))
        (is (= ["ap-1" "ap-bridge-1"]
               (mapv :thing-id (body response))))))
    (testing "items"
      (let [response (request handler :get "/api/items")]
        (is (= 200 (:status response)))
        (is (= ["AP_FanSpeed" "AP_Temp"]
               (mapv :item-name (body response))))))))

(deftest get-single-thing-and-item-return-json-resources
  (let [handler (http/handler (registry-ctx (sample-state)))]
    (testing "thing"
      (let [response (request handler :get "/api/things/ap-1")
            decoded (body response)]
        (is (= 200 (:status response)))
        (is (= "ap-1" (:thing-id decoded)))
        (is (= "online" (get-in decoded [:status :value])))
        (is (= ["fan-speed" "temp"] (mapv :channel-id (:channels decoded))))))
    (testing "item"
      (let [response (request handler :get "/api/items/AP_FanSpeed")
            decoded (body response)]
        (is (= 200 (:status response)))
        (is (= "AP_FanSpeed" (:item-name decoded)))
        (is (= 3 (:state decoded)))
        (is (= "number" (:state-type decoded)))))))

(deftest unknown-resources-and-routes-return-json-404
  (let [handler (http/handler (registry-ctx (sample-state)))]
    (doseq [uri ["/api/things/missing"
                 "/api/items/Missing"
                 "/api/nope"]]
      (let [response (request handler :get uri)]
        (is (= 404 (:status response)))
        (is (= expected-json-content-type (get-in response [:headers "Content-Type"])))
        (is (= {:error "not found"} (body response)))))))

(deftest unsupported-methods-return-json-405
  (let [handler (http/handler (registry-ctx (sample-state)))
        response (request handler :post "/api/things")]
    (is (= 405 (:status response)))
    (is (= expected-json-content-type (get-in response [:headers "Content-Type"])))
    (is (= {:error "method not allowed"} (body response)))))

(deftest post-item-command-accepts-command-and-dispatches-effect
  (let [sent (atom [])
        ctx (command-ctx (sample-state)
                         (fn [_thing effect]
                           (swap! sent conj effect)))
        handler (http/handler ctx)
        response (request handler :post "/api/items/AP_FanSpeed/command" "{\"value\":5}")]
    (is (= 202 (:status response)))
    (is (= {:accepted true} (body response)))
    (is (= [{:kind :openhab/send-command
             :thing-id "ap-1"
             :commands {:fan-speed 5}}]
           (mapv #(select-keys % [:kind :thing-id :commands]) @sent)))
    (is (= 5 (get-in @(:registry ctx) [:items "AP_FanSpeed" :state])))))

(deftest post-item-command-rejects-invalid-bodies
  (let [handler (http/handler (command-ctx (sample-state) (fn [_thing _effect])))]
    (doseq [[request-body expected-error] [[nil "empty body"]
                                           ["" "empty body"]
                                           ["{" "malformed json"]
                                           ["[]" "expected json object"]
                                           ["{}" "missing or null value"]
                                           ["{\"value\":null}" "missing or null value"]]]
      (let [response (request handler :post "/api/items/AP_FanSpeed/command" request-body)]
        (is (= 400 (:status response)))
        (is (= {:error expected-error} (body response)))))))

(deftest post-item-command-maps-domain-failures
  (testing "missing item is a 404"
    (let [handler (http/handler (command-ctx (sample-state) (fn [_thing _effect])))
          response (request handler :post "/api/items/Missing/command" "{\"value\":5}")]
      (is (= 404 (:status response)))
      (is (= {:error "not found"} (body response)))))
  (testing "read-only channel is a conflict with reason"
    (let [handler (http/handler (command-ctx (sample-state) (fn [_thing _effect])))
          response (request handler :post "/api/items/AP_Temp/command" "{\"value\":99}")]
      (is (= 409 (:status response)))
      (is (= {:error "channel-read-only"} (body response)))))
  (testing "other domain failures fall through to conflict"
    (let [state (update-in (sample-state) [:things "ap-1" :runtime :status] (constantly (thing/status :offline)))
          handler (http/handler (command-ctx state (fn [_thing _effect])))
          response (request handler :post "/api/items/AP_FanSpeed/command" "{\"value\":5}")]
      (is (= 409 (:status response)))
      (is (= {:error "thing-offline"} (body response))))))

(deftest handler-reads-live-registry-on-each-request
  (let [reg (registry/make-registry)
        handler (http/handler {:registry reg})]
    (is (= [] (body (request handler :get "/api/items"))))
    (swap! reg registry/put-item (item/make-item "LateItem" "String"))
    (is (= ["LateItem"]
           (mapv :item-name (body (request handler :get "/api/items")))))))
