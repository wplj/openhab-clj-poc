(ns openhab.reporting
  "Runtime adapter from addon channel snapshots into transition calls.

   Reporting is separate from polling: polling schedules work, while reporting
   knows which Thing receives a full channel snapshot or offline status."
  (:require [openhab.polling :as polling]
            [openhab.runtime :as runtime]
            [openhab.thing :as thing]
            [openhab.transition :as transition])
  (:import [java.time Instant]))

(defn report-channels!
  "Applies a full normalized channel snapshot to thing-id through the transition path."
  [{:keys [registry bus effect-dispatcher profiles]} thing-id reported]
  (runtime/apply-transition! registry
                             bus
                             effect-dispatcher
                             transition/channels-reported
                             profiles
                             thing-id
                             reported
                             (Instant/now)))

(defn mark-offline!
  "Marks thing-id offline through the transition path."
  [{:keys [registry bus effect-dispatcher]} thing-id]
  (runtime/apply-transition! registry
                             bus
                             effect-dispatcher
                             transition/set-thing-status
                             thing-id
                             (thing/status :offline)))

(defn- fetch-and-report!
  [context thing-id fetch-fn]
  (try
    (let [transition-result (report-channels! context thing-id (fetch-fn))]
      {:ok true
       :result (:result transition-result)
       :transition transition-result})
    (catch Exception ex
      ;; Fetch failures are liveness failures, not registry failures. Preserve the transition
      ;; result while retaining the exception for callers that want startup diagnostics.
      (let [transition-result (mark-offline! context thing-id)]
        {:ok false
         :error ex
         :result (:result transition-result)
         :transition transition-result}))))

(defn start-channel-polling!
  "Starts channel snapshot reporting for one Thing.
   With :initial-fetch? true, performs one synchronous fetch before returning and delays the
   background poller by interval-ms to avoid an immediate duplicate fetch."
  [context {:keys [thing-id fetch-fn interval-ms initial-fetch?]
            :or {interval-ms 60000
                 initial-fetch? false}}]
  (let [initial-result (when initial-fetch?
                         (fetch-and-report! context thing-id fetch-fn))
        poll-handle    (polling/start!
                         {:fetch-fn fetch-fn
                          :on-success (fn [reported]
                                        (report-channels! context thing-id reported))
                          :on-error (fn [_]
                                      (mark-offline! context thing-id))
                          :interval-ms interval-ms
                          :initial-delay-ms (if initial-fetch? interval-ms 0)})]
    (cond-> poll-handle
      initial-fetch? (assoc :initial-result initial-result))))

(defn stop-channel-polling!
  "Stops a channel polling handle returned by start-channel-polling!."
  [poll-handle]
  (polling/stop! poll-handle))
