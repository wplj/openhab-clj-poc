(ns openhab.effects
  "Effect dispatch helpers for work that must happen after state commits.

   Transitions describe effects as data. Runtime dispatches them here so device
   I/O cannot leak into retryable swap! functions."
  (:require [clojure.tools.logging :as log]
            [openhab.registry :as registry]
            [openhab.runtime :as runtime]
            [openhab.transition :as transition])
  (:import [java.time Instant]))

(def core-effect-kinds
  #{:openhab/send-command})

(defn assert-handler-coverage!
  "Ensures handler-map contains handlers for every required effect kind.
   Defaults to the core effect kinds emitted by the framework."
  ([handler-map]
   (assert-handler-coverage! handler-map core-effect-kinds))
  ([handler-map required-kinds]
   (let [required (set required-kinds)
         missing  (->> required
                       (remove #(contains? handler-map %))
                       sort
                       vec)]
     (when (seq missing)
       (throw (ex-info "Missing effect handlers"
                       {:missing missing
                        :required required})))
     handler-map)))

(defn dispatch!
  "Dispatches one effect through handler-map with the supplied context."
  [handler-map context effect]
  (if-let [handler (get handler-map (:kind effect))]
    (handler effect context)
    (throw (ex-info "No handler for effect kind" {:effect effect}))))

(defn make-dispatcher
  "Returns a single-arg effect dispatcher fn that closes over handler-map and context."
  [handler-map context]
  (fn [effect]
    (dispatch! handler-map context effect)))

(defn send-command-handler
  "Returns an effect handler for :openhab/send-command effects.
   send-fn is called with the current Thing and the effect map.
   send-fn must throw on failure; any return value indicates success."
  [send-fn]
  (fn [effect {:keys [registry bus effect-dispatcher profiles]}]
    ;; The Thing is looked up at dispatch time because the committed state may have moved on.
    (let [thing (registry/get-thing registry (:thing-id effect))]
      (try
        (when-not thing
          (throw (ex-info "Thing removed before effect dispatch" {:thing-id (:thing-id effect)})))
        (send-fn thing effect)
        (catch Exception ex
          (log/warn ex "Command effect failed; applying corrective transition" {:effect effect})
          (runtime/apply-transition! registry
                                     bus
                                     effect-dispatcher
                                     transition/command-failed
                                     profiles
                                     (:thing-id effect)
                                     (:command-id effect)
                                     (Instant/now)))))))
