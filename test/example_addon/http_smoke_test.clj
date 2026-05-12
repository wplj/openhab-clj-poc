(ns example-addon.http-smoke-test
  "Smoke tests for the runnable demo through a real local HTTP server."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [example-addon.main :as main])
  (:import [java.net ServerSocket URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private client
  (HttpClient/newHttpClient))

(def ^:private demo* (atom nil))
(def ^:private base-url* (atom nil))

(defn- free-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- request-builder [path]
  (-> (HttpRequest/newBuilder (URI/create (str @base-url* path)))
      (.timeout (Duration/ofSeconds 5))
      (.header "Accept" "application/json")))

(defn- send! [request]
  (.send client request (HttpResponse$BodyHandlers/ofString)))

(defn- decode-json [response]
  (json/parse-string (.body response) true))

(defn- get-json [path]
  (let [response (send! (-> (request-builder path)
                            (.GET)
                            (.build)))]
    {:status (.statusCode response)
     :body (decode-json response)}))

(defn- post-json [path body]
  (let [response (send! (-> (request-builder path)
                            (.header "Content-Type" "application/json")
                            (.POST (HttpRequest$BodyPublishers/ofString
                                    (json/generate-string body)))
                            (.build)))]
    {:status (.statusCode response)
     :body (decode-json response)}))

(defn- with-demo-server [f]
  (let [port (free-port)
        demo (main/start-demo! {:ip "127.0.0.1" :port port})]
    (reset! demo* demo)
    (reset! base-url* (str "http://127.0.0.1:" port))
    (try
      (f)
      (finally
        ((:stop! demo))
        (reset! demo* nil)
        (reset! base-url* nil)))))

(use-fixtures :once with-demo-server)

(deftest demo-http-api-smoke
  (let [system (get-json "/api/system")]
    (is (= 200 (:status system)))
    (is (= ["ap-1" "ap-bridge-1"]
           (mapv :thing-id (get-in system [:body :things]))))
    (is (= ["AP_FanSpeed" "AP_Mode" "AP_Temp"]
           (mapv :item-name (get-in system [:body :items]))))
    (is (= ["fan-speed" "mode" "temp"]
           (mapv :channel-id (get-in system [:body :links])))))
  (let [links (get-json "/api/links")]
    (is (= 200 (:status links)))
    (is (= ["AP_FanSpeed" "AP_Mode" "AP_Temp"]
           (mapv :item-name (:body links)))))
  (let [item-links (get-json "/api/items/AP_FanSpeed/links")]
    (is (= 200 (:status item-links)))
    (is (= [{:item-name "AP_FanSpeed"
             :thing-id "ap-1"
             :channel-id "fan-speed"
             :profile "system:default"}]
           (:body item-links))))
  (let [channel-links (get-json "/api/things/ap-1/channels/fan-speed/links")]
    (is (= 200 (:status channel-links)))
    (is (= [{:item-name "AP_FanSpeed"
             :thing-id "ap-1"
             :channel-id "fan-speed"
             :profile "system:default"}]
           (:body channel-links))))
  (let [command (post-json "/api/items/AP_FanSpeed/command" {:value 5})]
    (is (= 202 (:status command)))
    (is (= {:accepted true} (:body command))))
  (let [item (get-json "/api/items/AP_FanSpeed")]
    (is (= 200 (:status item)))
    (is (= 5 (get-in item [:body :state])))))
