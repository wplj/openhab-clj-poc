(ns example-addon.main
  "Runnable entrypoint for the in-memory example addon demo.

   This namespace is intentionally addon-local: openhab.* remains framework code,
   while the demo decides which addon, seed state, and HTTP port to run."
  (:require [clojure.string :as str]
            [example-addon.api :as api]
            [example-addon.system :as system]
            [openhab.api.server :as server]))

(def ^:private default-port 8080)

(def ^:private default-ip "127.0.0.1")

(def ^:private demo-device-states
  {"ap-1" {:fan-speed 3
           :temp 21
           :mode "auto"}})

(defn parse-port
  "Parses a configured port, falling back to 8080 for nil, blank, invalid, or out-of-range values."
  [value]
  (let [s (some-> value str str/trim)]
    (if (seq s)
      (try
        (let [port (Integer/parseInt s)]
          (if (<= 1 port 65535)
            port
            default-port))
        (catch NumberFormatException _
          default-port))
      default-port)))

(defn configured-port
  "Returns the demo HTTP port from PORT, JVM -Dport, or the default."
  []
  (if-let [configured (or (System/getenv "PORT")
                          (System/getProperty "port"))]
    (parse-port configured)
    default-port))

(defn make-stop-fn
  "Returns a stop fn that closes the HTTP server before stopping the addon."
  [srv ctx]
  (fn []
    ;; Stop accepting requests before stopping polling/device state.
    (server/stop! srv)
    (system/stop! ctx)))

(defn start-demo!
  "Starts the in-memory example addon and local HTTP API. Returns {:ctx :server :stop!}."
  ([] (start-demo! {}))
  ([{:keys [port ip]
     :or {port default-port
          ip default-ip}}]
   (api/reset-state! demo-device-states)
   (let [ctx (system/start!)]
     (try
       (let [srv (server/start! ctx {:ip ip :port port})]
         {:ctx ctx
          :server srv
          :stop! (make-stop-fn srv ctx)})
       (catch Exception ex
         (system/stop! ctx)
         (throw ex))))))

(defn -main
  "Starts the example addon HTTP demo. Set PORT or JVM -Dport to override 8080."
  [& _args]
  (let [port (configured-port)
        ip default-ip
        {:keys [stop!]} (start-demo! {:ip ip :port port})]
    (.addShutdownHook (Runtime/getRuntime) (Thread. stop!))
    (println (str "Example addon HTTP API running on http://" ip ":"
                  port
                  "/api/system"))))
