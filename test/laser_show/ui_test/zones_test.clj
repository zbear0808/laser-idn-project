(ns laser-show.ui-test.zones-test
  "Zones, zone maps and the one Target control (zones plan Z4, 06 §7): the
   Target chip in the editors, the grid's badges and column targets, the zone
   map pads, the Zones and maps section."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.input :as input]
            [laser-show.events.handlers.input-mapper :as input-mapper]
            [laser-show.input.router :as input-router]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.state.core :as state]
            [laser-show.state.history :as history]
            [laser-show.timeline.shows :as shows]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.show-editor :as show-editor]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.css PseudoClass]
           [javafx.scene Node]
           [javafx.scene.control CheckBox ComboBox Control CustomMenuItem Labeled Menu MenuButton MenuItem]
           [javafx.stage Window]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(defn- app-state [] (state/get-raw-state))

(defn- cue [col row] (get-in (app-state) [:chains :cue-chains [col row]]))

(defn- open-editor!
  "The cue chain editor on [col row]; a cue with one item opens with it
   selected, so its Target chip shows."
  [col row]
  (events/dispatch! {:event/type :cue-chain/open-editor :col col :row row})
  (ui/wait-for! #(some? (ui/query "#target-chip-cue"))))

(defn- chip-items
  "The items of the menu button found by selector."
  [selector]
  (let [^MenuButton chip (ui/query selector)]
    (ui/on-fx-thread #(vec (.getItems chip)))))

(defn- item-texts [selector]
  (ui/on-fx-thread #(mapv (fn [^MenuItem i] (.getText i)) (chip-items selector))))

(defn- fire-item!
  "Fire the item of the chip at selector whose text, without its tick, is
   text, as a click on it does."
  [selector text]
  (let [item (some #(when (= text (str/trim (str/replace (str (ui/on-fx-thread (fn [] (.getText ^MenuItem %)))) #"^✓ " ""))) %)
                   (chip-items selector))]
    (is (some? item) (str "an item " (pr-str text) " in " (item-texts selector)))
    (when item
      (ui/on-fx-thread #(.fire ^MenuItem item))
      (ui/sync-fx!))))

(defn- combine-boxes
  "{text check-box} of the chip's Combine submenu."
  [selector]
  (let [^Menu combine (some #(when (and (instance? Menu %) (= "Combine" (ui/on-fx-thread (fn [] (.getText ^Menu %))))) %)
                            (chip-items selector))]
    (ui/on-fx-thread #(into {} (map (fn [^CustomMenuItem i] (let [^CheckBox b (.getContent i)] [(.getText b) b])))
                            (.getItems combine)))))

(defn- tick! [selector text]
  (let [box (get (combine-boxes selector) text)]
    (is (some? box) (str text " in Combine"))
    (when box
      (ui/on-fx-thread #(.fire ^CheckBox box))
      (ui/sync-fx!))))

(defn- texts
  "The texts of the labels and buttons found by selector."
  [selector]
  (ui/on-fx-thread #(into [] (keep (fn [n] (when (instance? Labeled n) (.getText ^Labeled n)))) (ui/query-all selector))))

(defn- tooltip-of [selector]
  (ui/on-fx-thread #(some-> ^Control (ui/query selector) .getTooltip .getText)))

(defn- item-target [] (get-in (cue 0 0) [:items 0 :target]))


;; The Target chip (06 §7.1, §7.2)

(deftest item-chip-sets-a-zone-test
  (let [robot (ui/robot)]
    (open-editor! 0 0)
    (ui/wait-for! #(some? (ui/query "#target-chip-item")))
    (ui/assert-text! "#target-chip-item" "(All)")
    (testing "the chip opens the Target menu"
      (ui/click! robot "#target-chip-item")
      (ui/wait-for! #(some? (ui/query ".menu-item")))
      (ui/press! robot javafx.scene.input.KeyCode/ESCAPE))
    (is (= ["✓ Inherit (All)" "   All" "ZONES · map: Left / Right" "   A → Left" "   B → Right" "   New zone (C → Left)" "GROUPS"]
           (take 7 (item-texts "#target-chip-item"))))
    (fire-item! "#target-chip-item" "A → Left")
    (ui/wait-for! #(= :zone/a (item-target)))
    (ui/assert-text! "#target-chip-item" "A → Left")
    (ui/wait-for! #(some #{"Circle [A → Left]"} (texts ".chain-item-name")))
    (is (some #{"Circle [A → Left]"} (texts ".chain-item-name")) "the list says where the item goes")
    (testing "a group, pinned"
      (fire-item! "#target-chip-item" "Graphics")
      (ui/wait-for! #(= :graphics (item-target)))
      (ui/assert-text! "#target-chip-item" "Graphics"))
    (testing "Inherit takes it away"
      (fire-item! "#target-chip-item" "Inherit (All)")
      (ui/wait-for! #(not (contains? (get-in (cue 0 0) [:items 0]) :target)))
      (ui/assert-text! "#target-chip-item" "(All)"))))

(deftest chip-says-where-an-inherited-target-comes-from-test
  (events/write-state! #(-> %
                            (assoc-in [:grid :column-targets] {0 :zone/a})
                            (assoc-in [:zones :labels :zone/a] "Beams")))
  (open-editor! 0 0)
  (ui/assert-text! "#target-chip-item" "(A · Beams → Left)")
  (ui/assert-text! "#target-from-item" "from column 1")
  (ui/assert-text! "#target-chip-cue" "(A · Beams → Left)")
  (ui/assert-text! "#target-from-cue" "from column 1")
  (is (= "✓ Inherit (A · Beams → Left · from column 1)" (first (item-texts "#target-chip-item"))))
  (testing "the cue's own target: the item says it comes from the cue"
    (fire-item! "#target-chip-cue" "B → Right")
    (ui/wait-for! #(= :zone/b (:target (cue 0 0))))
    (ui/assert-text! "#target-chip-item" "(B → Right)")
    (ui/assert-text! "#target-from-item" "from the cue")
    (ui/assert-text! "#target-chip-cue" "B → Right")
    (is (empty? (ui/query-all "#target-from-cue")) "the cue's own: no from"))
  (testing "neither: the show default"
    (events/write-state! #(-> %
                              (update-in [:chains :cue-chains [0 0]] dissoc :target)
                              (assoc-in [:grid :column-targets] {})
                              (assoc-in [:config :default-target] :graphics)))
    (ui/assert-text! "#target-chip-item" "(Graphics)")
    (ui/assert-text! "#target-from-item" "from the show default")))

(deftest combine-makes-a-union-test
  (open-editor! 0 0)
  (tick! "#target-chip-item" "A → Left")
  (ui/wait-for! #(= :zone/a (item-target)))
  (tick! "#target-chip-item" "B → Right")
  (ui/wait-for! #(= [:zone/a :zone/b] (item-target)))
  (ui/assert-text! "#target-chip-item" "A + B → Left + Right")
  (is (= [true true] (mapv #(ui/on-fx-thread (fn [] (.isSelected ^CheckBox %)))
                           (map (combine-boxes "#target-chip-item") ["A → Left" "B → Right"])))
      "both ticked")
  (testing "unticking one leaves the other"
    (tick! "#target-chip-item" "A → Left")
    (ui/wait-for! #(= :zone/b (item-target)))))

(deftest new-zone-takes-the-next-letter-test
  (open-editor! 0 0)
  (fire-item! "#target-chip-item" "New zone (C → Left)")
  (ui/wait-for! #(= :zone/c (item-target)))
  (ui/wait-for! #(some #{"   New zone (D → Right)"} (item-texts "#target-chip-item")))
  (is (= ["   A → Left" "   B → Right" "✓ C → Left" "   New zone (D → Right)"]
         (filterv #(and % (re-find #"→" %) (not (str/starts-with? % "Pin"))) (item-texts "#target-chip-item")))
      "A and B, the zone the show uses now, then the next letter (Z4-Q3 A)"))

(deftest pin-freezes-the-lane-test
  (events/write-state! assoc-in [:grid :column-targets] {0 :zone/a})
  (open-editor! 0 0)
  (fire-item! "#target-chip-item" "Pin: A → Left")
  (ui/wait-for! #(= :auto/left (item-target)))
  (ui/assert-text! "#target-chip-item" "Left")
  (testing "the map switches; the pinned item stays on Left"
    (events/write-state! assoc-in [:zone-map :active] :map/odd-even)
    (ui/wait-for! #(= "(A → Odd)" (ui/node-text "#target-chip-cue")))
    (ui/assert-text! "#target-chip-item" "Left")
    (is (= :auto/left (item-target)))))

(deftest footer-says-target-test
  (open-editor! 0 0)
  (is (some #{"Target:"} (texts ".dialog-footer .target-field-label")))
  (is (= "Where the cue's content goes, unless an item, a group or a track sets its own" (tooltip-of "#target-chip-cue")))
  (ui/assert-text! "#target-chip-cue" "(All)")
  (fire-item! "#target-chip-cue" "Graphics")
  (ui/wait-for! #(= :graphics (:target (cue 0 0))))
  (ui/assert-text! "#target-chip-cue" "Graphics")
  (ui/assert-text! "#target-chip-item" "(Graphics)")
  (ui/assert-text! "#target-from-item" "from the cue")
  (testing "an undo step: Change Cue Target"
    (is (= "Change Cue Target" (history/undo-label (:history (app-state)))))))

(defn- row-target-items
  "The items of the Target submenu of the context menu of the cue list's
   row whose name starts with name."
  [name]
  (let [^Node card (some #(when (str/starts-with? (str (ui/on-fx-thread (fn [] (some-> ^Labeled (.lookup ^Node % ".chain-item-name") .getText))))
                                                  name)
                            %)
                         (ui/query-all "#list-editor-cue-chain-0-0 .chain-item"))]
    (ui/on-fx-thread
     #(some (fn [i] (when (and (instance? Menu i) (= "Target" (.getText ^Menu i))) (vec (.getItems ^Menu i))))
            (some-> ^Control (.getParent card) .getContextMenu .getItems)))))

(deftest a-rows-own-target-menu-sets-its-item-test
  ;; Each row's Target menu is a component of its own (reactivity plan 34):
  ;; it follows its item when the rows move
  (open-editor! 0 0)
  (events/dispatch! {:event/type :cue-chain/add-preset :col 0 :row 0 :item-id :square})
  (ui/wait-for! #(= 2 (count (:items (cue 0 0)))))
  (let [[circle square] (:items (cue 0 0))]
    (events/dispatch! {:event/type :list/perform-drop :component-id [:cue-chain 0 0]
                       :dragging-ids #{(:id square)} :target-id (:id circle) :drop-position :before
                       :items-path [:chains :cue-chains [0 0] :items]
                       :on-change-event :chain/set-items :on-change-params {:domain :cue-chains :entity-key [0 0]}})
    (ui/wait-for! #(= (mapv :id [square circle]) (mapv :id (:items (cue 0 0)))))
    (ui/wait-for! #(some (fn [i] (= "   B → Right" (ui/on-fx-thread (fn [] (.getText ^MenuItem i)))))
                         (row-target-items "Circle")))
    (let [item (some (fn [i] (when (= "   B → Right" (ui/on-fx-thread (fn [] (.getText ^MenuItem i)))) i))
                     (row-target-items "Circle"))]
      (ui/on-fx-thread #(.fire ^MenuItem item)))
    (ui/wait-for! #(= :zone/b (:target (second (:items (cue 0 0))))))
    (is (nil? (:target (first (:items (cue 0 0))))) "the square, moved before it, as it was")
    (ui/wait-for! #(some #{"Circle [B → Right]"} (texts ".chain-item-name")))))

(deftest a-target-choice-behind-a-reorder-sets-its-own-item-test
  ;; Follow-up R2: a row's menu sent its item's path, so a choice made
  ;; before a reorder was handled, and handled after it, set the item now
  ;; at that place. The drop and the choice here are queued in one FX turn,
  ;; before the editor renders the reorder
  (open-editor! 0 0)
  (events/dispatch! {:event/type :cue-chain/add-preset :col 0 :row 0 :item-id :square})
  (ui/wait-for! #(= 2 (count (:items (cue 0 0)))))
  (let [[circle square] (:items (cue 0 0))
        item (some (fn [i] (when (= "   B → Right" (ui/on-fx-thread (fn [] (.getText ^MenuItem i)))) i))
                   (row-target-items "Circle"))]
    (is (some? item) "Circle's Target menu offers B → Right")
    (ui/on-fx-thread #(do (events/dispatch! {:event/type :list/perform-drop :component-id [:cue-chain 0 0]
                                             :dragging-ids #{(:id square)} :target-id (:id circle) :drop-position :before
                                             :items-path [:chains :cue-chains [0 0] :items]
                                             :on-change-event :chain/set-items
                                             :on-change-params {:domain :cue-chains :entity-key [0 0]}})
                          (.fire ^MenuItem item)))
    (ui/wait-for! #(= (mapv :id [square circle]) (mapv :id (:items (cue 0 0)))))
    (is (events/flush!))
    (is (= [[(:id square) nil] [(:id circle) :zone/b]] (mapv (juxt :id :target) (:items (cue 0 0))))
        "the circle has it, now second; the square nothing")))


;; The timeline (06 §7.2)

(def ^:private timeline-cue
  {:editor-type :timeline
   :tracks [{:id :beat :type :group :name "Beat" :target :zone/c :collapsed? false
             :items [{:id :kick :type :track :name "Kick" :color "#E91E63"}]}
            {:id :lead :type :track :name "Lead" :color "#2196F3"}]
   :items [{:type :preset :id :c1 :preset-id :circle :params {} :effects [] :enabled? true
            :track-id :kick :timeline/start 0.0 :timeline/duration 4.0}
           {:type :preset :id :c2 :preset-id :wave :params {} :effects [] :enabled? true
            :track-id :lead :timeline/start 0.0 :timeline/duration 4.0}]})

(def ^:private show-id #uuid "6f1c0000-0000-4000-8000-0000000000a1")

(defn- show [] (get-in (app-state) (shows/show-path show-id)))

(defn- track-target [id]
  (:target (some #(when (= id (:id %)) %) (tree-seq map? :items {:items (:tracks (show))}))))

(defn- clip-target [id]
  (:target (some #(when (= id (:id %)) %) (:items (show)))))

(defn- open-timeline!
  "The editor on timeline-cue, a show in the library."
  []
  (show-editor/open-show! show-id timeline-cue)
  (ui/wait-for! #(some? (ui/query "#timeline-track-target-2"))))

(defn- header-target-menu
  "The Target submenu of header row idx's context menu."
  [idx]
  (let [^Control row (ui/query (str "#timeline-track-header-" idx))]
    (ui/on-fx-thread #(some (fn [i] (when (and (instance? Menu i) (= "Target" (.getText ^Menu i))) i))
                            (some-> row .getContextMenu .getItems)))))

(deftest timeline-chip-and-inspector-set-targets-test
  (open-timeline!)
  (testing "the header's chips, compact: the folder's own, the track in it inheriting it, a track with nothing"
    (ui/assert-text! "#timeline-track-target-0" "C")
    (ui/assert-text! "#timeline-track-target-1" "(C)")
    (ui/assert-text! "#timeline-track-target-2" "(All)"))
  (testing "a header chip sets the track's target"
    (fire-item! "#timeline-track-target-2" "B → Right")
    (ui/wait-for! #(= :zone/b (track-target :lead)))
    (ui/assert-text! "#timeline-track-target-2" "B"))
  (testing "the row's menu: Target ▸, the same menu"
    (let [menu (header-target-menu 2)]
      (is (some? menu) "a Target submenu")
      (when menu
        (let [inherit (ui/on-fx-thread #(first (.getItems ^Menu menu)))]
          (is (= "   Inherit (All)" (ui/on-fx-thread #(.getText ^MenuItem inherit))))
          (ui/on-fx-thread #(.fire ^MenuItem inherit))
          (ui/wait-for! #(nil? (track-target :lead)))
          (ui/assert-text! "#timeline-track-target-2" "(All)")))))
  (testing "the inspector: a track inherits its folder's"
    (events/dispatch! {:event/type :timeline/select-track :show-id show-id :track-id :kick})
    (ui/wait-for! #(= "TRACK PROPERTIES" (ui/node-text "#timeline-inspector-title")))
    (ui/wait-for! #(some? (ui/query "#target-chip-track")))
    (ui/assert-text! "#target-chip-track" "(C → Left)")
    (ui/assert-text! "#target-from-track" "from folder Beat")
    (fire-item! "#target-chip-track" "A → Left")
    (ui/wait-for! #(= :zone/a (track-target :kick)))
    (ui/assert-text! "#target-chip-track" "A → Left")
    (ui/assert-text! "#timeline-track-target-1" "A")
    (is (= "Change Track Target" (history/undo-label (:history (app-state))))))
  (testing "the inspector: a clip inherits its track's"
    (events/dispatch! {:event/type :timeline/select-items :show-id show-id :ids #{:c1} :mode :replace})
    (ui/wait-for! #(= "CLIP" (ui/node-text "#timeline-inspector-title")))
    (ui/wait-for! #(some? (ui/query "#target-chip-clip")))
    (ui/assert-text! "#target-chip-clip" "(A → Left)")
    (ui/assert-text! "#target-from-clip" "from track Kick")
    (fire-item! "#target-chip-clip" "Graphics")
    (ui/wait-for! #(= :graphics (clip-target :c1)))
    (ui/assert-text! "#target-chip-clip" "Graphics")
    (is (= "Change Clip Target" (history/undo-label (:history (app-state)))))))


;; The grid: badges and column targets (06 §7.3)

(defn- cell-tooltip
  "The tooltip of the cue cell at [col row]: the label around it holds it."
  [col row]
  (ui/on-fx-thread #(let [parent (some-> ^javafx.scene.Node (ui/query (format "#grid-cell-%d-%d" col row)) .getParent)]
                      (when (instance? Control parent)
                        (some-> ^Control parent .getTooltip .getText)))))

(defn- has-class? [selector cls]
  (ui/on-fx-thread #(boolean (some-> ^javafx.scene.Node (ui/query selector) .getStyleClass (.contains cls)))))

(deftest grid-badge-and-column-target-test
  (events/write-state! #(-> %
                            (assoc-in [:chains :cue-chains [0 0] :items 0 :target] :zone/a)
                            (assoc-in [:chains :cue-chains [1 0]] {:items [{:type :preset :id :b1 :preset-id :wave
                                                                            :params {} :effects []}]})))
  (testing "a cue's badge, top right, and its line in the cell's tooltip"
    (ui/wait-for! #(some? (ui/query "#grid-cell-badge-0-0")))
    (is (= ["A"] (texts "#grid-cell-badge-0-0 .label")))
    (is (not (has-class? "#grid-cell-badge-0-0" "grid-cell-target-badge-inherited")))
    (is (= "A → Left (map: Left / Right)" (cell-tooltip 0 0)))
    (is (nil? (ui/query "#grid-cell-badge-1-0")) "All: no badge"))
  (testing "a column's number sets its default"
    (ui/assert-text! "#grid-column-number-1" "2")
    (is (= "Column 2: no default (cues use the show default: All)" (tooltip-of "#grid-column-number-1")))
    (fire-item! "#grid-column-number-1" "B → Right")
    (ui/wait-for! #(= :zone/b (get-in (app-state) [:grid :column-targets 1])))
    (ui/assert-text! "#grid-column-number-1" "2 · B")
    (is (= "Column 2: cues here without a target of their own use B → Right" (tooltip-of "#grid-column-number-1")))
    (is (= "Change Column Target" (history/undo-label (:history (app-state))))))
  (testing "the cue there inherits it: its badge is dim, and its tooltip says from where"
    (ui/wait-for! #(some? (ui/query "#grid-cell-badge-1-0")))
    (is (= ["B"] (texts "#grid-cell-badge-1-0 .label")))
    (is (has-class? "#grid-cell-badge-1-0" "grid-cell-target-badge-inherited"))
    (is (= "B → Right (map: Left / Right) · from column 2" (cell-tooltip 1 0))))
  (testing "a group: pinned"
    (fire-item! "#grid-column-number-1" "Left")
    (ui/wait-for! #(= :auto/left (get-in (app-state) [:grid :column-targets 1])))
    (ui/assert-text! "#grid-column-number-1" "2 · pinned L")
    (ui/wait-for! #(= ["L"] (texts "#grid-cell-badge-1-0 .label")))
    (is (= "Pinned: ignores the zone map · from column 2" (cell-tooltip 1 0))))
  (testing "Inherit takes it away"
    (fire-item! "#grid-column-number-1" "Inherit (All)")
    (ui/wait-for! #(not (contains? (get-in (app-state) [:grid :column-targets]) 1)))
    (ui/assert-text! "#grid-column-number-1" "2")
    (ui/wait-for! #(nil? (ui/query "#grid-cell-badge-1-0")))))



;; Zone map pads (06 §7.4)

(defn- uses-zone-a! []
  (events/write-state! assoc-in [:chains :cue-chains [0 0] :items 0 :target] :zone/a))

(defn- active-map [] (get-in (app-state) [:zone-map :active]))

(defn- blink? [selector]
  (ui/on-fx-thread #(boolean (some-> ^Node (ui/query selector) .getPseudoClassStates
                                     (.contains (PseudoClass/getPseudoClass "blink"))))))

(defn- open-perform! []
  (events/dispatch! {:event/type :ui/set-active-tab :tab-id :perform})
  (ui/wait-for! #(ui/visible? "#vibe-pad-row")))

(defn- with-input-mapper
  "(f) with the input mapper running: start-test! doesn't start it, and a
   learned note reaches the app only through it (perform-test's fixture)."
  [f]
  (input-mapper/set-dispatch-fn! events/dispatch!)
  (input-mapper/start-mapper!)
  (try
    (f)
    (finally
      (input-mapper/stop-mapper!)
      (input-mapper/set-dispatch-fn! nil))))

(defn- note [type n]
  {:type type :source :midi :channel 0 :note n :velocity (if (= :note-on type) 1.0 0.0)
   :timestamp (System/currentTimeMillis)})

(deftest map-pad-switches-the-map-test
  (let [robot (ui/robot)]
    (testing "no zone anywhere: no strip"
      (is (not (ui/visible? "#zone-map-strip"))))
    (uses-zone-a!)
    (ui/wait-for! #(ui/visible? "#zone-map-chip-odd-even"))
    (ui/assert-text! "#zone-map-chip-left-right" "● Left / Right")
    (ui/assert-text! "#zone-map-chip-odd-even" "Odd / Even")
    (is (has-class? "#zone-map-chip-left-right" "zone-map-pad-active"))
    (let [undo-depth (count (get-in (app-state) [:history :undo-stack]))]
      (ui/press-node! robot "#zone-map-chip-odd-even")
      (ui/release-node! robot "#zone-map-chip-odd-even")
      (testing "no clock thread: at once"
        (ui/wait-for! #(= :map/odd-even (active-map)))
        (is (nil? (get-in (app-state) [:zone-map :pending]))))
      (ui/assert-text! "#zone-map-chip-odd-even" "● Odd / Even")
      (is (has-class? "#zone-map-chip-odd-even" "zone-map-pad-active"))
      (is (not (has-class? "#zone-map-chip-left-right" "zone-map-pad-active")))
      (is (= undo-depth (count (get-in (app-state) [:history :undo-stack]))) "a switch is live: no undo step"))
    (testing "the cells name the new lanes"
      (ui/wait-for! #(= "A → Odd (map: Odd / Even)" (cell-tooltip 0 0))))
    (testing "on Perform, the pads say so"
      (open-perform!)
      (ui/wait-for! #(ui/visible? "#zone-map-pad-odd-even"))
      (ui/assert-text! "#zone-map-pad-odd-even-state" "now")
      (is (has-class? "#zone-map-pad-odd-even" "zone-map-pad-active"))
      (is (not-any? #(.isFocusTraversable ^Node %) (ui/query-all ".zone-map-pad")) "Space or Enter never presses one")
      (ui/press-node! robot "#zone-map-pad-unison")
      (ui/release-node! robot "#zone-map-pad-unison")
      (ui/wait-for! #(= :map/unison (active-map)))
      (ui/assert-text! "#zone-map-pad-unison-state" "now"))))

(deftest pending-pad-blinks-test
  (uses-zone-a!)
  (events/write-state! assoc-in [:zone-map :pending] {:id 7 :map :map/odd-even :at-beat 1.0e6 :bar? false})
  (ui/wait-for! #(ui/visible? "#zone-map-chip-odd-even"))
  (testing "pending: amber, and it says so"
    (is (has-class? "#zone-map-chip-odd-even" "zone-map-pad-pending"))
    (ui/assert-text! "#zone-map-chip-odd-even" "Odd / Even ◌"))
  (testing "no clock: it stays lit"
    (is (not (blink? "#zone-map-chip-odd-even"))))
  (testing "while the clock runs it blinks, and only it"
    (fixtures/with-clock
     (fn [_]
       (ui/wait-for! #(blink? "#zone-map-chip-odd-even"))
       (ui/wait-for! #(not (blink? "#zone-map-chip-odd-even")))
       (ui/wait-for! #(blink? "#zone-map-chip-odd-even"))
       (is (not (blink? "#zone-map-chip-left-right"))))))
  (testing "on Perform: next beat, or next bar"
    (open-perform!)
    (ui/wait-for! #(ui/visible? "#zone-map-pad-odd-even"))
    (ui/assert-text! "#zone-map-pad-odd-even-state" "next beat")
    (is (has-class? "#zone-map-pad-odd-even" "zone-map-pad-pending"))
    (events/write-state! assoc-in [:zone-map :pending :bar?] true)
    (ui/assert-text! "#zone-map-pad-odd-even-state" "next bar")))

(deftest map-pad-learns-a-note-test
  (with-input-mapper
    (fn []
      (let [robot (ui/robot)
            k36 {:source :midi :channel 0 :note 36}]
        (uses-zone-a!)
        (open-perform!)
        (ui/wait-for! #(ui/visible? "#zone-map-pad-odd-even"))
        (ui/assert-text! "#zone-map-pad-odd-even-keys" "Right-click to learn a control")
        (ui/right-click-node! robot (ui/query "#zone-map-pad-odd-even"))
        (ui/click! robot "#zone-map-pad-odd-even-learn")
        (ui/wait-for! #(= {:type :zone-map :target :map/odd-even} (input/learning-target (app-state))))
        (is (has-class? "#zone-map-pad-odd-even" "zone-map-pad-learning"))
        (is (= :map/left-right (active-map)) "the menu pressed nothing")
        (input-router/dispatch! (note :note-on 36))
        (ui/wait-for! #(= {:type :zone-map :target :map/odd-even} (get-in (app-state) [:config :input :trigger-map k36])))
        (ui/assert-text! "#zone-map-pad-odd-even-keys" "MIDI Ch 1 Note 36")
        (ui/wait-for! #(not (has-class? "#zone-map-pad-odd-even" "zone-map-pad-learning")))
        (input-router/dispatch! (note :note-off 36))
        (testing "the key presses the pad"
          (input-router/dispatch! (note :note-on 36))
          (ui/wait-for! #(= :map/odd-even (active-map)))
          (input-router/dispatch! (note :note-off 36)))
        (testing "Clear control"
          (ui/right-click-node! robot (ui/query "#zone-map-pad-odd-even"))
          (ui/click! robot "#zone-map-pad-odd-even-clear")
          (ui/wait-for! #(nil? (get-in (app-state) [:config :input :trigger-map k36])))
          (ui/assert-text! "#zone-map-pad-odd-even-keys" "Right-click to learn a control"))))))


;; Zones and maps on the Zone Groups tab (06 §7.5)

(defn- open-zones-tab! []
  (events/dispatch! {:event/type :ui/set-active-tab :tab-id :zones})
  (ui/wait-for! #(ui/visible? "#zone-maps-panel")))

(deftest zone-maps-section-edits-a-map-test
  (let [robot (ui/robot)]
    (uses-zone-a!)
    (open-zones-tab!)
    (testing "the show's default target"
      (ui/assert-text! "#target-chip-show" "All")
      (fire-item! "#target-chip-show" "Graphics")
      (ui/wait-for! #(= :graphics (get-in (app-state) [:config :default-target])))
      (ui/assert-text! "#target-chip-show" "Graphics"))
    (testing "a zone's label"
      (ui/fill! robot "#zone-label-a" "Beams")
      (ui/submit! robot "#zone-label-a")
      (ui/wait-for! #(= "Beams" (get-in (app-state) [:zones :labels :zone/a]))))
    (testing "a built-in map is read only"
      (ui/click! robot "#zone-map-item-odd-even")
      (ui/wait-for! #(= :map/odd-even (get-in (app-state) [:ui :zone-maps :selected])))
      (ui/assert-text! "#zone-map-built-in" "Built-in maps follow the rig. Duplicate one to change it.")
      (is (nil? (ui/query "#zone-map-delete")) "no Delete"))
    (testing "a new map, selected, with Left / Right's lanes"
      (ui/click! robot "#zone-maps-new")
      (ui/wait-for! #(= :map/custom-1 (get-in (app-state) [:ui :zone-maps :selected])))
      (ui/wait-for! #(ui/visible? "#zone-map-name"))
      (ui/assert-text! "#zone-map-lane-0" "Left")
      (ui/assert-text! "#zone-map-lane-1" "Right"))
    (testing "renamed"
      (ui/fill! robot "#zone-map-name" "Crowd sweep")
      (ui/submit! robot "#zone-map-name")
      (ui/wait-for! #(= "Crowd sweep" (get-in (app-state) [:zone-maps :map/custom-1 :name])))
      (ui/assert-text! "#zone-map-item-custom-1 .zone-map-item-name" "Crowd sweep"))
    (testing "lane 2 to Graphics, which has no projector: a warning"
      (is (not (some #(= "Combine" %) (item-texts "#zone-map-lane-1"))) "a lane is All or a group")
      (fire-item! "#zone-map-lane-1" "Graphics")
      (ui/wait-for! #(= [:auto/left :graphics] (get-in (app-state) [:zone-maps :map/custom-1 :lanes])))
      (ui/wait-for! #(some #{"Lane 2 (Graphics) has no projector: zone B goes nowhere."} (texts "#zone-map-warnings .label"))))
    (testing "where each zone goes"
      (ui/assert-text! "#zone-map-zones" "A · Beams → Left · B → Graphics"))
    (testing "a lane added, then taken out"
      (ui/click! robot "#zone-map-add-lane")
      (ui/wait-for! #(= [:auto/left :graphics :all] (get-in (app-state) [:zone-maps :map/custom-1 :lanes])))
      (ui/click! robot "#zone-map-remove-lane-2")
      (ui/wait-for! #(= [:auto/left :graphics] (get-in (app-state) [:zone-maps :map/custom-1 :lanes]))))
    (testing "an override"
      (ui/click! robot "#zone-map-add-override")
      (ui/wait-for! #(= {:zone/a :all} (get-in (app-state) [:zone-maps :map/custom-1 :overrides])))
      (ui/assert-text! "#zone-map-zones" "A · Beams → All (override) · B → Graphics")
      (ui/click! robot "#zone-map-remove-override-a")
      (ui/wait-for! #(= {} (get-in (app-state) [:zone-maps :map/custom-1 :overrides]))))
    (testing "Use now; a group's details count the cues that reach it through the map"
      (ui/click! robot "#zone-map-use-now")
      (ui/wait-for! #(= :map/custom-1 (active-map)))
      (events/dispatch! {:event/type :zone-groups/select :group-id :auto/left})
      (ui/wait-for! #(= "Used by 1 cue through Zone A" (ui/node-text "#zone-group-cue-usage"))))))

(deftest deleting-a-map-asks-first-test
  (let [robot (ui/robot)
        title "Delete \"Crowd sweep\"?"
        asking? #(boolean (some #{title} (ui/window-titles)))]
    (events/write-state! #(-> %
                              (assoc-in [:zone-maps :map/custom-1] {:name "Crowd sweep" :lanes [:auto/left :graphics] :overrides {}})
                              (assoc-in [:zone-maps :map/custom-2] {:name "Balcony" :lanes [:all] :overrides {}})
                              (assoc-in [:chains :effect-chains [1 1]]
                                        {:active? true :items [(-> (zone-patterns/cycle-instance)
                                                                   (assoc :id #uuid "00000000-0000-0000-0000-0000000000d2")
                                                                   (assoc-in [:params :maps] [:map/left-right :map/custom-2]))]})
                              (assoc-in [:zone-map :active] :map/custom-1)
                              (assoc-in [:ui :zone-maps :selected] :map/custom-1)))
    (open-zones-tab!)
    (ui/wait-for! #(ui/visible? "#zone-map-delete"))
    (ui/click! robot "#zone-map-delete")
    (ui/wait-for! asking?)
    (ui/assert-text! "#confirm-message" "If this is still the manual map, deleting it selects Left / Right and pauses a running Map cycle.")
    (is (some? (get-in (app-state) [:zone-maps :map/custom-1])) "nothing goes before you say yes")
    (testing "Cancel keeps it"
      (ui/click! robot "#confirm-cancel")
      (ui/wait-for! #(not (asking?)))
      (is (some? (get-in (app-state) [:zone-maps :map/custom-1]))))
    (testing "yes deletes it: the show goes back to Left / Right, and the running Map cycle pauses"
      (ui/click! robot "#zone-map-delete")
      (ui/wait-for! asking?)
      (ui/click! robot "#confirm-ok")
      (ui/wait-for! #(nil? (get-in (app-state) [:zone-maps :map/custom-1])))
      (is (= :map/left-right (active-map)))
      (is (true? (get-in (app-state) [:zone-map :cycle-paused?])))
      (ui/wait-for! #(nil? (ui/query "#zone-map-item-custom-1"))))
    (testing "a map the Map cycle lists, with no key or pad setting: the prompt never calls it unused"
      (let [title "Delete \"Balcony\"?"
            asking? #(boolean (some #{title} (ui/window-titles)))]
        (ui/click! robot "#zone-map-item-custom-2")
        (ui/wait-for! #(= :map/custom-2 (get-in (app-state) [:ui :zone-maps :selected])))
        (ui/click! robot "#zone-map-delete")
        (ui/wait-for! asking?)
        (ui/assert-text! "#confirm-message" "Delete this map from the show?")
        (ui/click! robot "#confirm-cancel")
        (ui/wait-for! #(not (asking?)))
        (is (some? (get-in (app-state) [:zone-maps :map/custom-2])))))))

(defn- manual-badges
  "The texts of the Manual badges the maps' list shows."
  []
  (ui/on-fx-thread #(into [] (keep (fn [n] (when (and (instance? Labeled n) (.isVisible ^Labeled n)) (.getText ^Labeled n))))
                          (ui/query-all ".zone-map-item-manual"))))

(deftest map-editor-can-take-over-on-the-manual-map-test
  ;; plans/2026-11-23-cljfx-reactivity/29 §7: while a Map cycle runs, the
  ;; manual map, edited, isn't called in use, and Use now on it takes over
  (let [robot (ui/robot)
        paused? #(true? (get-in (app-state) [:zone-map :cycle-paused?]))]
    (events/write-state! #(-> %
                              (assoc-in [:chains :effect-chains [1 1]]
                                        {:active? true :items [(assoc (zone-patterns/cycle-instance) :id #uuid "00000000-0000-0000-0000-0000000000d3")]})
                              (assoc-in [:zone-map :active] :map/unison)
                              (assoc-in [:ui :zone-maps :selected] :map/unison)))
    (open-zones-tab!)
    (testing "cycle mode, with no clock: no Manual badge, and Use now can take over"
      (ui/assert-text! "#zone-map-title" "Unison")
      (ui/assert-enabled! "#zone-map-use-now")
      (ui/wait-for! #(and (ui/visible? "#zone-map-mode")
                          (= "Cycle mode · Manual fallback: Unison" (ui/node-text "#zone-map-mode"))))
      (is (= [] (manual-badges)))
      (is (= "Choose this map now, pause any running Map cycle, and clear any queued switch." (tooltip-of "#zone-map-use-now"))))
    (testing "Use now: Unison, the cycle paused, and the editor says so"
      (ui/click! robot "#zone-map-use-now")
      (ui/wait-for! paused?)
      (is (= {:active :map/unison :pending nil} (select-keys (:zone-map (app-state)) [:active :pending])))
      (ui/assert-text! "#zone-map-mode" "Manual map: Unison")
      (ui/assert-text! "#zone-map-item-unison .zone-map-item-manual" "Manual")
      (is (ui/visible? "#zone-map-item-unison .zone-map-item-manual"))
      (is (= ["Manual"] (manual-badges)) "one Manual badge")
      (ui/assert-disabled! "#zone-map-use-now"))
    (testing "another row: its title, the badge stays on Unison, and Use now can choose it"
      (ui/click! robot "#zone-map-item-odd-even")
      (ui/assert-text! "#zone-map-title" "Odd / Even")
      (ui/assert-text! "#zone-map-item-unison .zone-map-item-manual" "Manual")
      (is (= ["Manual"] (manual-badges)))
      (ui/assert-text! "#zone-map-mode" "Manual map: Unison")
      (ui/assert-enabled! "#zone-map-use-now")
      (is (= :map/unison (active-map)) "browsing chooses nothing")
      (is (paused?)))))


;; The Zone Selector leaves the bank (06 §4.5, invariant 13)

(deftest bank-offers-no-zone-selector-test
  (testing "the effects grid's editor: its Zone tab offers Zone pattern and Map cycle (zones plan Z5), never the selector"
    (events/dispatch! {:event/type :effect-chain/open-editor :col 0 :row 0})
    (ui/wait-for! #(seq (ui/query-all "#zone-tab")))
    (ui/click! (ui/robot) "#zone-tab")
    (ui/wait-for! #(seq (filter #{"Zone pattern"} (texts ".bank-item-btn"))))
    (is (= ["Map cycle" "Zone pattern"] (sort (texts ".bank-item-btn"))))
    (events/dispatch! {:event/type :ui/close-dialog :dialog-id :effect-chain-editor})
    (ui/wait-for! #(empty? (ui/query-all "#shape-tab"))))
  (testing "the cue chain editor: none either"
    (open-editor! 0 0)
    (ui/wait-for! #(seq (ui/query-all "#shape-tab")))
    (is (empty? (ui/query-all "#zone-tab")))
    (events/dispatch! {:event/type :ui/close-dialog :dialog-id :cue-chain-editor})
    (ui/wait-for! #(empty? (ui/query-all "#shape-tab"))))
  (testing "the timeline's browser, a clip selected: none either"
    (open-timeline!)
    (events/write-state! assoc-in [:ui :timeline-prefs :add-panel :open?] true)
    (events/dispatch! {:event/type :timeline/select-items :show-id show-id :ids #{:c1} :mode :replace})
    (ui/wait-for! #(seq (ui/query-all "#timeline-browser-effects #shape-tab")))
    (is (empty? (ui/query-all "#zone-tab")))))


;; A refused edit (plans/2026-11-23-cljfx-reactivity/13): the controls show
;; the targets that apply

(defn- notice-id [] (get-in (app-state) [:ui :notice :id]))

(defn- attempt!
  "(act!), then wait for the show lock's new refusal of an event of type."
  [type act!]
  (let [before (notice-id)]
    (act!)
    (ui/wait-for! #(and (some? (notice-id)) (not= before (notice-id))
                        (= type (get-in (app-state) [:ui :show-lock-notice :event]))))))

(defn- lock! [locked?]
  (events/dispatch! {:event/type :vibe/set-show-lock :locked? locked?})
  (ui/wait-for! #(= locked? (true? (get-in (app-state) [:ui :show-lock?])))))

(defn- box-ticked [selector text]
  (when-let [^CheckBox box (get (combine-boxes selector) text)]
    (ui/on-fx-thread #(.isSelected box))))

(defn- combo-value [selector]
  (when-let [^ComboBox combo (ui/query selector)]
    (ui/on-fx-thread #(.getValue combo))))

(deftest locked-combine-and-override-show-applied-targets-test
  (let [robot (ui/robot)
        undo-depth #(count (get-in (app-state) [:history :undo-stack]))]
    (open-editor! 0 0)
    (tick! "#target-chip-item" "A → Left")
    (ui/wait-for! #(= :zone/a (item-target)))
    (ui/wait-for! #(true? (box-ticked "#target-chip-item" "A → Left")))
    (lock! true)
    (let [depth (undo-depth)]
      (testing "Combine, open: a second ref refused isn't left ticked"
        ;; Found before the menus open: ui/query looks in the topmost window
        (let [^MenuButton chip (ui/query "#target-chip-item")
              ^Menu combine (ui/on-fx-thread #(some (fn [i] (when (and (instance? Menu i) (= "Combine" (.getText ^Menu i))) i))
                                                     (.getItems chip)))
              box (fn [text] (some (fn [^CustomMenuItem i] (let [^CheckBox b (.getContent i)] (when (= text (.getText b)) b)))
                                   (.getItems combine)))
              ^CheckBox a (ui/on-fx-thread #(box "A → Left"))
              ^CheckBox b (ui/on-fx-thread #(box "B → Right"))]
          (ui/on-fx-thread #(do (.show chip) (.show combine)))
          (ui/wait-for! #(ui/on-fx-thread (fn [] (.isShowing combine))))
          (attempt! :cue-chain/set-item-target #(do (ui/on-fx-thread (fn [] (.fire b))) (ui/sync-fx!)))
          (is (= :zone/a (item-target)))
          (ui/wait-for! #(false? (ui/on-fx-thread (fn [] (.isSelected b)))))
          (is (true? (ui/on-fx-thread #(.isSelected a))))
          (is (ui/on-fx-thread #(.isShowing combine)) "still open")
          (ui/on-fx-thread #(.hide chip))
          (ui/sync-fx!))
        (events/dispatch! {:event/type :ui/close-dialog :dialog-id :cue-chain-editor})
        (ui/wait-for! #(nil? (ui/query "#target-chip-item"))))
      (testing "an override's zone"
        (events/write-state! #(-> %
                                  (assoc-in [:zone-maps :map/custom-1] {:name "Sweep" :lanes [:auto/left :auto/right]
                                                                        :overrides {:zone/a :all}})
                                  (assoc-in [:ui :zone-maps :selected] :map/custom-1)))
        (open-zones-tab!)
        (ui/wait-for! #(some? (ui/query "#zone-map-override-zone-a")))
        (attempt! :zone-maps/set-override #(ui/select! robot "#zone-map-override-zone-a" ":zone/b"))
        (is (= {:zone/a :all} (get-in (app-state) [:zone-maps :map/custom-1 :overrides])))
        (ui/wait-for! #(= :zone/a (combo-value "#zone-map-override-zone-a")))
        (is (nil? (ui/query "#zone-map-override-zone-b")) "no override for B"))
      (is (= depth (undo-depth)) "no undo step"))))
