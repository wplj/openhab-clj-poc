(ns openhab.api.sse
  "Server-Sent Events formatting and event-bus stream wiring.

   This namespace keeps SSE frames testable without an HTTP server. The HTTP edge
   supplies only a send function and connection cleanup hook."
  (:require [cheshire.core :as json]
            [clojure.core.async :as async]
            [openhab.api.view :as view]
            [openhab.events :as events]))

(def event-stream-content-type "text/event-stream; charset=utf-8")

(def default-heartbeat-ms 25000)

(defn heartbeat-frame
  "Returns an SSE comment frame used as a keepalive."
  []
  ":\n\n")

(defn event-payload
  "Returns the public JSON payload for one decorated event map."
  [event]
  ;; Events are raw EDN until they cross this API boundary.
  (view/api-value
   {:type (:event/name event)
    :topic (:event/topic event)
    :payload (dissoc event :event/name :event/topic)}))

(defn event-frame
  "Formats one decorated event map as an SSE frame."
  [event]
  (str "event: " (:event/name event) "\n"
       "data: " (json/generate-string (event-payload event)) "\n\n"))

(defn- send-frame [send-frame! frame]
  (try
    (not (false? (send-frame! frame)))
    (catch Exception _
      false)))

(defn start-event-stream!
  "Subscribes to all events and sends SSE frames through send-frame!.

   Returns a no-arg cleanup fn. send-frame! may return false to stop the stream.
   Heartbeats are SSE comments and can be disabled by passing :heartbeat-ms nil."
  ([bus send-frame!] (start-event-stream! bus send-frame! {}))
  ([bus send-frame! {:keys [buf-size heartbeat-ms]
                     :or {buf-size 64
                          heartbeat-ms default-heartbeat-ms}}]
   (let [event-ch (events/subscribe-all! bus buf-size)
         stop-ch (async/chan)
         stopped? (atom false)]
     (letfn [(stop! []
               (when (compare-and-set! stopped? false true)
                 (events/unsubscribe-all! bus event-ch)
                 (async/close! stop-ch)))
             (ports []
               ;; timeout channels are one-shot, so create a fresh heartbeat port each loop.
               (cond-> [stop-ch event-ch]
                 (some? heartbeat-ms) (conj (async/timeout heartbeat-ms))))]
       (async/go-loop []
         (let [[event port] (async/alts! (ports) :priority true)]
           (cond
             (= port stop-ch)
             nil

             (= port event-ch)
             (if (some? event)
               (if (send-frame send-frame! (event-frame event))
                 (recur)
                 (stop!))
               (stop!))

             :else
             (if (send-frame send-frame! (heartbeat-frame))
               (recur)
               (stop!)))))
       stop!))))
