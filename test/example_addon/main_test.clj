(ns example-addon.main-test
  "Tests for the example addon command-line entrypoint wiring."
  (:require [clojure.test :refer [deftest is]]
            [example-addon.api :as api]
            [example-addon.main :as main]
            [example-addon.system :as system]
            [openhab.api.server :as server]))

(deftest parse-port-falls-back-for-missing-or-invalid-values
  (doseq [value [nil "" "   " "abc" "0" "-1" "65536"]]
    (is (= 8080 (main/parse-port value)))))

(deftest parse-port-accepts-valid-values
  (is (= 9090 (main/parse-port "9090")))
  (is (= 8081 (main/parse-port " 8081 ")))
  (is (= 8082 (main/parse-port 8082)))
  (is (= 65535 (main/parse-port "65535"))))

(deftest make-stop-fn-stops-http-before-addon
  (let [calls (atom [])]
    (with-redefs [server/stop! (fn [srv]
                                 (swap! calls conj [:server srv]))
                  system/stop! (fn [ctx]
                                 (swap! calls conj [:system ctx]))]
      ((main/make-stop-fn ::server ::ctx))
      (is (= [[:server ::server]
              [:system ::ctx]]
             @calls)))))

(deftest start-demo-seeds-state-and-starts-local-server
  (let [calls (atom [])]
    (with-redefs [api/reset-state! (fn [state]
                                     (swap! calls conj [:reset state]))
                  system/start! (fn []
                                  (swap! calls conj [:system-start])
                                  ::ctx)
                  server/start! (fn [ctx opts]
                                  (swap! calls conj [:server-start ctx opts])
                                  ::server)]
      (let [demo (main/start-demo! {:port 9090})]
        (is (= ::ctx (:ctx demo)))
        (is (= ::server (:server demo)))
        (is (ifn? (:stop! demo)))
        (is (= [[:reset {"ap-1" {:fan-speed 3
                                  :temp 21
                                  :mode "auto"}}]
                [:system-start]
                [:server-start ::ctx {:ip "127.0.0.1" :port 9090}]]
               @calls))))))

(deftest start-demo-stops-addon-if-server-start-fails
  (let [calls (atom [])]
    (with-redefs [api/reset-state! (fn [_state]
                                     (swap! calls conj :reset))
                  system/start! (fn []
                                  (swap! calls conj :system-start)
                                  ::ctx)
                  system/stop! (fn [ctx]
                                 (swap! calls conj [:system-stop ctx]))
                  server/start! (fn [_ctx _opts]
                                  (swap! calls conj :server-start)
                                  (throw (ex-info "boom" {})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"boom"
                            (main/start-demo! {:port 9090})))
      (is (= [:reset
              :system-start
              :server-start
              [:system-stop ::ctx]]
             @calls)))))
