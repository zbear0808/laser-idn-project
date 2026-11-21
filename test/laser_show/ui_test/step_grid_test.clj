(ns laser-show.ui-test.step-grid-test
  "The step grid window (zones plan Z6, 08 §7): opened from a cue's pattern
   (its Pattern chip, its pattern window) and from an effects cell's, to
   click, drag or type a pattern, with each row's zone and where it goes."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.animation.show-time :as show-time]
            [laser-show.events.core :as events]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.state.core :as state]
            [laser-show.state.history :as history]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]
            [laser-show.views.components.shortcuts :as shortcuts]
            [laser-show.views.components.step-grid :as step-grid])
  (:import [javafx.event Event]
           [javafx.scene Node]
           [javafx.scene.control ButtonBase Control Labeled Menu MenuButton MenuItem]
           [javafx.scene.input KeyCode KeyEvent MouseButton MouseEvent]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(defn- app-state [] (state/get-raw-state))

(def ^:private A :zone/a)
(def ^:private B :zone/b)
(def ^:private C :zone/c)

(def ^:private cue-target {:domain :cue-zone-patterns :entity-key [0 0] :effect-path [0]})
(def ^:private cell-target {:domain :effect-chains :entity-key [3 1] :effect-path [0]})

(defn- cue-params [] (get-in (app-state) [:chains :cue-chains [0 0] :zone-patterns 0 :params]))
(defn- cell-params [] (get-in (app-state) [:chains :effect-chains [3 1] :items 0 :params]))
(defn- steps [] (:steps (cue-params)))

(def ^:private cue-title "Step Grid — Cue A1 \"Beams\" · Zone pattern")

(defn- grid-on-a1!
  "The cue at A1, \"Beams\", with a new grid (8 steps at 2 a beat over A
   and B, Alternate), extra merged into its params."
  ([] (grid-on-a1! {}))
  ([extra]
   (state/swap-state! #(-> %
                           (assoc-in [:chains :cue-chains [0 0] :name] "Beams")
                           (assoc-in [:chains :cue-chains [0 0] :zone-patterns]
                                     [(-> (zone-patterns/preset-instance :steps :cue)
                                          (assoc :id #uuid "00000000-0000-0000-0000-00000000a6a1")
                                          (update :params merge extra))])))))

