(ns laser-show.ui-test.setup-dialogs-test
  "Setup controls whose value arrives as the event's :fx/event: the Add
   Projector dialog, the zone group editor, a projector's name, a discovered
   output's checkbox and the calibration brightness slider. A discovered
   device with one output, or none, shows in the device list. The Add
   Projector dialog closes after Add, and the projector panel's Port: label
   shows whole."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.state.clipboard :as clipboard]
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
                                                  :enabled? true :zone-groups [] :exclude-from-all? false
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
                     "Groups are where cues draw. All and the automatic groups follow the order on the Projectors tab.")
    (events/dispatch! {:event/type :zone-groups/select :group-id :graphics})
    (ui/assert-visible! "#zone-output-a")
    (testing "a click on the projector adds it"
      (ui/click! robot "#zone-output-a .label-bold")
      (ui/wait-for! #(contains? (groups) :graphics))
      (settle!)
      (is (= #{:graphics} (groups))))
    (testing "a click on its checkbox takes it out again, once"
      (ui/click! robot "#zone-output-a .check-box")
      (ui/wait-for! #(not (contains? (groups) :graphics)))
      (settle!)
      (is (= #{} (groups)))
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
    (ui/assert-visible! "#rig-card-enable-projector-10-0-0-5-1")
    (is (false? (enabled? 1)) "a discovered output starts disabled")
    (ui/check! robot "#rig-card-enable-projector-10-0-0-5-1")
    (ui/wait-for! #(true? (enabled? 1)))
    (ui/uncheck! robot "#rig-card-enable-projector-10-0-0-5-1")
    (ui/wait-for! #(false? (enabled? 1)))
    (is (false? (enabled? 1)))
    (is (false? (enabled? 2)) "the other output is untouched")))

(deftest calibration-brightness-slider-test
  (let [robot (ui/robot)
        brightness #(get-in (app-state) [:projector-ui :calibration-brightness])]
    (with-projector!)
    (ui/click! robot "#projectors-tab")
    ;; Below the strip, the inspector scrolls to its test pattern section
    (ui/scroll-into-view! "#projector-calibration-button")
    (ui/click! robot "#projector-calibration-button")
    (ui/scroll-into-view! "#calibration-brightness-slider")
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
    (ui/assert-visible! "#rig-card-projector-10-0-0-6-1")
    (ui/assert-visible! "#rig-card-projector-10-0-0-7-0")
    (ui/assert-visible! "#rig-card-enable-projector-10-0-0-7-0")
    (ui/check! robot "#rig-card-enable-projector-10-0-0-6-1")
    (ui/wait-for! #(true? (enabled?)))
    (ui/uncheck! robot "#rig-card-enable-projector-10-0-0-6-1")
    (ui/wait-for! #(false? (enabled?)))
    (is (false? (enabled?)))))

(deftest delete-group-asks-and-retargets-test
  (let [robot (ui/robot)
        cue-zone #(get-in (app-state) [:chains :cue-chains [0 0] :items 0 :target])]
    (state/swap-state! #(-> %
                            (assoc-in [:zone-groups :group-balcony] {:id :group-balcony :name "Balcony"
                                                                     :description "" :color "#FF0000"})
                            (assoc-in [:chains :cue-chains [0 0]] {:name "Intro"
                                                                   :items [{:id "p" :type :preset :preset-id :circle
                                                                            :target :group-balcony}]})))
    (ui/click! robot "#zones-tab")
    (events/dispatch! {:event/type :zone-groups/select :group-id :group-balcony})
    (ui/wait-for! #(= "Used by 1 cue" (ui/node-text "#zone-group-cue-usage")))
    (ui/click! robot "#zone-group-delete-button")
    (ui/wait-for! #(window-showing? "Delete \"Balcony\"?"))
    (ui/assert-text! "#confirm-details" "A1 Intro")
    (ui/select! robot "#confirm-choice" ":auto/left")
    (ui/wait-for! #(= :auto/left (get-in (app-state) [:ui :dialogs :confirm :choice-value])))
    (ui/click! robot "#confirm-ok")
    (ui/wait-for! #(nil? (get-in (app-state) [:zone-groups :group-balcony])))
    (is (= :auto/left (cue-zone)) "the cue now goes to Left, the automatic group")
    (ui/wait-for! #(not (window-showing? "Delete \"Balcony\"?")))))

(deftest all-has-no-delete-test
  (let [robot (ui/robot)]
    (ui/click! robot "#zones-tab")
    (events/dispatch! {:event/type :zone-groups/select :group-id :all})
    (ui/wait-for! #(some? (ui/query "#zone-group-cannot-delete")))
    (ui/assert-text! "#zone-group-cannot-delete" "Automatic: can't be renamed or deleted.")
    (is (nil? (ui/query "#zone-group-delete-button")))
    (testing "another group has its Delete... button"
      (events/dispatch! {:event/type :zone-groups/select :group-id :graphics})
      (ui/wait-for! #(some? (ui/query "#zone-group-delete-button"))))))

(defn- check-box-labelled
  "The check box whose text is text, or nil."
  [text]
  (some #(when (= text (ui/on-fx-thread (fn [] (.getText ^javafx.scene.control.Labeled %)))) %)
        (ui/query-all ".check-box")))

(defn- with-calibration-chains!
  "Projectors :a and :b; :a's chain is RGB curves and an Axis Transform, the
   Axis Transform selected for editing."
  []
  (with-projector!)
  (state/swap-state! #(-> %
                          (assoc-in [:projectors :b] {:name "Fake 2" :host "127.0.0.2" :port 7255 :service-id 1
                                                      :enabled? true :zone-groups [:all]
                                                      :output-config {:color-bit-depth 8 :xy-bit-depth 16}})
                          (assoc-in [:chains :projector-effects :a :items]
                                    [{:id #uuid "00000000-0000-0000-0000-0000000000a1" :effect-id :rgb-curves :enabled? true
                                      :params {:r-curve-points [[0.0 0.0] [1.0 1.0]]
                                               :g-curve-points [[0.0 0.0] [1.0 1.0]]
                                               :b-curve-points [[0.0 0.0] [1.0 1.0]]}}
                                     {:id #uuid "00000000-0000-0000-0000-0000000000a2" :effect-id :axis-transform :enabled? true
                                      :params {:mirror-x? false :mirror-y? false :swap-axes? false}}])
                          (assoc-in [:chains :projector-effects :b :items] [])
                          (assoc-in [:list-ui [:projector-effects :a]]
                                    {:selected-ids #{#uuid "00000000-0000-0000-0000-0000000000a2"}
                                     :last-selected-id #uuid "00000000-0000-0000-0000-0000000000a2"}))))

(deftest axis-transform-boxes-stick-test
  (let [robot (ui/robot)
        param #(get-in (app-state) [:chains :projector-effects :a :items 1 :params %])]
    (with-calibration-chains!)
    (ui/click! robot "#projectors-tab")
    (ui/wait-for! #(some? (check-box-labelled "Mirror X")))
    (ui/click-node! robot (check-box-labelled "Mirror X"))
    (ui/wait-for! #(true? (param :mirror-x?)))
    (ui/click-node! robot (check-box-labelled "Swap Axes"))
    (ui/wait-for! #(true? (param :swap-axes?)))
    (is (false? (param :mirror-y?)) "only the boxes clicked change")
    (ui/click-node! robot (check-box-labelled "Mirror X"))
    (ui/wait-for! #(false? (param :mirror-x?)))
    (is (= :rgb-curves (get-in (app-state) [:chains :projector-effects :a :items 0 :effect-id]))
        "and the chain is still two effects")))

(deftest copy-an-effect-to-another-projector-test
  (let [robot (ui/robot)
        b-effects #(mapv :effect-id (get-in (app-state) [:chains :projector-effects :b :items]))]
    (with-calibration-chains!)
    (ui/click! robot "#projectors-tab")
    (ui/wait-for! #(some? (ui/query "#chain-copy-selection")))
    (ui/click! robot "#chain-copy-selection")
    (ui/wait-for! #(= :item-effects (:type (clipboard/get-clipboard))))
    (events/dispatch! {:event/type :projectors/select-projector :projector-id :b})
    (ui/wait-for! #(= "CONFIGURE: Fake 2" (ui/node-text "#projector-config-title")))
    (ui/wait-for! #(not (ui/on-fx-thread (fn [] (.isDisable ^javafx.scene.Node (ui/query "#chain-paste-items"))))))
    (ui/click! robot "#chain-paste-items")
    (ui/wait-for! #(= [:axis-transform] (b-effects)))
    (is (not= #uuid "00000000-0000-0000-0000-0000000000a2"
              (get-in (app-state) [:chains :projector-effects :b :items 0 :id]))
        "the pasted effect is a copy with its own id")))

(deftest remove-projector-asks-first-test
  (let [robot (ui/robot)]
    (with-projector!)
    (ui/click! robot "#projectors-tab")
    (ui/click! robot "#projector-remove-button")
    (ui/wait-for! #(window-showing? "Remove Fake 1?"))
    (testing "Cancel keeps it"
      (ui/click! robot "#confirm-cancel")
      (ui/wait-for! #(not (window-showing? "Remove Fake 1?")))
      (is (some? (get-in (app-state) [:projectors :a]))))
    (testing "Remove removes it"
      (ui/click! robot "#projector-remove-button")
      (ui/wait-for! #(window-showing? "Remove Fake 1?"))
      (ui/click! robot "#confirm-ok")
      (ui/wait-for! #(nil? (get-in (app-state) [:projectors :a])))
      (ui/wait-for! #(not (window-showing? "Remove Fake 1?"))))))

(deftest subtitle-says-to-put-the-lasers-in-order-test
  (let [robot (ui/robot)]
    (ui/click! robot "#projectors-tab")
    (ui/assert-text! "#projectors-subtitle" "Put your lasers in order, left to right. The groups follow the order.")))


;; Decimate (zones plan 12)

(defn- menu-texts
  "The texts of the items of the menu button found by selector."
  [selector]
  (let [^javafx.scene.control.MenuButton menu (ui/query selector)]
    (ui/on-fx-thread #(mapv (fn [^javafx.scene.control.MenuItem item] (.getText item)) (.getItems menu)))))

(defn- window-titled
  "The showing window whose title starts with title, or nil."
  ^javafx.stage.Stage [title]
  (ui/on-fx-thread #(some (fn [w] (when (and (instance? javafx.stage.Stage w) (.isShowing ^javafx.stage.Stage w)
                                              (some-> (.getTitle ^javafx.stage.Stage w) (.startsWith ^String title)))
                                     w))
                          (vec (javafx.stage.Window/getWindows)))))

(defn- labeled-in
  "The first label or button in window whose text is text, or nil."
  [^javafx.stage.Window window text]
  (ui/on-fx-thread
   #(some (fn [n] (when (and (instance? javafx.scene.control.Labeled n)
                             (= text (.getText ^javafx.scene.control.Labeled n)))
                    n))
          (tree-seq (fn [n] (instance? javafx.scene.Parent n))
                    (fn [^javafx.scene.Parent p] (seq (.getChildrenUnmodifiable p)))
                    (.getRoot (.getScene window))))))

(deftest decimate-is-offered-test
  (let [robot (ui/robot)
        chain #(get-in (app-state) [:chains :projector-effects :a :items])]
    (with-projector!)
    (ui/click! robot "#projectors-tab")
    (testing "in a laser's Add Effect menu"
      ;; Below the strip, the inspector scrolls to its calibration chain
      (ui/scroll-into-view! "#projector-add-effect-menu")
      (is (some #{"Decimate"} (menu-texts "#projector-add-effect-menu")))
      (ui/click! robot "#projector-add-effect-menu")
      (ui/click! robot "#projector-add-effect-decimate")
      (ui/wait-for! #(= [:decimate] (mapv :effect-id (chain))))
      (is (= {:kpps 25 :fps 30} (:params (first (chain)))) "at 25 kpps and 30 fps")
      (testing "the chain names it, and its editor has its two parameters"
        (ui/wait-for! #(some? (labeled-in (ui/main-window) "Decimate")))
        (ui/click-node! robot (labeled-in (ui/main-window) "Decimate"))
        (ui/wait-for! #(some? (labeled-in (ui/main-window) "Edit: Decimate")))
        (is (some? (labeled-in (ui/main-window) "Scan rate (kpps)")))
        (is (some? (labeled-in (ui/main-window) "Target fps")))))
    (testing "and in the effect bank's Shape tab"
      (events/dispatch! {:event/type :cue-chain/open-editor :col 0 :row 0})
      (ui/wait-for! #(some? (window-titled "Cue Chain Editor")))
      (ui/wait-for! #(some? (labeled-in (window-titled "Cue Chain Editor") "Decimate")))
      (is (ui/has-style-class? "#shape-tab" "tab-btn-active") "the tab it shows"))))

