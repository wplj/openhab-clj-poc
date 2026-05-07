(ns openhab.api.server
  "http-kit server lifecycle wrapper for the public HTTP API.

   openhab.api.http builds the Ring handler; this namespace owns the socket
   lifecycle so tests and embedders can use the handler without starting a server."
  (:require [org.httpkit.server :as http-kit]
            [openhab.api.http :as http]))

(def ^:private default-opts
  {:ip "127.0.0.1"
   :port 8080})

(defn start!
  "Starts the HTTP API server for ctx.

   Options are http-kit run-server options. Defaults bind to loopback only.
   Returns {:port port :stop! f}; the stop function is idempotent."
  ([ctx] (start! ctx {}))
  ([ctx opts]
   (let [server-opts  (merge default-opts opts)
         stop-server! (http-kit/run-server (http/handler ctx) server-opts)
         running?     (atom true)]
     {:port (:port server-opts)
      :stop! (fn []
               (when (compare-and-set! running? true false)
                 (stop-server!))
               nil)})))

(defn stop!
  "Stops a server handle returned by start!. Safe to call repeatedly."
  [server]
  ((:stop! server)))
