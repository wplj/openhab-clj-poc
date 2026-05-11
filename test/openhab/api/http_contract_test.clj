(ns openhab.api.http-contract-test
  "Checks that the public HTTP contract stays aligned with route metadata."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [openhab.api.http :as http]))

(defn- api-contract []
  (edn/read-string (slurp "doc/http-api.edn")))

(defn- documented-endpoints [contract]
  (mapv #(select-keys % [:id :method :path]) (:endpoints contract)))

(deftest http-api-contract-documents-implemented-routes
  (let [contract (api-contract)]
    (is (= http/route-endpoints
           (documented-endpoints contract)))))

(deftest http-api-contract-has-unique-endpoint-ids-and-routes
  (let [endpoints (:endpoints (api-contract))]
    (is (= (count endpoints)
           (count (set (map :id endpoints)))))
    (is (= (count endpoints)
           (count (set (map #(select-keys % [:method :path]) endpoints)))))))