(defn- open-grid! [target]
  (events/dispatch! {:event/type :step-grid/open :target target})
  (ui/wait-for! #(some? (ui/query "#step-grid-body"))))

(defn- window-open? [title] (boolean (some #{title} (ui/window-titles))))

(defn- menu-items [selector]
  (let [^MenuButton button (ui/query selector)]
    (ui/on-fx-thread #(vec (.getItems button)))))

(defn- item-text [^MenuItem i]
  (ui/on-fx-thread #(str/trim (str/replace (str (.getText i)) #"^✓ " ""))))

(defn- fire-menu-item!
  "Fire the item whose text (without its tick) is text, among items."
  [items text]
  (let [item (some #(when (= text (item-text %)) %) items)]
    (is (some? item) (str "an item " (pr-str text) " in " (mapv item-text items)))
    (when item
      (ui/on-fx-thread #(.fire ^MenuItem item))
      (ui/sync-fx!))))

(defn- context-items
  "The context menu items of the control at selector."
  [selector]
  (ui/on-fx-thread #(vec (.getItems (.getContextMenu ^Control (ui/query selector))))))

(defn- fire! [selector]
  (ui/wait-for! #(some? (ui/query selector)))
  (ui/on-fx-thread #(.fire ^ButtonBase (ui/query selector)))
  (ui/sync-fx!))

(defn- tooltip-of [selector]
  (ui/on-fx-thread #(some-> ^Control (ui/query selector) .getTooltip .getText)))

(defn- body ^Node [] (ui/query "#step-grid-body"))

(defn- text-anywhere
  "The text of the first Labeled at selector in any window (query looks in
   the topmost only)."
  [selector]
  (let [nodes (ui/query-all selector)]
    (ui/on-fx-thread #(some (fn [n] (when (instance? Labeled n) (.getText ^Labeled n))) nodes))))

(defn- cell-classes [row step]
  (ui/on-fx-thread #(set (.getStyleClass ^Node (ui/query (format "#step-cell-%d-%d" row step))))))

(defn- lit-cell? [row step] (contains? (cell-classes row step) "step-cell-on"))

(defn- fire-grid!
  "One primary-button mouse event at the grid body's local (x, y), as a real
   mouse sends it (headless, a robot drag loses its button)."
  [event-type x y]
  (let [^Node node (body)]
    (ui/on-fx-thread
     #(let [scene (.localToScene node (double x) (double y))
            screen (.localToScreen node (double x) (double y))]
        (Event/fireEvent node
                         (MouseEvent. event-type (.getX scene) (.getY scene) (.getX screen) (.getY screen)
                                      MouseButton/PRIMARY 1 false false false false
                                      (not= event-type MouseEvent/MOUSE_RELEASED) false false
                                      false false false nil))))
    (ui/sync-fx!)))

(defn- cell-centre
  "The local centre of cell [row step] in the grid body."
  [row step]
  [(* (+ step 0.5) step-grid/cell-w) (* (+ row 0.5) step-grid/cell-h)])

(defn- key!
  "A key pressed while the grid has the focus, with :shift? or :shortcut? (⌘
   on macOS, Ctrl elsewhere): fired at the body, as the headless robot can't
   hold a modifier."
  [code & {:keys [shift? shortcut?]}]
  (let [meta? (and shortcut? (= KeyCode/COMMAND (ui/shortcut-key-code)))
        ctrl? (and shortcut? (not meta?))]
    (ui/on-fx-thread #(Event/fireEvent (body) (KeyEvent. KeyEvent/KEY_PRESSED "" "" code (boolean shift?) (boolean ctrl?) false (boolean meta?))))
    (ui/sync-fx!)))

(defn- cursor [] (get-in (app-state) [:ui :dialogs :step-grid-editor :cursor]))


;; Opening it (Z6-Q5 A: its own window)

(deftest opens-from-a-cue-pattern-test
  (state/swap-state! assoc-in [:chains :cue-chains [0 0] :name] "Beams")
  (testing "the Pattern chip's Step grid… gives a cue with no pattern a new grid, and opens it"
    (events/dispatch! {:event/type :cue-chain/open-editor :col 0 :row 0})
    (ui/wait-for! #(some? (ui/query "#pattern-chip-cue")))
    (fire-menu-item! (menu-items "#pattern-chip-cue") "Step grid…")
    (ui/wait-for! #(window-open? cue-title))
    (ui/wait-for! #(some? (body)))
    (is (= :steps (:style (cue-params))))
    (is (= (vec (take 8 (cycle [[A] [B]]))) (steps)) "8 steps at 2 a beat over A and B (Z6-Q1 A)")
    (is (= 2.0 (:rate (cue-params))))
    (is (= "Edit Step Grid" (history/undo-label (:history (app-state)))))
    (ui/assert-text! "#step-row-0" "A → Left")
    (ui/assert-text! "#step-row-1" "B → Right")
    (is (lit-cell? 0 0))
    (is (not (lit-cell? 1 0)))
    (is (lit-cell? 1 1))
    (is (some? (ui/query "#step-cell-1-7")) "8 steps")
    (is (nil? (ui/query "#step-cell-1-8")))
    (testing "the chip says it"
      (ui/wait-for! #(= "Steps · 8 · A B" (text-anywhere "#pattern-chip-cue"))))
    (fire! "#step-grid-close")
    (ui/wait-for! #(not (window-open? cue-title))))
  (testing "the pattern window's Step grid… makes the pattern a grid of its own first 8 steps"
    (state/swap-state! assoc-in [:chains :cue-chains [0 0] :zone-patterns]
                       [(-> (zone-patterns/preset-instance :alternate :cue)
                            (assoc :id #uuid "00000000-0000-0000-0000-00000000a6a2")
                            (assoc-in [:params :zones] [A B C :zone/d]))])
    (events/dispatch! {:event/type :ui/open-dialog :dialog-id :zone-pattern-editor :data {:col 0 :row 0}})
    (ui/wait-for! #(some? (ui/query "#zone-pattern-step-grid")))
    (ui/click! (ui/robot) "#zone-pattern-step-grid")
    (ui/wait-for! #(window-open? cue-title))
    (ui/wait-for! #(= :steps (:style (cue-params))))
    (is (= (vec (take 8 (cycle [[A C] [B :zone/d]]))) (steps)))
    (ui/assert-text! "#step-row-3" "D → Right")))

(deftest opens-from-an-effect-cell-test
  (state/swap-state! assoc-in [:chains :effect-chains [3 1]]
                     {:name "Run" :active? false
                      :items [(assoc (zone-patterns/preset-instance :chase :cell) :id #uuid "00000000-0000-0000-0000-00000000a6b1")]})
  (events/dispatch! {:event/type :effect-chain/open-editor :col 3 :row 1})
  (ui/wait-for! #(some? (ui/query "#zone-pattern-step-grid")))
  (ui/click! (ui/robot) "#zone-pattern-step-grid")
  (ui/wait-for! #(window-open? "Step Grid — Effects B4 \"Run\" · Zone pattern"))
  (ui/wait-for! #(= :steps (:style (cell-params))))
  (testing "Every laser can't be a row: it became zones, and the window says so (S-4)"
    (is (= [A B] (:zones (cell-params))) "no laser on: at least 2")
    (is (= [[A] [B] [A] [B] [A] [B] [A] [B]] (:steps (cell-params))) "a Chase's first 8 steps")
    (ui/assert-text! "#step-grid-every-laser-note"
                     "Every laser can't be a row, because the number of lasers changes. It became zones A and B: pick a zone map that sends them where you want."))
  (testing "a click on the cell's grid edits the cell's pattern"
    (let [[x y] (cell-centre 1 0)]
      (fire-grid! MouseEvent/MOUSE_PRESSED x y)
      (fire-grid! MouseEvent/MOUSE_RELEASED x y))
    (ui/wait-for! #(= [A B] (first (:steps (cell-params)))))))


;; The mouse

(deftest click-toggles-a-cell-test
  (grid-on-a1!)
  (open-grid! cue-target)
  (let [robot (ui/robot)]
    (ui/click! robot "#step-cell-1-0")
    (testing "an unlit cell lights"
      (ui/wait-for! #(= [A B] (first (steps)))))
    (ui/wait-for! #(lit-cell? 1 0))
    (is (= "Edit Step Grid" (history/undo-label (:history (app-state)))))
    (is (= [1 0] (cursor)) "the cursor follows the click")
    (is (contains? (cell-classes 1 0) "step-cell-cursor"))
    (ui/click! robot "#step-cell-1-0")
    (testing "a lit one clears"
      (ui/wait-for! #(= [A] (first (steps)))))
    (ui/wait-for! #(not (lit-cell? 1 0)))))

(deftest drag-paints-a-row-test
  (grid-on-a1! {:zones [A B C] :steps (vec (repeat 9 []))})
  (open-grid! cue-target)
  (testing "a press on an unlit cell lights every cell the drag crosses (Z6-Q3 A)"
    (let [depth (count (get-in (app-state) [:history :undo-stack]))]
      ;; 3 rows of 9 steps: the body's centre is row B, step 5 (index 4)
      (ui/fire-drag! (body) (* 3 step-grid/cell-w) 0)
      (ui/wait-for! #(= [[] [] [] [] [B] [B] [B] [B] []] (steps)))
      (is (= (inc depth) (count (get-in (app-state) [:history :undo-stack]))) "one drag, one undo step")
      (is (nil? (get-in (app-state) [:history :transaction])))
      (ui/wait-for! #(every? (fn [k] (lit-cell? 1 k)) [4 5 6 7]))))
  (testing "a press on a lit cell clears every cell crossed"
    (let [[x0 y0] (cell-centre 1 7)
          [x1 _] (cell-centre 1 5)]
      (fire-grid! MouseEvent/MOUSE_PRESSED x0 y0)
      (fire-grid! MouseEvent/MOUSE_DRAGGED x1 y0)
      (fire-grid! MouseEvent/MOUSE_RELEASED x1 y0)
      (ui/wait-for! #(= [[] [] [] [] [B] [] [] [] []] (steps))))))

(deftest undo-reverts-a-drag-test
  (grid-on-a1! {:steps (vec (repeat 8 []))})
  (open-grid! cue-target)
  (let [[x0 y0] (cell-centre 0 1)
        [x1 _] (cell-centre 0 6)]
    (fire-grid! MouseEvent/MOUSE_PRESSED x0 y0)
    (fire-grid! MouseEvent/MOUSE_DRAGGED x1 y0)
    (fire-grid! MouseEvent/MOUSE_RELEASED x1 y0)
    (ui/wait-for! #(= [[] [A] [A] [A] [A] [A] [A] []] (steps)))
    (key! KeyCode/Z :shortcut? true)
    (testing "⌘Z (Ctrl+Z) undoes the whole drag"
      (ui/wait-for! #(= (vec (repeat 8 [])) (steps))))
    (ui/wait-for! #(not (lit-cell? 0 3)))
    (key! KeyCode/Z :shortcut? true :shift? true)
    (testing "and ⇧⌘Z redoes it"
      (ui/wait-for! #(= [[] [A] [A] [A] [A] [A] [A] []] (steps))))))


;; The keys

(deftest keyboard-moves-and-toggles-test
  (grid-on-a1! {:steps (vec (repeat 8 []))})
  (open-grid! cue-target)
  (ui/focus! (ui/robot) "#step-grid-body")
  (is (= [0 0] (cursor)))
  (key! KeyCode/RIGHT)
  (key! KeyCode/DOWN)
  (ui/wait-for! #(= [1 1] (cursor)))
  (ui/wait-for! #(contains? (cell-classes 1 1) "step-cell-cursor"))
  (testing "Space and Enter toggle the cell under the cursor"
    (key! KeyCode/SPACE)
    (ui/wait-for! #(= [B] (nth (steps) 1)))
    (key! KeyCode/ENTER)
    (ui/wait-for! #(= [] (nth (steps) 1)))
    (key! KeyCode/SPACE))
  (testing "Delete clears the cursor's step, in every row"
    (key! KeyCode/UP)
    (key! KeyCode/SPACE)
    (ui/wait-for! #(= [A B] (nth (steps) 1)))
    (key! KeyCode/DELETE)
    (ui/wait-for! #(= [] (nth (steps) 1))))
  (testing "Shift+→ and Shift+← rotate every row"
    (key! KeyCode/SPACE)
    (ui/wait-for! #(= [A] (nth (steps) 1)))
    (key! KeyCode/RIGHT :shift? true)
    (ui/wait-for! #(= [A] (nth (steps) 2)))
    (key! KeyCode/LEFT :shift? true)
    (ui/wait-for! #(= [A] (nth (steps) 1))))
  (testing "Home and End: the first step and the last"
    (key! KeyCode/END)
    (ui/wait-for! #(= [0 7] (cursor)))
    (key! KeyCode/HOME)
    (ui/wait-for! #(= [0 0] (cursor))))
  (testing "the arrows stop at the edges"
    (key! KeyCode/LEFT)
    (key! KeyCode/UP)
    (ui/wait-for! #(= [0 0] (cursor)))))


;; The toolbar

(deftest length-buttons-test
  (grid-on-a1!)
  (open-grid! cue-target)
  (fire! "#step-grid-length-16")
  (ui/wait-for! #(= 16 (count (steps))))
  (ui/wait-for! #(some? (ui/query "#step-cell-0-15")))
  (is (= (vec (concat (take 8 (cycle [[A] [B]])) (repeat 8 []))) (steps)) "rests at the end")
  (fire! "#step-grid-length-32")
  (ui/wait-for! #(= 32 (count (steps))))
  (fire! "#step-grid-length-8")
  (ui/wait-for! #(= 8 (count (steps))))
  (ui/wait-for! #(nil? (ui/query "#step-cell-0-8")))
  (testing "any length from 1 to 64 in the field (Z6-Q4 A)"
    (ui/fill! (ui/robot) "#step-grid-length" "12")
    (ui/submit! (ui/robot) "#step-grid-length")
    (ui/wait-for! #(= 12 (count (steps))))
    (ui/fill! (ui/robot) "#step-grid-length" "99")
    (ui/submit! (ui/robot) "#step-grid-length")
    (ui/wait-for! #(= 64 (count (steps))))
    (ui/wait-for! #(= "64" (ui/node-text "#step-grid-length")))))

(deftest add-and-remove-a-row-test
  (grid-on-a1!)
  (open-grid! cue-target)
  (testing "+ Row adds the next zone letter"
    (fire! "#step-grid-add-row")
    (ui/wait-for! #(= [A B C] (:zones (cue-params))))
    (ui/assert-text! "#step-row-2" "C → Left"))
  (testing "its menu: any zone, group or laser"
    (let [items (menu-items "#step-grid-add-row")]
      (is (not-any? #{"Every laser, left to right"} (map item-text items)) "Every laser can't be a row")
      (fire-menu-item! items "Right"))
    (ui/wait-for! #(= [A B C :auto/right] (:zones (cue-params))))
    (ui/assert-text! "#step-row-3" "Right"))
  (testing "a row's menu: Remove row"
    (fire-menu-item! (context-items "#step-row-0") "Remove row")
    (ui/wait-for! #(= [B C :auto/right] (:zones (cue-params))))
    (is (every? #(not (some #{A} %)) (steps)) "no step names A")
    (ui/assert-text! "#step-row-0" "B → Right"))
  (testing "Move down, and Every 2nd"
    (fire-menu-item! (context-items "#step-row-0") "Move down")
    (ui/wait-for! #(= [C B :auto/right] (:zones (cue-params))))
    (fire-menu-item! (context-items "#step-row-0") "Every 2nd")
    (ui/wait-for! #(= [[C] [B] [C] [B] [C] [B] [C] [B]] (steps)))))

(deftest start-from-chase-test
  (grid-on-a1! {:zones [A B C]})
  (open-grid! cue-target)
  (is (= "Replaces the grid with the first 8 steps of Chase over these rows" (tooltip-of "#step-grid-start-chase")))
  (fire! "#step-grid-start-chase")
  (ui/wait-for! #(= [[A] [B] [C] [A] [B] [C] [A] [B]] (steps)))
  (is (= "Edit Step Grid" (history/undo-label (:history (app-state)))))
  (fire! "#step-grid-start-center-out")
  (ui/wait-for! #(= (vec (take 8 (cycle [[B] [A C]]))) (steps)))
  (fire! "#step-grid-clear")
  (ui/wait-for! #(= (vec (repeat 8 [])) (steps))))

(deftest copy-and-paste-between-grids-test
  (grid-on-a1!)
  (state/swap-state! assoc-in [:chains :effect-chains [3 1]]
                     {:active? false
                      :items [(-> (zone-patterns/preset-instance :steps :cell)
                                  (assoc :id #uuid "00000000-0000-0000-0000-00000000a6b2")
                                  (update :params merge {:zones [C] :steps [[C] [] [C] []]}))]})
  (open-grid! cue-target)
  (testing "the tooltips name the keys as the platform writes them"
    (is (= (str "Copy this grid (" (shortcuts/shortcut-text :c) ")") (tooltip-of "#step-grid-copy")))
    (is (= (str "Paste the grid you copied (" (shortcuts/shortcut-text :v) ")") (tooltip-of "#step-grid-paste"))))
  (is (not (ui/enabled? "#step-grid-paste")) "nothing copied yet")
  (fire! "#step-grid-copy")
  (ui/wait-for! #(some? (get-in (app-state) [:ui :step-grid :clipboard])))
  (fire! "#step-grid-close")
  (open-grid! cell-target)
  (ui/wait-for! #(ui/enabled? "#step-grid-paste"))
  (fire! "#step-grid-paste")
  (testing "the rows it lacked, added"
    (ui/wait-for! #(= [C A B] (:zones (cell-params)))))
  (is (= (vec (take 8 (cycle [[A] [B]]))) (:steps (cell-params))) "A1's grid, by ref")
  (testing "⌘V (Ctrl+V) pastes too"
    (fire! "#step-grid-clear")
    (ui/wait-for! #(= (vec (repeat 8 [])) (:steps (cell-params))))
    (key! KeyCode/V :shortcut? true)
    (ui/wait-for! #(= (vec (take 8 (cycle [[A] [B]]))) (:steps (cell-params))))))


;; The rows (08 §7)

(deftest rows-say-where-they-go-test
  (grid-on-a1! {:zones [A B C]})
  (open-grid! cue-target)
  (ui/assert-text! "#step-row-0" "A → Left")
  (ui/assert-text! "#step-row-1" "B → Right")
  (ui/assert-text! "#step-row-2" "C → Left")
  (ui/assert-text! "#step-grid-wrap-note" "A and C both go to Left under Left / Right.")
  (testing "the map switches, and so do the rows"
    (state/swap-state! assoc-in [:zone-map :active] :map/odd-even)
    (ui/assert-text! "#step-row-0" "A → Odd")
    (ui/assert-text! "#step-row-1" "B → Even"))
  (testing "while the pattern's keyframes are on, the grid is greyed out and says why"
    (state/swap-state! assoc-in [:chains :cue-chains [0 0] :zone-patterns 0 :keyframe-modulator] {:enabled? true :keyframes []})
    (ui/assert-text! "#step-grid-guard" "This pattern's keyframes are on. Switch them off to edit its grid.")
    (is (ui/on-fx-thread #(.isDisabled (body))))))


;; The playhead and the pattern window's small grid (08 §7)

(def ^:private grid-id #uuid "00000000-0000-0000-0000-00000000a6a1")

(defn- playhead-columns
  "The columns the playhead canvas lights: those whose middle isn't the
   snapshot's white."
  []
  (let [w (long (ui/on-fx-thread #(.getWidth ^javafx.scene.canvas.Canvas (ui/query "#step-grid-playhead"))))
        px (ui/node-pixels "#step-grid-playhead")
        y (long (/ step-grid/cell-h 2))]
    (vec (keep (fn [k]
                 (let [x (long (+ (* k step-grid/cell-w) (/ step-grid/cell-w 2)))]
                   (when (not= (unchecked-int 0xFFFFFFFF) (nth px (+ (* y w) x))) k)))
               (range (quot w (long step-grid/cell-w)))))))

(deftest playhead-follows-the-clock-test
  (state/swap-state! assoc-in [:timing :bpm] 120.0)
  (grid-on-a1!)
  (open-grid! cue-target)
  (ui/assert-text! "#step-grid-now" "Not playing")
  (is (= [] (playhead-columns)) "no playhead while the cue doesn't play")
  (events/dispatch! {:event/type :transport/play})
  (events/dispatch! {:event/type :grid/trigger-cell :col 0 :row 0})
  (ui/wait-for! #(some? (get-in (app-state) [:playback :active-cues [0 0]])))
  (ui/wait-for! #(true? (get-in (app-state) [:playback :playing?])))
  (fixtures/with-clock 1000000000
    (fn [at-ms]
      (let [step #(get-in (show-time/latest) [:zone-steps grid-id])]
        (ui/wait-for! #(some? (step)))
        (testing "the playhead lights the column of the step the pattern is on (:zone-steps, step mod L)"
          (let [s0 (step)]
            (ui/wait-for! #(= (str "Step " (inc (mod s0 8)) " of 8") (ui/node-text "#step-grid-now")))
            (ui/wait-for! #(= [(mod s0 8)] (playhead-columns)))))
        (testing "and follows the clock"
          ;; 0.75 s on (the clock skips a jump of more than 1 s): 1.5 beats
          (reset! at-ms 750)
          (ui/wait-for! #(and (step) (pos? (mod (step) 8))))
          (let [s1 (step)]
            (ui/wait-for! #(= (str "Step " (inc (mod s1 8)) " of 8") (ui/node-text "#step-grid-now")))
            (ui/wait-for! #(= [(mod s1 8)] (playhead-columns)))))
        (testing "it reads the snapshot only: the clock writes no app state (08 invariant 7)"
          (is (nil? (get-in (app-state) [:ui :dialogs :step-grid-editor :playhead]))))
        (testing "stopped: no playhead, and it says so"
          (state/swap-state! assoc-in [:playback :active-cues] {})
          (ui/wait-for! #(nil? (step)))
          (ui/assert-text! "#step-grid-now" "Not playing")
          (ui/wait-for! #(= [] (playhead-columns))))))))

(deftest pattern-window-shows-a-small-grid-test
  (grid-on-a1! {:zones [A B C] :steps [[A] [] [B C] []]})
  (events/dispatch! {:event/type :ui/open-dialog :dialog-id :zone-pattern-editor :data {:col 0 :row 0}})
  (ui/wait-for! #(some? (ui/query "#zone-pattern-mini-grid")))
  (testing "a small read-only grid in place of Zones, with Edit grid…"
    (is (nil? (ui/query "#zone-pattern-row-zones")) "the rows are the grid's")
    (is (= ["0-0" "1-2" "2-2"]
           (ui/on-fx-thread #(vec (sort (keep (fn [^Node n] (when (.contains (.getStyleClass n) "step-cell-on")
                                                              (subs (.getId n) (count "zone-pattern-mini-cell-"))))
                                              (ui/query-all "#zone-pattern-mini-grid .step-cell")))))))
    (ui/assert-text! "#zone-pattern-step-grid" "Edit grid…"))
  (ui/click! (ui/robot) "#zone-pattern-step-grid")
  (ui/wait-for! #(window-open? cue-title))
  (ui/wait-for! #(lit-cell? 2 2)))
