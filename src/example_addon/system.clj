(ns example-addon.system
  "Executable example addon wiring the framework pieces together.

   It registers a bridge, one device Thing, Items, Links, profiles, effects, and
   polling so integration tests exercise the real framework path."
  (:require [example-addon.api :as api]
            [example-addon.device :as device]
            [openhab.commands :as commands]
            [openhab.effects :as effects]
            [openhab.events :as events]
            [openhab.item :as item]
            [openhab.link :as link]
            [openhab.profile :as profile]
            [openhab.registry :as registry]
            [openhab.reporting :as reporting]
            [openhab.runtime :as runtime]
            [openhab.thing :as thing]
            [openhab.transition :as transition])
  (:import [java.time Instant]))

(def ^:private default-opts
  {:bridge-id      "ap-bridge-1"
   :device-id      "ap-1"
   :interval-ms    10000
   :initial-fetch? true})

;; Items and their channel bindings for an air-purifier device.
(def ^:private item-specs
  [["AP_FanSpeed" "Number" :fan-speed]
   ["AP_Temp"     "Number" :temp]
   ["AP_Mode"     "String" :mode]])

(defn- assert-ok!
  [message result data]
  (when-not (get-in result [:result :ok])
    (throw (ex-info message (assoc data :result (:result result)))))
  result)

(defn- wire-items-and-links! [reg bus profiles device-id]
  (doseq [[item-name item-type _] item-specs]
    (assert-ok! "Failed to register item"
                (runtime/apply-transition! reg bus nil transition/add-item
                                           (item/make-item item-name item-type))
                {:item-name item-name}))
  (doseq [[item-name _ channel-id] item-specs]
    (assert-ok! "Failed to register link"
                (runtime/apply-transition! reg bus nil transition/add-link profiles
                                           (link/make-link item-name device-id channel-id)
                                           (Instant/now))
                {:item-name item-name :channel-id channel-id})))

(defn start!
  "Starts the example addon. Returns a context map that must be passed to stop!.

   Startup performs one synchronous initial fetch by default, so the child Thing has a known
   online/offline status and projected Item state before start! returns. Set :initial-fetch? false
   when the caller explicitly wants fully asynchronous readiness."
  ([] (start! {}))
  ([opts]
   (let [{:keys [bridge-id device-id interval-ms initial-fetch?]} (merge default-opts opts)
         reg      (registry/make-registry)
         bus      (events/make-bus)
         profiles (device/register-codecs (profile/make-registry))

         ;; The effect-dispatcher needs to be in the context passed to effect handlers,
         ;; but the dispatcher itself is constructed from that context: circular reference.
         ;; An atom breaks the cycle; the stable wrapper delegates after construction.
         effect-dispatcher* (atom nil)
         lazy-dispatcher    (fn [effect] (@effect-dispatcher* effect))
         ctx                {:registry          reg
                             :bus               bus
                             :profiles          profiles
                             :effect-dispatcher lazy-dispatcher}
         handler-map        {:openhab/send-command (effects/send-command-handler api/send!)}
         _                  (effects/assert-handler-coverage! handler-map)
         dispatcher         (effects/make-dispatcher handler-map ctx)]

     (reset! effect-dispatcher* dispatcher)

     (assert-ok! "Failed to register bridge"
                 (runtime/apply-transition! reg bus dispatcher transition/add-thing
                                            (thing/make-bridge bridge-id :ap-bridge))
                 {:bridge-id bridge-id})
     (assert-ok! "Failed to register device"
                 (runtime/apply-transition! reg bus dispatcher transition/add-thing
                                            (device/make-device device-id bridge-id))
                 {:device-id device-id :bridge-id bridge-id})

     (wire-items-and-links! reg bus profiles device-id)

     (let [poll-handle (reporting/start-channel-polling!
                         ctx
                         {:thing-id device-id
                          :fetch-fn #(api/fetch! device-id)
                          :interval-ms interval-ms
                          :initial-fetch? initial-fetch?})]
       (assoc ctx
              :poll-handle poll-handle
              :bridge-id   bridge-id
              :device-id   device-id)))))

(defn stop!
  "Stops polling, marks the bridge offline (cascades to children), and closes the event bus."
  [{:keys [registry bus effect-dispatcher poll-handle bridge-id]}]
  (reporting/stop-channel-polling! poll-handle)
  (runtime/apply-transition! registry bus effect-dispatcher
                             transition/set-bridge-status
                             bridge-id
                             (thing/status :offline))
  (events/close-bus! bus)
  nil)

(defn dispatch-command!
  "Sends a command to item-name with value. Returns the transition result map."
  [ctx item-name value]
  (commands/dispatch! ctx {:item-name item-name :value value}))


