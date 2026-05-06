(ns openhab.api.http
  (:require [cheshire.core :as json]
            [openhab.api.view :as view]
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

(defn handler
  "Returns a Ring handler for the read-only HTTP API.

   The handler closes over ctx but reads (:registry ctx) on every request, so live registry
   atoms expose fresh state without route handlers owning synchronization or domain logic."
  [ctx]
  (ring/ring-handler
   (ring/router
    [["/api/system" {:get (system-handler ctx)}]
     ["/api/things" {:get (things-handler ctx)}]
     ["/api/things/:thing-id" {:get (thing-handler ctx)}]
     ["/api/items" {:get (items-handler ctx)}]
     ["/api/items/:item-name" {:get (item-handler ctx)}]])
   (ring/create-default-handler
    {:not-found (constantly (not-found-response))
     :method-not-allowed (constantly (method-not-allowed-response))})))
