(ns openhab.commands
  (:require [openhab.runtime :as runtime]
            [openhab.transition :as transition])
  (:import [java.time Instant]
           [java.util UUID]))

(defn- next-command-id []
  (str (UUID/randomUUID)))

(defn- require-effect-dispatcher
  [{:keys [effect-dispatcher]}]
  (or effect-dispatcher
      (throw (ex-info "Missing :effect-dispatcher in command context"
                      {:required-key :effect-dispatcher}))))

(defn- dispatch-channel-command!
  "Dispatches a raw channel command, bypassing profile encoding.
   value must already be a device-native channel value."
  [{:keys [registry bus profiles] :as context} {:keys [thing-id channel-id value]}]
  (let [dispatch-effect!  (require-effect-dispatcher context)
        command-id        (next-command-id)
        changed-at        (Instant/now)
        transition-result (runtime/apply-transition! registry
                                                     bus
                                                     dispatch-effect!
                                                     transition/command-accepted
                                                     profiles
                                                     command-id
                                                     [{:thing-id   thing-id
                                                       :channel-id channel-id
                                                       :value      value}]
                                                     changed-at)]
    (:result transition-result)))

(defn- dispatch-item-command!
  [{:keys [registry bus profiles] :as context} {:keys [item-name value]}]
  (let [dispatch-effect!  (require-effect-dispatcher context)
        command-id        (next-command-id)
        changed-at        (Instant/now)
        transition-result (runtime/apply-transition! registry
                                                     bus
                                                     dispatch-effect!
                                                     transition/item-command-accepted
                                                     profiles
                                                     command-id
                                                     item-name
                                                     value
                                                     changed-at)]
    (:result transition-result)))

(defn dispatch!
  "Dispatches either an item-level or direct channel-level command.
   Context keys: :registry, :bus, :profiles, :effect-dispatcher."
  [context command]
  (cond
    (:item-name command)
    (dispatch-item-command! context command)

    (and (:thing-id command) (:channel-id command))
    (dispatch-channel-command! context command)

    :else
    {:ok false :reason :invalid-command}))