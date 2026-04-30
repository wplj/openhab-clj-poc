(ns openhab.profile-test
  (:require [clojure.test :refer [deftest is]]
            [openhab.link :as link]
            [openhab.profile :as profile]
            [openhab.thing :as thing]))

(defn- profile-context [lnk]
  {:thing (thing/make-thing "dev-1" :fan)
   :link lnk
   :channel-id :fan-speed
   :channel-value 7
   :item-value 11})

(deftest custom-profile-functions-receive-one-context-map
  (let [seen-project-context (atom nil)
        seen-command-context (atom nil)
        profiles (-> (profile/make-registry)
                     (profile/register-profile
                      "custom:test"
                      {:project-state (fn [context]
                                        (reset! seen-project-context context)
                                        {:state (:channel-value context)})
                       :encode-command (fn [context]
                                         (reset! seen-command-context context)
                                         (* 2 (:item-value context)))}))
        lnk (link/make-link "FanSpeed" "dev-1" :fan-speed "custom:test")]
    (is (= {:state 7 :state-type :number}
           (profile/project-state profiles (profile-context lnk))))
    (is (= 22 (profile/encode-command profiles (profile-context lnk))))
    (is (contains? @seen-project-context :profile-registry))
    (is (contains? @seen-command-context :profile-registry))))

(deftest projected-state-map-without-state-type-is-normalized
  (let [profiles (-> (profile/make-registry)
                     (profile/register-profile
                      "custom:no-type"
                      {:project-state (fn [_] {:state true})
                       :encode-command :item-value}))
        lnk (link/make-link "Enabled" "dev-1" :enabled "custom:no-type")]
    (is (= {:state true :state-type :boolean}
           (profile/project-state profiles (profile-context lnk))))))
(deftest register-profile-rejects-malformed-profiles
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Invalid profile registration"
                        (profile/register-profile (profile/make-registry)
                                                  "custom:bad"
                                                  {:project-state identity}))))

(deftest register-codec-rejects-malformed-codecs
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Invalid channel codec registration"
                        (profile/register-codec (profile/make-registry)
                                                [:fan :fan-speed]
                                                {:to-state 42}))))

(deftest known-profile-recognizes-registered-profiles
  (let [profiles (profile/register-profile (profile/make-registry)
                                           "custom:test"
                                           {:project-state :channel-value
                                            :encode-command :item-value})]
    (is (profile/known-profile? profiles "system:default"))
    (is (profile/known-profile? profiles "custom:test"))
    (is (not (profile/known-profile? profiles "custom:missing")))))
