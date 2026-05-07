(ns openhab.api.server-test
  "Tests for HTTP server lifecycle wiring without binding a real socket."
  (:require [clojure.test :refer [deftest is]]
            [org.httpkit.server :as http-kit]
            [openhab.api.http :as http]
            [openhab.api.server :as server]))

(deftest start-builds-handler-and-uses-loopback-defaults
  (let [ctx {:registry ::registry}
        captured (atom {})
        stop-count (atom 0)]
    (with-redefs [http/handler (fn [ctx']
                                 (swap! captured assoc :ctx ctx')
                                 ::handler)
                  http-kit/run-server (fn [handler opts]
                                        (swap! captured assoc
                                               :handler handler
                                               :opts opts)
                                        (fn []
                                          (swap! stop-count inc)))]
      (let [handle (server/start! ctx)]
        (is (= ctx (:ctx @captured)))
        (is (= ::handler (:handler @captured)))
        (is (= {:ip "127.0.0.1" :port 8080} (:opts @captured)))
        (is (= 8080 (:port handle)))
        (server/stop! handle)
        ((:stop! handle))
        (is (= 1 @stop-count))))))

(deftest start-passes-custom-http-kit-opts-through
  (let [captured (atom nil)]
    (with-redefs [http/handler (constantly ::handler)
                  http-kit/run-server (fn [_handler opts]
                                        (reset! captured opts)
                                        (constantly nil))]
      (let [handle (server/start! {} {:ip "0.0.0.0"
                                      :port 9090
                                      :thread 4})]
        (is (= {:ip "0.0.0.0"
                :port 9090
                :thread 4}
               @captured))
        (is (= 9090 (:port handle)))))))
