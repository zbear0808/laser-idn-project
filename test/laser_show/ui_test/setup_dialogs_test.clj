(ns laser-show.ui-test.setup-dialogs-test
  "Setup controls whose value arrives as the event's :fx/event: the Add
   Projector dialog, the zone group editor, a projector's name, a discovered
   output's checkbox and the calibration brightness slider. A discovered
   device with one output, or none, shows in the device list. The Add
   Projector dialog closes after Add, and the projector panel's Port: label
   shows whole."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(defn- app-state []
  (state/get-raw-state))

(defn- window-showing? [title]
  (boolean (some #{title} (ui/window-titles))))

(defn- with-projector!
  "One projector, :a, selected for editing."
  []
  (state/swap-state! #(-> %
                          (assoc :projectors {:a {:name "Fake 1" :host "127.0.0.1" :port 7255 :service-id 1
                                                  :enabled? true :zone-groups [:all]
                                                  :output-config {:color-bit-depth 8 :xy-bit-depth 16}}})
                          (assoc-in [:projector-ui :active-projector] :a))))

(defn- device
  "A discovered device as the network scan reports it, with these services."
  [address host-name services]
  {:address address :host-name host-name :unit-id "unit" :port 7255 :status {}
   :protocol-version "1.0" :services services :relays []})

(deftest add-projector-by-hand-test
  (let [robot (ui/robot)
        added #(some (fn [p] (when (= "10.0.0.5" (:host p)) p)) (vals (:projectors (app-state))))]
    (ui/click! robot "#projectors-tab")
    (ui/click! robot "#projectors-add-manual-button")
    (ui/wait-for! #(window-showing? "Add Projector"))
    (ui/assert-visible! "#add-projector-confirm-button")
    (ui/assert-disabled! "#add-projector-confirm-button")
    (ui/fill! robot "#add-projector-host-field" "10.0.0.5")
    (ui/wait-for! #(= "10.0.0.5" (get-in (app-state) [:ui :dialogs :add-projector-manual :host])))
    (ui/assert-enabled! "#add-projector-confirm-button")
    (ui/fill! robot "#add-projector-name-field" "Stage left")
    (ui/fill! robot "#add-projector-port-field" "7300")
    (ui/wait-for! #(= {:name "Stage left" :host "10.0.0.5" :port "7300"}
                      (select-keys (get-in (app-state) [:ui :dialogs :add-projector-manual]) [:name :host :port])))
    (ui/click! robot "#add-projector-confirm-button")
    (ui/wait-for! #(some? (added)))
    (is (= {:name "Stage left" :host "10.0.0.5" :port 7300 :enabled? true}
           (select-keys (added) [:name :host :port :enabled?])))))

(deftest add-projector-closes-after-add-test
  (let [robot (ui/robot)
        with-host #(filterv (fn [p] (= "10.0.0.8" (:host p))) (vals (:projectors (app-state))))]
    (ui/click! robot "#projectors-tab")
    (ui/click! robot "#projectors-add-manual-button")
    (ui/wait-for! #(window-showing? "Add Projector"))
    (ui/fill! robot "#add-projector-host-field" "10.0.0.8")
    (ui/assert-enabled! "#add-projector-confirm-button")
    (ui/click! robot "#add-projector-confirm-button")
    (ui/wait-for! #(= 1 (count (with-host))))
    (ui/wait-for! #(not (window-showing? "Add Projector")))
    (is (= 1 (count (with-host))) "one click, one projector: no second click to make")
    (testing "Add Manual... starts blank again"
      (ui/click! robot "#projectors-add-manual-button")
      (ui/wait-for! #(window-showing? "Add Projector"))
      (ui/assert-text! "#add-projector-host-field" "")
      (ui/assert-disabled! "#add-projector-confirm-button"))))

(deftest create-zone-group-test
  (let [robot (ui/robot)
        group-named (fn [group-name]
                      (some (fn [[id group]] (when (= group-name (:name group)) (assoc group :id id)))
                            (:zone-groups (app-state))))]
    (ui/click! robot "#zones-tab")
    (ui/click! robot "#zone-groups-add-button")
    (ui/wait-for! #(window-showing? "New Zone Group"))
    (ui/assert-visible! "#zone-group-save-button")
    (ui/assert-disabled! "#zone-group-save-button")
    (ui/fill! robot "#zone-group-name-field" "Balcony")
    (ui/assert-enabled! "#zone-group-save-button")
    (ui/fill! robot "#zone-group-description-field" "Upstairs")
    (ui/fill! robot "#zone-group-color-field" "#1ABC9C")
    (ui/wait-for! #(= {:name "Balcony" :description "Upstairs" :color "#1ABC9C"}
                      (select-keys (get-in (app-state) [:ui :dialogs :zone-group-editor]) [:name :description :color])))
    (ui/click! robot "#zone-group-save-button")
    (ui/wait-for! #(some? (group-named "Balcony")))
    (is (= {:name "Balcony" :description "Upstairs" :color "#1ABC9C"}
           (select-keys (group-named "Balcony") [:name :description :color])))
    (ui/wait-for! #(not (window-showing? "New Zone Group")))
    (testing "editing it renames it"
      (events/dispatch! {:event/type :zone-groups/edit :group-id (:id (group-named "Balcony"))})
      (ui/wait-for! #(window-showing? "Edit Zone Group"))
      (ui/fill! robot "#zone-group-name-field" "Gallery")
      (ui/wait-for! #(= "Gallery" (get-in (app-state) [:ui :dialogs :zone-group-editor :name])))
      (ui/click! robot "#zone-group-save-button")
      (ui/wait-for! #(some? (group-named "Gallery")))
      (is (nil? (group-named "Balcony"))))))

(deftest click-a-projector-to-add-it-to-the-group-test
  (let [robot (ui/robot)
        groups #(set (get-in (app-state) [:projectors :a :zone-groups]))
        settle! (fn []
                  ;; Each toggle is an event: one landing is the last of them
                  (ui/sync-fx!)
                  (events/dispatch! {:event/type :timing/set-bpm :bpm 121.0})
                  (ui/wait-for! #(= 121.0 (get-in (app-state) [:timing :bpm])))
                  (events/dispatch! {:event/type :timing/set-bpm :bpm 120.0})
                  (ui/wait-for! #(= 120.0 (get-in (app-state) [:timing :bpm]))))]
    (with-projector!)
    (ui/click! robot "#zones-tab")
    (ui/assert-text! "#zones-subtitle"
                     "Routing targets for cues • Click a projector to add it to the group, or use the Projectors tab")
    (events/dispatch! {:event/type :zone-groups/select :group-id :left})
    (ui/assert-visible! "#zone-output-a")
    (testing "a click on the projector adds it"
      (ui/click! robot "#zone-output-a .label-bold")
      (ui/wait-for! #(contains? (groups) :left))
      (settle!)
      (is (= #{:all :left} (groups))))
    (testing "a click on its checkbox takes it out again, once"
      (ui/click! robot "#zone-output-a .check-box")
      (ui/wait-for! #(not (contains? (groups) :left)))
      (settle!)
      (is (= #{:all} (groups)))
      (is (not (ui/selected? "#zone-output-a .check-box")) "and the box says so"))))

(deftest rename-projector-test
  (let [robot (ui/robot)
        projector-name #(get-in (app-state) [:projectors :a :name])]
    (with-projector!)
    (ui/click! robot "#projectors-tab")
    (ui/assert-text! "#projector-config-title" "CONFIGURE: Fake 1")
    (ui/fill! robot "#projector-name-field" "Stage left")
    (ui/wait-for! #(= "Stage left" (projector-name)))
    (ui/assert-text! "#projector-config-title" "CONFIGURE: Stage left")
    (testing "the tab keeps up with the next edit"
      (ui/fill! robot "#projector-name-field" "Stage right")
      (ui/wait-for! #(= "Stage right" (projector-name)))
      (ui/assert-text! "#projector-config-title" "CONFIGURE: Stage right"))))

(deftest projector-port-label-shows-whole-test
  (let [robot (ui/robot)]
    (with-projector!)
    (ui/click! robot "#projectors-tab")
    (ui/assert-visible! "#projector-port-label")
    (ui/wait-for! #(= "Port:" (ui/shown-text (ui/query "#projector-port-label"))))
    (is (= "Port:" (ui/shown-text (ui/query "#projector-port-label"))) "not \"P…\"")))

(deftest untick-a-discovered-output-disables-it-test
  (let [robot (ui/robot)
        enabled? (fn [service-id] (get-in (app-state) [:projectors (keyword (str "projector-10-0-0-5-" service-id)) :enabled?]))]
    (events/dispatch! {:event/type :projectors/scan-complete
                       :devices [(device "10.0.0.5" "Two-head DAC" [{:service-id 1 :name "Head A"}
                                                                    {:service-id 2 :name "Head B"}])]})
    (ui/click! robot "#projectors-tab")
    (ui/click! robot "#discovered-device-10-0-0-5")
    (ui/assert-visible! "#discovered-output-10-0-0-5-1")
    (is (false? (enabled? 1)) "a discovered output starts disabled")
    (ui/check! robot "#discovered-output-10-0-0-5-1")
    (ui/wait-for! #(true? (enabled? 1)))
    (ui/uncheck! robot "#discovered-output-10-0-0-5-1")
    (ui/wait-for! #(false? (enabled? 1)))
    (is (false? (enabled? 1)))
    (is (false? (enabled? 2)) "the other output is untouched")))

(deftest calibration-brightness-slider-test
  (let [robot (ui/robot)
        brightness #(get-in (app-state) [:projector-ui :calibration-brightness])]
    (with-projector!)
    (ui/click! robot "#projectors-tab")
    (ui/click! robot "#projector-calibration-button")
    (ui/assert-visible! "#calibration-brightness-slider")
    (ui/assert-text! "#calibration-brightness-label" "10%")
    (ui/slide! robot "#calibration-brightness-slider" 0.4)
    (ui/wait-for! #(= 0.4 (brightness)))
    (ui/assert-text! "#calibration-brightness-label" "40%")))

(deftest single-output-device-renders-test
  (let [robot (ui/robot)
        enabled? #(get-in (app-state) [:projectors :projector-10-0-0-6-1 :enabled?])]
    (events/dispatch! {:event/type :projectors/scan-complete
                       :devices [(device "10.0.0.6" "One-head DAC" [{:service-id 1 :name "Head"}])
                                 (device "10.0.0.7" "Bare DAC" [])]})
    (ui/click! robot "#projectors-tab")
    (ui/assert-visible! "#discovered-device-10-0-0-6")
    (ui/assert-visible! "#discovered-device-10-0-0-7")
    (ui/assert-visible! "#discovered-output-10-0-0-7-0")
    (ui/check! robot "#discovered-output-10-0-0-6-1")
    (ui/wait-for! #(true? (enabled?)))
    (ui/uncheck! robot "#discovered-output-10-0-0-6-1")
    (ui/wait-for! #(false? (enabled?)))
    (is (false? (enabled?)))))

(deftest delete-group-asks-and-retargets-test
  (let [robot (ui/robot)
        cue-zone #(get-in (app-state) [:chains :cue-chains [0 0] :items 0 :zone-group-id])]
    (state/swap-state! #(-> %
                            (assoc-in [:zone-groups :group-balcony] {:id :group-balcony :name "Balcony"
                                                                     :description "" :color "#FF0000"})
                            (assoc-in [:chains :cue-chains [0 0]] {:name "Intro"
                                                                   :items [{:id "p" :type :preset :preset-id :circle
                                                                            :zone-group-id :group-balcony}]})))
    (ui/click! robot "#zones-tab")
    (events/dispatch! {:event/type :zone-groups/select :group-id :group-balcony})
    (ui/wait-for! #(= "Used by 1 cue" (ui/node-text "#zone-group-cue-usage")))
    (ui/click! robot "#zone-group-delete-button")
    (ui/wait-for! #(window-showing? "Delete \"Balcony\"?"))
    (ui/assert-text! "#confirm-details" "A1 Intro")
    (ui/select! robot "#confirm-choice" ":left")
    (ui/wait-for! #(= :left (get-in (app-state) [:ui :dialogs :confirm :choice-value])))
    (ui/click! robot "#confirm-ok")
    (ui/wait-for! #(nil? (get-in (app-state) [:zone-groups :group-balcony])))
    (is (= :left (cue-zone)) "the cue now goes to Left")
    (ui/wait-for! #(not (window-showing? "Delete \"Balcony\"?")))))

(deftest all-has-no-delete-test
  (let [robot (ui/robot)]
    (ui/click! robot "#zones-tab")
    (events/dispatch! {:event/type :zone-groups/select :group-id :all})
    (ui/wait-for! #(some? (ui/query "#zone-group-cannot-delete")))
    (ui/assert-text! "#zone-group-cannot-delete" "All can't be deleted.")
    (is (nil? (ui/query "#zone-group-delete-button")))
    (testing "another group has its Delete... button"
      (events/dispatch! {:event/type :zone-groups/select :group-id :left})
      (ui/wait-for! #(some? (ui/query "#zone-group-delete-button"))))))

