(ns openhab.link-test
  "Tests for Link construction and composite key behavior."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is]]
            [openhab.link :as link]))

(deftest make-link-defaults-to-system-default-profile
  (let [lnk (link/make-link "Lamp" "thing-1" :power)]
    (is (s/valid? ::link/link lnk))
    (is (= "system:default" (:profile lnk)))))

(deftest make-link-allows-custom-profile
  (let [lnk (link/make-link "Lamp" "thing-1" :power "custom:offset")]
    (is (s/valid? ::link/link lnk))
    (is (= "custom:offset" (:profile lnk)))))

(deftest link-key-is-the-composite-identity
  (let [lnk (link/make-link "Lamp" "thing-1" :power)]
    (is (= ["Lamp" "thing-1" :power] (link/link-key lnk)))))
