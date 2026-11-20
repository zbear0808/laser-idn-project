(ns laser-show.ui-test.zones-test
  "Zones, zone maps and the one Target control (zones plan Z4, 06 §7): the
   Target chip in the editors, the grid's badges and column targets, the zone
   map pads, the Zones and maps section."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.state.history :as history]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.scene.control CheckBox Control CustomMenuItem Labeled Menu MenuButton MenuItem]
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
  (state/swap-state! #(-> %
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
    (state/swap-state! #(-> %
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
  (state/swap-state! assoc-in [:grid :column-targets] {0 :zone/a})
  (open-editor! 0 0)
  (fire-item! "#target-chip-item" "Pin: A → Left")
  (ui/wait-for! #(= :auto/left (item-target)))
  (ui/assert-text! "#target-chip-item" "Left")
  (testing "the map switches; the pinned item stays on Left"
    (state/swap-state! assoc-in [:zone-map :active] :map/odd-even)
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

(defn- track-target [id]
  (:target (some #(when (= id (:id %)) %) (tree-seq map? :items {:items (:tracks (cue 0 0))}))))

(defn- clip-target [id]
  (:target (some #(when (= id (:id %)) %) (:items (cue 0 0)))))

(defn- open-timeline! []
  (state/swap-state! assoc-in [:chains :cue-chains [0 0]] timeline-cue)
  (events/dispatch! {:event/type :timeline-cue-editor/open :col 0 :row 0})
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
    (events/dispatch! {:event/type :timeline/select-track :col 0 :row 0 :track-id :kick})
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
    (events/dispatch! {:event/type :timeline/select-items :col 0 :row 0 :ids #{:c1} :mode :replace})
    (ui/wait-for! #(= "CLIP" (ui/node-text "#timeline-inspector-title")))
    (ui/wait-for! #(some? (ui/query "#target-chip-clip")))
    (ui/assert-text! "#target-chip-clip" "(A → Left)")
    (ui/assert-text! "#target-from-clip" "from track Kick")
    (fire-item! "#target-chip-clip" "Graphics")
    (ui/wait-for! #(= :graphics (clip-target :c1)))
    (ui/assert-text! "#target-chip-clip" "Graphics")
    (is (= "Change Clip Target" (history/undo-label (:history (app-state)))))))
