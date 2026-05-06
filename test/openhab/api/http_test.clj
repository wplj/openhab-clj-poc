(ns openhab.api.http-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [openhab.api.http :as http]
            [openhab.item :as item]
            [openhab.link :as link]
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

(defn- request [handler method uri]
  (handler {:request-method method
            :uri uri}))

(defn- body [response]
  (json/parse-string (:body response) true))

(defn- registry-ctx [state]
  {:registry (atom state)})

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

(deftest handler-reads-live-registry-on-each-request
  (let [reg (registry/make-registry)
        handler (http/handler {:registry reg})]
    (is (= [] (body (request handler :get "/api/items"))))
    (swap! reg registry/put-item (item/make-item "LateItem" "String"))
    (is (= ["LateItem"]
           (mapv :item-name (body (request handler :get "/api/items")))))))
