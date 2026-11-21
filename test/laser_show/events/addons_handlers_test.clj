(ns laser-show.events.addons-handlers-test
  "The add-on events (plans/2026-11-19-output-drivers/00 §6): :addons/loaded
   puts the drivers' descriptors and the add-ons' statuses into app state,
   which is never saved, undone or counted as an unsaved change; Install
   add-on… (the chooser, the install effect, its notice and the pending
   list) and Open add-ons folder (02 § D3)."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.core :as events]
            [laser-show.events.handlers :as handlers]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]))

(def ^:private drivers
  {:lsps {:id :lsps :name "LSPS point stream (UDP)" :transport :network
          :fields [{:key :host :label "Host" :type :host :required? true}
                   {:key :port :label "Port" :type :int :min 1 :max 65535 :default 7266}]
          :capabilities nil :discover? false :addon nil}})

(def ^:private addons
  [{:id :acme/nova-bridge :name "Acme Nova output" :version "0.3.0" :source "/addons/acme-nova.jar"
    :state :loaded :drivers [:acme/nova] :load-ms 840}
   {:source "/addons/broken.jar" :state :failed :error "No laser-show-addon.edn in broken.jar"}])

(deftest loaded-writes-descriptors-test
  (let [initial (history/mark-clean (domains/build-initial-state))]
    (is (= {:drivers {} :addons [] :dir nil :pending-installs []} (get-in initial [:backend :output]))
        "nothing before startup says")
    (let [state (:state (handlers/handle-event {:event/type :addons/loaded :addons addons :drivers drivers
                                                :state initial}))]
      (is (= drivers (get-in state [:backend :output :drivers])))
      (is (= addons (get-in state [:backend :output :addons]))))
    (testing "none of it is the document: no undo step, not unsaved"
      (let [state (:state (events/handle-event-pipeline {:event/type :addons/loaded :addons addons :drivers drivers
                                                         :state initial}))]
        (is (= drivers (get-in state [:backend :output :drivers])))
        (is (empty? (get-in state [:history :undo-stack])))
        (is (false? (get-in state [:project :dirty?])))))
    (testing "loaded again (no add-ons, no drivers), it replaces what was there"
      (let [once (:state (handlers/handle-event {:event/type :addons/loaded :addons addons :drivers drivers :state initial}))
            twice (:state (handlers/handle-event {:event/type :addons/loaded :addons [] :drivers {} :state once}))]
        (is (= {:drivers {} :addons [] :dir nil :pending-installs []} (get-in twice [:backend :output])))))
    (testing "the folder it loaded from, and no pending install any more: startup swapped them in"
      (let [pending (assoc-in initial [:backend :output :pending-installs] [{:file "/addons/a.jar.new"}])
            state (:state (handlers/handle-event {:event/type :addons/loaded :addons addons :drivers drivers
                                                  :dir "/Users/zoë/LaserShowProjects/addons" :state pending}))]
        (is (= "/Users/zoë/LaserShowProjects/addons" (get-in state [:backend :output :dir])))
        (is (= [] (get-in state [:backend :output :pending-installs])))))))


;; Installing and the folder (02 § D3)


(defn- state-with-dir []
  (-> (domains/build-initial-state)
      (assoc-in [:backend :output :dir] "/home/zoë/LaserShowProjects/addons")
      history/mark-clean))

(defn- handle [event]
  (handlers/handle-event (assoc event :state (state-with-dir))))

(deftest pick-install-opens-chooser-test
  (let [result (handle {:event/type :addons/pick-install})]
    (is (= {:fx/show-file-chooser {:title "Install add-on"
                                   :mode :open
                                   :initial-directory (System/getProperty "user.home")
                                   :extension-filters [{:description "Laser Show add-ons (*.jar)"
                                                        :extensions ["*.jar"]}]
                                   :on-result {:event/type :addons/install-picked}}}
           result)
        "only the chooser: nothing in state changes")))

