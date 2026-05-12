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

(def ^:private not-found-response
  (json-response 404 {:error "not found"}))

(defn- bad-request-response [message]
  (json-response 400 {:error message}))

(defn- conflict-response [reason]
  (json-response 409 {:error (if reason
                               (name reason)
                               "command-rejected")}))

(def ^:private method-not-allowed-response
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
        not-found-response))))

(defn- items-handler [ctx]
  (fn [_]
    (json-response (mapv view/item
                         (query/items (registry ctx))))))

(defn- links-handler [ctx]
  (fn [_]
    (json-response (mapv view/link
                         (query/links (registry ctx))))))

(defn- item-links-handler [ctx]
  (fn [request]
    ;; Keep the existence check and link lookup on the same registry snapshot.
    (let [state @(registry ctx)
          item-name (get-in request [:path-params :item-name])]
      (if (query/item state item-name)
        (json-response (mapv view/link
                             (query/links-for-item state item-name)))
        not-found-response))))

(defn- item-handler [ctx]
  (fn [request]
    (let [item-name (get-in request [:path-params :item-name])]
      (if-let [item (query/item (registry ctx) item-name)]
        (json-response (view/item item))
        not-found-response))))

(defn- channel-links-handler [ctx]
  (fn [request]
    ;; query/thing returns a read model; :channels is a vector, not the raw channel map.
    (let [state @(registry ctx)
          {:keys [thing-id channel-id]} (:path-params request)
          channel-id (keyword channel-id)
          thing-view (query/thing state thing-id)]
      (if (some #(= channel-id (:channel-id %)) (:channels thing-view))
        (json-response (mapv view/link
                             (query/links-for-channel state thing-id channel-id)))
        not-found-response))))

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
    not-found-response

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

(def ^:private route-specs
  [{:id :system-snapshot
    :method :get
    :path "/api/system"
    :handler-fn system-handler}
   {:id :things
    :method :get
    :path "/api/things"
    :handler-fn things-handler}
   {:id :thing
    :method :get
    :path "/api/things/:thing-id"
    :handler-fn thing-handler}
   {:id :items
    :method :get
    :path "/api/items"
    :handler-fn items-handler}
   {:id :item
    :method :get
    :path "/api/items/:item-name"
    :handler-fn item-handler}
   {:id :item-links
    :method :get
    :path "/api/items/:item-name/links"
    :handler-fn item-links-handler}
   {:id :links
    :method :get
    :path "/api/links"
    :handler-fn links-handler}
   {:id :channel-links
    :method :get
    :path "/api/things/:thing-id/channels/:channel-id/links"
    :handler-fn channel-links-handler}
   {:id :item-command
    :method :post
    :path "/api/items/:item-name/command"
    :handler-fn item-command-handler}
   {:id :events
    :method :get
    :path "/api/events"
    :handler-fn event-stream-handler}])

(def route-endpoints
  "Stable route metadata used by the API contract drift test.

   Handler functions stay private; this exposes only the public API identity."
  (mapv #(select-keys % [:id :method :path]) route-specs))

(defn- routes [ctx]
  (mapv (fn [{:keys [path method handler-fn]}]
          [path {method (handler-fn ctx)}])
        route-specs))

(defn handler
  "Returns a Ring handler for the HTTP API.

   The handler closes over ctx but reads (:registry ctx) on every request, so live registry
   atoms expose fresh state without route handlers owning synchronization or domain logic."
  [ctx]
  (ring/ring-handler
   (ring/router (routes ctx))
   (ring/create-default-handler
    {:not-found (constantly not-found-response)
     :method-not-allowed (constantly method-not-allowed-response)})))
