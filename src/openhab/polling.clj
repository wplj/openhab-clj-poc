(ns openhab.polling
  (:require [clojure.core.async :refer [<!! alts! chan close! go-loop io-thread timeout]]
            [clojure.tools.logging :as log]))

(defn- fetch-on-io-thread
  [fetch-fn]
  (io-thread
    (try
      {:status :ok
       :value (fetch-fn)}
      (catch Exception ex
        {:status :error
         :error ex}))))

(defn- deliver-result!
  [on-success on-error result]
  (try
    (case (:status result)
      :ok (on-success (:value result))
      :error (on-error (:error result)))
    (catch Exception ex
      (log/error ex "Polling callback failed" {:result-status (:status result)}))))

(defn start!
  "Starts a generic polling loop. fetch-fn runs on core.async/io-thread so blocking I/O does not
   occupy a go block thread. on-success and on-error are framework/addon wiring hooks.

   Callback exceptions are logged and contained so polling continues. stop! requests shutdown
   promptly and suppresses late callback delivery from any in-flight fetch that completes after
   stop is requested. initial-delay-ms delays only the first fetch; the default is immediate."
  [{:keys [fetch-fn on-success on-error interval-ms]
    :or {interval-ms 60000}
    :as opts}]
  (let [stop-ch (chan)
        done-ch (chan)
        initial-delay-ms (long (or (:initial-delay-ms opts) 0))]
    (go-loop [delay-ms initial-delay-ms]
      (let [[_ delay-source] (if (pos? delay-ms)
                               (alts! [stop-ch (timeout delay-ms)] :priority true)
                               [nil nil])]
        (if (= delay-source stop-ch)
          (close! done-ch)
          (let [fetch-ch (fetch-on-io-thread fetch-fn)
                [result source-ch] (alts! [stop-ch fetch-ch] :priority true)]
            (if (= source-ch stop-ch)
              (close! done-ch)
              (do
                (deliver-result! on-success on-error result)
                (let [[_ wait-source] (alts! [stop-ch (timeout interval-ms)] :priority true)]
                  ;; Stop is first in each prioritized alts!, so shutdown wins ready-channel races.
                  (if (= wait-source stop-ch)
                    (close! done-ch)
                    (recur 0)))))))))
    {:stop-ch stop-ch
     :done-ch done-ch}))

(defn stop!
  "Stops the polling loop and waits only for the loop itself to exit.
   It does not wait for a hung fetch-fn thread to finish; addons must still enforce
   bounded I/O timeouts in fetch-fn. Late fetch results are discarded after stop."
  [{:keys [stop-ch done-ch]}]
  (close! stop-ch)
  (<!! done-ch)
  nil)