(deftest install-picked-runs-install-effect-test
  (is (= {:addons/install {:file-path "/Users/zoë/Downloads/acme-nova.jar"
                           :dir "/home/zoë/LaserShowProjects/addons"}}
         (handle {:event/type :addons/install-picked :file-path "/Users/zoë/Downloads/acme-nova.jar"}))
      "into the folder the add-ons loaded from"))

(deftest cancelled-pick-does-nothing-test
  (is (= {} (handle {:event/type :addons/install-picked :file-path nil})))
  (is (= {} (handle {:event/type :addons/install-picked :file-path ""}))))

(defn- notice [result] (get-in result [:state :ui :notice]))

(deftest installed-shows-restart-notice-test
  (let [installed {:event/type :addons/installed
                   :file "/home/zoë/LaserShowProjects/addons/acme-nova.jar.new"
                   :replaced? false
                   :descriptor {:id :acme/nova-bridge :name "Acme Nova" :version "0.3.0"}}]
    (testing "a new add-on"
      (let [result (handle installed)]
        (is (= "Installed Acme Nova 0.3.0. Restart Laser Show to load it." (:text (notice result))))
        (is (= :info (:level (notice result))))
        (is (= :ui/clear-notice (get-in result [:dispatch-later :event :event/type])) "the notice goes by itself")
        (is (= [{:id :acme/nova-bridge :name "Acme Nova" :version "0.3.0"
                 :file "/home/zoë/LaserShowProjects/addons/acme-nova.jar.new" :replaced? false}]
               (get-in result [:state :backend :output :pending-installs])))))
    (testing "over one already there"
      (let [result (handle (assoc installed :replaced? true))]
        (is (= "Replaced Acme Nova 0.3.0. Restart Laser Show to load it." (:text (notice result))))
        (is (true? (get-in result [:state :backend :output :pending-installs 0 :replaced?])))))
    (testing "installed twice before a restart: listed once, the newer one"
      (let [once (:state (handle installed))
            twice (handlers/handle-event (assoc installed :state once
                                                :descriptor {:id :acme/nova-bridge :name "Acme Nova" :version "0.3.1"}))]
        (is (= ["0.3.1"] (mapv :version (get-in twice [:state :backend :output :pending-installs]))))))
    (testing "no :name or :version: its id"
      (is (= "Installed :acme/nova-bridge. Restart Laser Show to load it."
             (:text (notice (handle (assoc installed :descriptor {:id :acme/nova-bridge})))))))
    (testing "not the document: no undo step, not unsaved"
      (let [state (:state (events/handle-event-pipeline (assoc installed :state (state-with-dir))))]
        (is (= 1 (count (get-in state [:backend :output :pending-installs]))))
        (is (empty? (get-in state [:history :undo-stack])))
        (is (false? (get-in state [:project :dirty?])))))))

(deftest install-failed-shows-error-test
  (let [result (handle {:event/type :addons/install-failed
                        :file-path "/Users/zoë/Downloads/acme-nova.jar"
                        :error "Built for output API 2; this app has API 1"})]
    (is (= {:text "Could not install acme-nova.jar: Built for output API 2; this app has API 1" :level :error}
           (select-keys (notice result) [:text :level])))
    (is (= [] (get-in result [:state :backend :output :pending-installs])) "nothing is pending")))

(deftest open-folder-runs-effect-test
  (is (= {:system/open-folder {:path "/home/zoë/LaserShowProjects/addons"
                               :on-failed {:event/type :addons/open-folder-failed}}}
         (handle {:event/type :addons/open-folder})))
  (testing "it couldn't: a notice with the full path"
    (let [result (handle {:event/type :addons/open-folder-failed :path "/home/zoë/LaserShowProjects/addons"})]
      (is (= {:text "Couldn't open the add-ons folder. It is at /home/zoë/LaserShowProjects/addons" :level :info}
             (select-keys (notice result) [:text :level]))))))
