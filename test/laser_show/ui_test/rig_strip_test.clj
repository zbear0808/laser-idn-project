(ns laser-show.ui-test.rig-strip-test
  "The rig strip on the Projectors tab (plans/2026-11-19-zones/04 §7): a card
   per output in rig order, its On box, reordering by drag and drop, by ◀ ▶
   and by Alt+← / Alt+→, the group rows under the cards and the audience
   offer in the inspector."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]
            [laser-show.views.components.drag-drop-cell :as dnd])
  (:import [javafx.event Event]
           [javafx.scene Node]
           [javafx.scene.input KeyCode KeyEvent TransferMode]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(defn- app-state [] (state/get-raw-state))

(defn- device
  "A device as the network scan reports it, with these outputs."
  [address unit-id & service-ids]
  {:address address :host-name "DAC" :unit-id unit-id :port 7255 :status {} :protocol-version {:major 1 :minor 0}
   :services (mapv (fn [s] {:service-id s :name (str "Head " s)}) service-ids) :relays []})

(defn- with-lasers!
  "Projectors :a :b :c at 10.0.0.1-3, on, so in that order."
  []
  (state/swap-state! assoc :projectors
                     (into {} (for [[id n] [[:a 1] [:b 2] [:c 3]]]
                                [id {:name (str "Laser " (str/upper-case (name id))) :host (str "10.0.0." n) :port 7255
                                     :service-id 1 :enabled? true :zone-groups [] :exclude-from-all? false
                                     :output-config {:color-bit-depth 8 :xy-bit-depth 16}}]))))

(defn- shown-order
  "The ids of the cards on the strip, left to right."
  []
  (->> (ui/query-all ".rig-card")
       (map (fn [^Node n] [(first (ui/scene-bounds n)) (str/replace-first (ui/on-fx-thread #(.getId n)) "rig-card-" "")]))
       (sort-by first)
       (mapv (comp keyword second))))

(defn- order [] (get-in (app-state) [:rig :order]))

(defn- key-press!
  "A key pressed on the strip, as the keyboard sends it while the strip has
   the focus (the headless robot can't hold Alt)."
  [code & {:keys [alt?]}]
  (let [strip (ui/query "#rig-strip")]
    (ui/on-fx-thread #(Event/fireEvent strip (KeyEvent. KeyEvent/KEY_PRESSED "" "" code false false (boolean alt?) false)))
    (ui/sync-fx!)))

(deftest scan-shows-a-card-per-output-in-ip-order-test
  (let [robot (ui/robot)]
    (events/dispatch! {:event/type :projectors/scan-complete
                       :devices [(device "10.0.0.10" "AA" 1) (device "10.0.0.9" "BB" 1 2) (device "10.0.0.2" "CC" 1)]})
    (ui/click! robot "#projectors-tab")
    ;; by IP as numbers: .9 before .10
    (ui/wait-for! #(= [:projector-10-0-0-2-1 :projector-10-0-0-9-1 :projector-10-0-0-9-2 :projector-10-0-0-10-1]
                      (shown-order)))
    (ui/assert-text! "#rig-card-number-projector-10-0-0-2-1" "–")
    (ui/assert-text! "#rig-header-count" "Tick On on a card to send it output.")
    (is (nil? (ui/query "#discovered-device-10-0-0-9")) "no device list any more: every output is a card")))

(deftest card-on-box-enables-the-output-test
  (let [robot (ui/robot)
        enabled? #(get-in (app-state) [:projectors % :enabled?])]
    (events/dispatch! {:event/type :projectors/scan-complete :devices [(device "10.0.0.5" "AA" 1 2)]})
    (ui/click! robot "#projectors-tab")
    (ui/wait-for! #(some? (ui/query "#rig-card-enable-projector-10-0-0-5-1")))
    (is (false? (enabled? :projector-10-0-0-5-1)) "a found output starts off")
    (ui/check! robot "#rig-card-enable-projector-10-0-0-5-1")
    (ui/wait-for! #(true? (enabled? :projector-10-0-0-5-1)))
    (ui/assert-text! "#rig-card-number-projector-10-0-0-5-1" "1")
    (ui/assert-text! "#rig-header-count" "2 outputs · 1 on")
    (is (false? (enabled? :projector-10-0-0-5-2)) "the other output is untouched")
    (ui/uncheck! robot "#rig-card-enable-projector-10-0-0-5-1")
    (ui/wait-for! #(false? (enabled? :projector-10-0-0-5-1)))
    (ui/assert-text! "#rig-card-number-projector-10-0-0-5-1" "–")))

(deftest no-output-device-gets-a-card-test
  (let [robot (ui/robot)]
    (events/dispatch! {:event/type :projectors/scan-complete :devices [(device "10.0.0.7" "AA")]})
    (ui/click! robot "#projectors-tab")
    (ui/assert-visible! "#rig-card-projector-10-0-0-7-0")
    (ui/check! robot "#rig-card-enable-projector-10-0-0-7-0")
    (ui/wait-for! #(true? (get-in (app-state) [:projectors :projector-10-0-0-7-0 :enabled?])))))

(deftest drop-a-card-before-another-test
  (let [robot (ui/robot)
        width (fn [^Node n] (let [[x0 _ x1 _] (ui/scene-bounds n)] (- x1 x0)))
        drop! (fn [id onto x & opts]
                (apply ui/fire-drop! (ui/query (str "#rig-card-" (name onto))) (dnd/drag-content :rig-card {:projector-id id})
                       x 20 :mode TransferMode/MOVE opts))]
    (with-lasers!)
    (ui/click! robot "#projectors-tab")
    (ui/wait-for! #(= [:a :b :c] (shown-order)))
    (testing "on the left half of a card: before it"
      (is (:completed? (drop! :c :a 5)))
      (ui/wait-for! #(= [:c :a :b] (shown-order)))
      (is (= [:c :a :b] (order)) "the whole order is written"))
    (testing "on the right half: after it"
      (drop! :c :b (- (width (ui/query "#rig-card-b")) 5))
      (ui/wait-for! #(= [:a :b :c] (shown-order))))
    (testing "while it is over a card, a bar shows where it would land, and goes when it leaves"
      (drop! :a :c 5 :drop? false :exit? false)
      (ui/wait-for! #(= {:projector-id :c :side :before} (get-in (app-state) [:rig-ui :drop])))
      (ui/assert-visible! "#rig-drop-bar-before-c")
      (drop! :a :c 5 :drop? false)
      (ui/wait-for! #(nil? (get-in (app-state) [:rig-ui :drop])))
      (is (= [:a :b :c] (order)) "nothing moved"))))

(deftest alt-arrow-moves-the-selected-card-test
  (let [robot (ui/robot)
        selected #(get-in (app-state) [:projector-ui :active-projector])]
    (with-lasers!)
    (ui/click! robot "#projectors-tab")
    (ui/click! robot "#rig-card-a")
    (ui/wait-for! #(= :a (selected)))
    (ui/wait-for! #(ui/on-fx-thread (fn [] (identical? (ui/query "#rig-strip")
                                                       (some-> ^Node (ui/query "#rig-strip") .getScene .getFocusOwner)))))
    (ui/assert-text! "#projector-config-title" "CONFIGURE: Laser A")
    (testing "→ alone moves the selection"
      (key-press! KeyCode/RIGHT)
      (ui/wait-for! #(= :b (selected)))
      (ui/assert-text! "#projector-config-title" "CONFIGURE: Laser B"))
    (testing "Alt+→ moves the card"
      (key-press! KeyCode/RIGHT :alt? true)
      (ui/wait-for! #(= [:a :c :b] (shown-order)))
      (is (= :b (selected)) "and it stays selected"))
    (testing "Alt+← moves it back, ← moves the selection"
      (key-press! KeyCode/LEFT :alt? true)
      (ui/wait-for! #(= [:a :b :c] (shown-order)))
      (key-press! KeyCode/LEFT)
      (ui/wait-for! #(= :a (selected))))
    (testing "at the left end, Alt+← does nothing"
      (key-press! KeyCode/LEFT :alt? true)
      (ui/sync-fx!)
      (is (= [:a :b :c] (shown-order))))))

(deftest move-buttons-follow-the-ends-test
  (let [robot (ui/robot)]
    (with-lasers!)
    (ui/click! robot "#projectors-tab")
    (ui/assert-disabled! "#rig-move-left-button")
    (ui/assert-disabled! "#rig-move-right-button")
    (events/dispatch! {:event/type :projectors/select-projector :projector-id :a})
    (ui/wait-for! #(ui/enabled? "#rig-move-right-button"))
    (ui/assert-disabled! "#rig-move-left-button")
    (ui/click! robot "#rig-move-right-button")
    (ui/wait-for! #(= [:b :a :c] (shown-order)))
    (ui/wait-for! #(ui/enabled? "#rig-move-left-button"))
    (events/dispatch! {:event/type :projectors/select-projector :projector-id :c})
    (ui/wait-for! #(not (ui/enabled? "#rig-move-right-button")))
    (ui/assert-enabled! "#rig-move-left-button")))


;; The group rows under the cards

(defn- mark [row id] (ui/node-text (str "#rig-cell-" row "-" (name id))))

(deftest rows-follow-the-order-test
  (let [robot (ui/robot)]
    (with-lasers!)
    (ui/click! robot "#projectors-tab")
    (ui/wait-for! #(= "◧" (mark "auto-left" :b)))
    (is (= ["■" "◧" "·"] (mapv #(mark "auto-left" %) [:a :b :c])) "three lasers: the middle one is split")
    (is (= ["·" "◨" "■"] (mapv #(mark "auto-right" %) [:a :b :c])))
    (is (= ["·" "■" "·"] (mapv #(mark "auto-center" %) [:a :b :c])))
    (is (= ["■" "·" "■"] (mapv #(mark "auto-odd" %) [:a :b :c])))
    (is (= ["■" "■" "■"] (mapv #(mark "all" %) [:a :b :c])))
    (is (= ["■" "■" "■"] (mapv #(mark "each-laser" %) [:a :b :c])))
    (testing "a card moved: the rows follow"
      (events/dispatch! {:event/type :rig/move-projector :projector-id :c :target :a :side :before})
      (ui/wait-for! #(= [:c :a :b] (shown-order)))
      (ui/wait-for! #(= ["■" "◧" "·"] (mapv (fn [id] (mark "auto-left" id)) [:c :a :b]))))
    (testing "a laser turned off: – in its column, and the others fold without it"
      (ui/uncheck! robot "#rig-card-enable-a")
      (ui/wait-for! #(= "–" (mark "auto-left" :a)))
      (is (= ["■" "–" "·"] (mapv #(mark "auto-left" %) [:c :a :b])) "two lasers: no middle")
      (is (= "–" (ui/node-text "#rig-card-number-a"))))))

(deftest middle-laser-choice-changes-the-rows-test
  (let [robot (ui/robot)
        rig #(get-in (app-state) [:rig %])]
    (with-lasers!)
    (ui/click! robot "#projectors-tab")
    (ui/wait-for! #(= "◧" (mark "auto-left" :b)))
    (ui/assert-enabled! "#rig-middle-choice")
    (ui/select! robot "#rig-middle-choice" ":both")
    (ui/wait-for! #(= :both (rig :middle)))
    (ui/wait-for! #(= ["■" "■"] [(mark "auto-left" :b) (mark "auto-right" :b)]))
    (ui/select! robot "#rig-middle-choice" ":left")
    (ui/wait-for! #(= ["■" "·"] [(mark "auto-left" :b) (mark "auto-right" :b)]))
    (testing "the split width is on while the middle is split or in both"
      (ui/assert-disabled! "#rig-split-width-slider")
      (ui/select! robot "#rig-middle-choice" ":split")
      (ui/wait-for! #(ui/enabled? "#rig-split-width-slider"))
      (ui/slide! robot "#rig-split-width-slider" 0.6)
      (ui/wait-for! #(= 0.6 (rig :split-width)))
      (ui/assert-text! "#rig-split-width-label" "60%"))
    (testing "with an even count there is no middle laser"
      (ui/uncheck! robot "#rig-card-enable-c")
      (ui/wait-for! #(not (ui/enabled? "#rig-middle-choice")))
      (ui/assert-text! "#rig-middle-hint" "Used with 3, 5, 7… lasers")
      (ui/assert-disabled! "#rig-split-width-slider"))))

(deftest custom-row-toggles-membership-test
  (let [robot (ui/robot)
        groups #(get-in (app-state) [:projectors % :zone-groups])]
    (with-lasers!)
    (state/swap-state! assoc-in [:projectors :c :enabled?] false)
    (ui/click! robot "#projectors-tab")
    (ui/wait-for! #(= "□" (mark "graphics" :a)))
    ;; The rows are under the cards: at 1200 × 800 the strip shows the cards
    (ui/scroll-into-view! "#rig-cell-graphics-a")
    (ui/click! robot "#rig-cell-graphics-a")
    (ui/wait-for! #(= [:graphics] (groups :a)))
    (ui/wait-for! #(= "■" (mark "graphics" :a)))
    (testing "a laser that is off can be put in a group too"
      (ui/click! robot "#rig-cell-graphics-c")
      (ui/wait-for! #(= [:graphics] (groups :c))))
    (ui/click! robot "#rig-cell-graphics-a")
    (ui/wait-for! #(= [] (groups :a)))
    (ui/wait-for! #(= "□" (mark "graphics" :a)))
    (testing "+ Group opens the New Zone Group dialog"
      (ui/scroll-into-view! "#rig-add-group-button")
      (ui/click! robot "#rig-add-group-button")
      (ui/wait-for! #(some #{"New Zone Group"} (ui/window-titles))))))

(deftest all-row-click-excludes-test
  (let [robot (ui/robot)
        excluded? #(get-in (app-state) [:projectors % :exclude-from-all?])]
    (with-lasers!)
    (ui/click! robot "#projectors-tab")
    (ui/wait-for! #(= "■" (mark "all" :b)))
    (ui/scroll-into-view! "#rig-cell-all-b")
    (ui/click! robot "#rig-cell-all-b")
    (ui/wait-for! #(true? (excluded? :b)))
    (ui/wait-for! #(= "×" (mark "all" :b)))
    (is (= "–" (ui/node-text "#rig-card-number-b")) "no place among the lasers in All")
    (is (= ["■" "×" "·"] (mapv #(mark "auto-left" %) [:a :b :c])) "the other two fold without it (D-33)")
    (is (= "■" (mark "each-laser" :b)) "it keeps its own group")
    (ui/click! robot "#rig-cell-all-b")
    (ui/wait-for! #(false? (excluded? :b)))
    (ui/wait-for! #(= "◧" (mark "auto-left" :b)))))

(deftest row-name-opens-the-group-test
  (let [robot (ui/robot)]
    (with-lasers!)
    (ui/click! robot "#projectors-tab")
    (ui/scroll-into-view! "#rig-row-auto-left")
    (ui/click! robot "#rig-row-auto-left")
    (ui/wait-for! #(= :zones (get-in (app-state) [:ui :active-tab])))
    (is (= :auto/left (get-in (app-state) [:zone-group-ui :selected-group])))))


;; The inspector: identity, tags, Exclude from All

(deftest audience-tag-offers-exclude-from-all-test
  (let [robot (ui/robot)
        projector #(get-in (app-state) [:projectors :b])]
    (with-lasers!)
    (ui/click! robot "#projectors-tab")
    (ui/click! robot "#rig-card-b")
    (ui/wait-for! #(some? (ui/query "#projector-identity-label")))
    (ui/assert-text! "#projector-identity-label"
                     "Identity: IP 10.0.0.2 · output 1 (the device sends no unit id, so a new address makes it a new laser)")
    (is (nil? (ui/query "#projector-audience-in-all")))
    (testing "Scans the audience, while in All: an amber line offers to exclude it, and nothing happens by itself"
      (ui/scroll-into-view! "#projector-tag-crowd-scanning")
      (ui/check! robot "#projector-tag-crowd-scanning")
      (ui/wait-for! #(= #{:crowd-scanning} (:tags (projector))))
      (ui/wait-for! #(some? (ui/query "#projector-audience-in-all")))
      (ui/assert-text! "#projector-audience-in-all" "This laser is in All, so that's every cue that plays on All.")
      (is (not (:exclude-from-all? (projector)))))
    (testing "Exclude from All, from the offer"
      (ui/scroll-into-view! "#projector-audience-exclude-button")
      (ui/click! robot "#projector-audience-exclude-button")
      (ui/wait-for! #(true? (:exclude-from-all? (projector))))
      (ui/wait-for! #(nil? (ui/query "#projector-audience-in-all")))
      (ui/wait-for! #(ui/selected? "#projector-exclude-from-all"))
      (ui/wait-for! #(= "×" (mark "all" :b))))
    (testing "and back in All from its box: the offer comes back"
      (ui/scroll-into-view! "#projector-exclude-from-all")
      (ui/uncheck! robot "#projector-exclude-from-all")
      (ui/wait-for! #(false? (:exclude-from-all? (projector))))
      (ui/wait-for! #(some? (ui/query "#projector-audience-in-all"))))
    (testing "Graphics is a tag too"
      (ui/scroll-into-view! "#projector-tag-graphics")
      (ui/check! robot "#projector-tag-graphics")
      (ui/wait-for! #(= #{:crowd-scanning :graphics} (:tags (projector)))))))

(deftest card-menu-turns-on-every-output-test
  (let [robot (ui/robot)
        enabled? #(get-in (app-state) [:projectors % :enabled?])]
    (events/dispatch! {:event/type :projectors/scan-complete :devices [(device "10.0.0.5" "AA" 1 2) (device "10.0.0.6" "BB" 1)]})
    (ui/click! robot "#projectors-tab")
    (ui/wait-for! #(some? (ui/query "#rig-card-projector-10-0-0-5-1")))
    (ui/right-click-node! robot (ui/query "#rig-card-projector-10-0-0-5-1"))
    (ui/wait-for! #(some? (ui/query "#rig-card-identify-projector-10-0-0-5-1")))
    (ui/click! robot "#rig-card-enable-all-projector-10-0-0-5-1")
    (ui/wait-for! #(and (true? (enabled? :projector-10-0-0-5-1)) (true? (enabled? :projector-10-0-0-5-2))))
    (is (false? (enabled? :projector-10-0-0-6-1)) "another device's output stays off")
    (testing "a card of a device with one output has no such item"
      (ui/right-click-node! robot (ui/query "#rig-card-projector-10-0-0-6-1"))
      (ui/wait-for! #(some? (ui/query "#rig-card-identify-projector-10-0-0-6-1")))
      (is (nil? (ui/query "#rig-card-enable-all-projector-10-0-0-6-1")))
      (ui/press! robot KeyCode/ESCAPE))))
