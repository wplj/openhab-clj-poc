(ns example-addon.api
  "Tiny in-memory appliance API used by the example addon tests.

   This simulates the remote side of a binding without introducing HTTP fixtures
   or external services into the core PoC.")

;; In-memory device state — simulates a remote appliance API.
;; Keyed by device-id so multiple devices can coexist.
(defonce ^:private device-states
  (atom {"ap-1" {:fan-speed 3 :temp 21 :mode "auto"}}))

(defn fetch!
  "Returns the current normalized channel map for device-id.
   Simulates a successful HTTP GET. Throws to simulate a connectivity failure."
  [device-id]
  (or (get @device-states device-id)
      (throw (ex-info "Device not found" {:device-id device-id}))))

(defn send!
  "Applies commands map to device-id's state.
   Must throw on failure; any return value indicates success."
  [_thing {:keys [thing-id commands]}]
  (swap! device-states update thing-id merge commands))

(defn reset-state!
  "Resets all in-memory device states to the supplied map."
  [states]
  (reset! device-states states))
