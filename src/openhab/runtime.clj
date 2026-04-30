(ns openhab.runtime
  (:require [clojure.tools.logging :as log]
            [openhab.events :as events]))

(def ^:dynamic *dispatching-effects?* false)

(defn apply-transition!
  "Applies a pure transition fn to the registry atom.
   Publishes events and dispatches effects after the swap! completes."
  [registry bus effect-dispatcher transition-fn & args]
  (let [result* (volatile! nil)]
    ;; swap! may retry the transition fn. vreset! keeps only the last successful result.
    (swap! registry
           (fn [state]
             (let [transition-result (apply transition-fn state args)]
               (when (and *dispatching-effects?* (seq (:effects transition-result)))
                 (throw (ex-info "Effectful transition during effect dispatch is forbidden"
                                 {:effect-count (count (:effects transition-result))})))
               (vreset! result* transition-result)
               (:state transition-result))))
    ;; Events/effects are outside swap!: transition fns are pure and may be retried.
    (doseq [event (:events @result*)]
      (when bus
        (events/publish! bus event)))
    (let [effects (:effects @result*)]
      (when (seq effects)
        (when-not effect-dispatcher
          (throw (ex-info "Missing :effect-dispatcher for effectful transition"
                          {:effect-count (count effects)})))
        (doseq [effect effects]
          (try
            (binding [*dispatching-effects?* true]
              (effect-dispatcher effect))
            (catch Exception ex
              (log/error ex "Effect dispatch failed" {:effect effect}))))))
    @result*))