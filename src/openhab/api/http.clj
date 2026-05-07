(ns openhab.api.http
  "Ring/Reitit/http-kit HTTP edge for the public API.

   Handlers are intentionally thin: read via openhab.query, serialize via
   openhab.api.view, and delegate writes to openhab.commands."
  (:require [cheshire.core :as json]
            [org.httpkit.server :as http-kit]
            [openhab.api.sse :as sse]
            [openhab.api.view :as view]
            [openhab.commands :as commands]
            [openhab.query :as query]
            [reitit.ring :as ring]))

(def ^:private json-content-type "application/json; charset=utf-8")

(defn- json-response
  "Returns a Ring JSON response for an already API-safe body value."
  ([body] (json-response 200 body))
  ([status body]
   {:status status
    :headers {"Content-Type" json-content-type}
    :body (json/generate-string body)}))

(defn- not-found-response []
  (json-response 404 {:error "not found"}))

(defn- bad-request-response [message]
  (json-response 400 {:error message}))

(defn- conflict-response [reason]
  (json-response 409 {:error (if reason
                               (name reason)
                               "command-rejected")}))

(defn- method-not-allowed-response []
  (json-response 405 {:error "method not allowed"}))

(defn- registry [ctx]
  (:registry ctx))

(defn- system-handler [ctx]
  (fn [_]
    (json-response (view/system-snapshot
                    (query/system-snapshot (registry ctx))))))

(defn- things-handler [ctx]
  (fn [_]
    (json-response (mapv view/thing
                         (query/things (registry ctx))))))

(defn- thing-handler [ctx]
  (fn [request]
    (let [thing-id (get-in request [:path-params :thing-id])]
      (if-let [thing (query/thing (registry ctx) thing-id)]
        (json-response (view/thing thing))
        (not-found-response)))))

(defn- items-handler [ctx]
  (fn [_]
    (json-response (mapv view/item
                         (query/items (registry ctx))))))

(defn- item-handler [ctx]
  (fn [request]
    (let [item-name (get-in request [:path-params :item-name])]
      (if-let [item (query/item (registry ctx) item-name)]
        (json-response (view/item item))
        (not-found-response)))))

(defn- parse-json-body [request]
  (let [body (some-> (:body request) slurp)]
    (if (seq body)
      (try
        {:ok true
         :value (json/parse-string body)}
        (catch Exception _
          {:ok false
           :response (bad-request-response "malformed json")}))
      {:ok false
       :response (bad-request-response "empty body")})))

(defn- command-response [{:keys [ok reason]}]
  (cond
    ok
    (json-response 202 {:accepted true})

    (= :item-not-found reason)
    (not-found-response)

    (= :invalid-command reason)
    (bad-request-response (name reason))

    :else
    (conflict-response reason)))

(defn- item-command-handler [ctx]
  (fn [request]
    (let [parsed (parse-json-body request)]
      (if-not (:ok parsed)
        (:response parsed)
        (let [command (:value parsed)
              item-name (get-in request [:path-params :item-name])]
          (cond
            (not (map? command))
            (bad-request-response "expected json object")

            (or (not (contains? command "value"))
                (nil? (get command "value")))
            (bad-request-response "missing or null value")

            :else
            (command-response
             (commands/dispatch! ctx {:item-name item-name
                                      :value (get command "value")}))))))))

(defn- event-stream-handler [ctx]
  (fn [request]
    (let [stop!* (atom nil)]
      (http-kit/as-channel
       request
       {:on-open (fn [ch]
                   ;; Subscribe only after the initial response is accepted; otherwise
                   ;; a failed open would leave an orphaned event-bus tap.
                   (when (http-kit/send! ch {:status 200
                                             :headers {"Content-Type" sse/event-stream-content-type
                                                       "Cache-Control" "no-cache"
                                                       "Connection" "keep-alive"
                                                       "X-Accel-Buffering" "no"}
                                             :body (sse/heartbeat-frame)}
                                         false)
                     (reset! stop!*
                             (sse/start-event-stream! (:bus ctx)
                                                      #(http-kit/send! ch % false)))))
        :on-close (fn [_ch _status]
                    (when-let [stop! @stop!*]
                      (stop!)))}))))

(defn handler
  "Returns a Ring handler for the HTTP API.

   The handler closes over ctx but reads (:registry ctx) on every request, so live registry
   atoms expose fresh state without route handlers owning synchronization or domain logic."
  [ctx]
  (ring/ring-handler
   (ring/router
    [["/api/system" {:get (system-handler ctx)}]
     ["/api/things" {:get (things-handler ctx)}]
     ["/api/things/:thing-id" {:get (thing-handler ctx)}]
     ["/api/items" {:get (items-handler ctx)}]
     ["/api/items/:item-name" {:get (item-handler ctx)}]
     ["/api/items/:item-name/command" {:post (item-command-handler ctx)}]
     ["/api/events" {:get (event-stream-handler ctx)}]])
   (ring/create-default-handler
    {:not-found (constantly (not-found-response))
     :method-not-allowed (constantly (method-not-allowed-response))})))
