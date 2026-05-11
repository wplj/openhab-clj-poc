(ns openhab.bridge
  "Bridge lifecycle helpers over the transition/runtime boundary.

   A bridge is topology and shared connectivity. Child device polling is handled
   by openhab.reporting so bridge code does not become a Java-style base class."
  (:require [openhab.registry :as registry]
            [openhab.reporting :as reporting]
            [openhab.runtime :as runtime]
            [openhab.thing :as thing]
            [openhab.transition :as transition])
  (:import [java.time Instant]))

(defn add-child!
  "Registers child-thing under bridge-id through the runtime transition path."
  [{:keys [registry bus effect-dispatcher]} bridge-id child-thing]
  (runtime/apply-transition! registry
                             bus
                             effect-dispatcher
                             transition/add-thing
                             (assoc child-thing :bridge-id bridge-id)))

(defn remove-child!
  "Unregisters child-id only if it currently belongs to bridge-id."
  [{:keys [registry bus effect-dispatcher]} bridge-id child-id]
  (runtime/apply-transition! registry
                             bus
                             effect-dispatcher
                             transition/remove-child
                             bridge-id
                             child-id
                             (Instant/now)))

(defn get-children
  "Returns child Thing maps for bridge-id."
  [{:keys [registry]} bridge-id]
  (registry/children-of registry bridge-id))

(defn propagate-status!
  "Sets bridge status; :offline cascades to children through the transition layer."
  [{:keys [registry bus effect-dispatcher]} bridge-id status]
  (runtime/apply-transition! registry
                             bus
                             effect-dispatcher
                             transition/set-bridge-status
                             bridge-id
                             status))

(defn start-own-channel-polling!
  "Starts polling for channels that belong to the bridge Thing itself.
   Child device polling should use openhab.reporting/start-channel-polling! with the child thing-id."
  [context {:keys [bridge-id fetch-fn interval-ms initial-fetch?]
            :or {interval-ms 60000
                 initial-fetch? false}}]
  (reporting/start-channel-polling! context
                                    {:thing-id bridge-id
                                     :fetch-fn fetch-fn
                                     :interval-ms interval-ms
                                     :initial-fetch? initial-fetch?}))

(defn stop!
  "Stops bridge polling and sets the bridge offline."
  [{:keys [registry bus effect-dispatcher]} bridge-id poll-handle]
  (reporting/stop-channel-polling! poll-handle)
  (runtime/apply-transition! registry
                             bus
                             effect-dispatcher
                             transition/set-bridge-status
                             bridge-id
                             (thing/status :offline)))
