(ns openhab.polling-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [openhab.polling :as polling])
  (:import [java.util.logging Level Logger]))

(defn- with-polling-logs-suppressed [f]
  (let [logger (Logger/getLogger "openhab.polling")
        old-level (.getLevel logger)]
    (try
      (.setLevel logger Level/OFF)
      (f)
      (finally
        (.setLevel logger old-level)))))

(use-fixtures :each with-polling-logs-suppressed)

(defn- make-counter
  "Returns an atom-backed zero-arg fn that increments on each call."
  []
  (let [n (atom 0)]
    [n (fn [] (swap! n inc))]))

(deftest on-success-called-with-fetch-result
  (let [received (atom nil)
        handle   (polling/start! {:fetch-fn    (fn [] :the-data)
                                  :on-success  (fn [v] (reset! received v))
                                  :on-error    (fn [_])
                                  :interval-ms 60000})]
    (Thread/sleep 200)
    (polling/stop! handle)
    (is (= :the-data @received))))

(deftest on-error-called-when-fetch-throws
  (let [received (atom nil)
        handle   (polling/start! {:fetch-fn    (fn [] (throw (ex-info "boom" {})))
                                  :on-success  (fn [_])
                                  :on-error    (fn [ex] (reset! received (ex-message ex)))
                                  :interval-ms 60000})]
    (Thread/sleep 200)
    (polling/stop! handle)
    (is (= "boom" @received))))

(deftest fetch-called-multiple-times
  (let [[counter fetch-fn] (make-counter)
        handle (polling/start! {:fetch-fn    fetch-fn
                                :on-success  (fn [_])
                                :on-error    (fn [_])
                                :interval-ms 80})]
    (Thread/sleep 300)
    (polling/stop! handle)
    (is (>= @counter 3) "should have polled at least 3 times in 300ms with 80ms interval")))

(deftest on-success-exceptions-do-not-stop-polling
  (let [success-calls (atom 0)
        handle (polling/start! {:fetch-fn    (fn [] :ok)
                                :on-success  (fn [_]
                                               (when (= 1 (swap! success-calls inc))
                                                 (throw (ex-info "callback failed" {}))))
                                :on-error    (fn [_])
                                :interval-ms 50})]
    (Thread/sleep 180)
    (polling/stop! handle)
    (is (>= @success-calls 2))))

(deftest on-error-exceptions-do-not-stop-polling
  (let [error-calls (atom 0)
        handle (polling/start! {:fetch-fn    (fn [] (throw (ex-info "fetch failed" {})))
                                :on-success  (fn [_])
                                :on-error    (fn [_]
                                               (when (= 1 (swap! error-calls inc))
                                                 (throw (ex-info "error callback failed" {}))))
                                :interval-ms 50})]
    (Thread/sleep 180)
    (polling/stop! handle)
    (is (>= @error-calls 2))))

(deftest stop-returns-without-waiting-for-hung-fetch
  (let [started?      (promise)
        success-called (atom false)
        error-called   (atom false)
        handle         (polling/start! {:fetch-fn    (fn []
                                                       (deliver started? true)
                                                       (Thread/sleep 300)
                                                       :done)
                                        :on-success  (fn [_] (reset! success-called true))
                                        :on-error    (fn [_] (reset! error-called true))
                                        :interval-ms 60000})]
    @started?
    (let [started-at (System/nanoTime)]
      (polling/stop! handle)
      (let [elapsed-ms (/ (- (System/nanoTime) started-at) 1000000.0)]
        (is (< elapsed-ms 150.0)
            (str "stop! should return promptly; elapsed " elapsed-ms "ms"))))
    (Thread/sleep 350)
    (is (false? @success-called) "late success callbacks must be suppressed after stop")
    (is (false? @error-called) "late error callbacks must be suppressed after stop")))

(deftest stop-twice-does-not-throw
  (let [handle (polling/start! {:fetch-fn    (fn [] nil)
                                :on-success  (fn [_])
                                :on-error    (fn [_])
                                :interval-ms 60000})]
    (polling/stop! handle)
    (is (nil? (polling/stop! handle)))))