(ns laser-show.ui-test.clipboard-test
  "One clipboard (keyboard plan Phase 4, Step 4a): copy writes [:ui :clipboard]
   in its handler's state and puts the clip's EDN on the OS clipboard; paste
   reads the slot, and the OS clipboard only while the slot is empty. Each
   test names the probe it comes from (plans/2026-11-13-keyboard-focus-window-
   lifecycle/probes/keyboard_probes_test.clj) and what that probe printed
   before Step 4a.

   The OS clipboard here is the headless platform's. Each test starts from a
   text it sets, and the fixture puts back whatever text was there before,
   so a visible run leaves the Mac's pasteboard as it found it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.app :as app]
            [laser-show.events.core :as events]
            [laser-show.state.clipboard :as clipboard]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.application Platform]
           [javafx.scene.input Clipboard ClipboardContent KeyCode]))

(defn- os-text [] (ui/on-fx-thread #(.getString (Clipboard/getSystemClipboard))))

(defn- set-os-text! [^String s]
  (ui/on-fx-thread #(.setContent (Clipboard/getSystemClipboard) (doto (ClipboardContent.) (.putString s)))))

(defn- keep-os-clipboard
  "Each fixture: the OS clipboard's text put back after the test."
  [tests]
  (let [before (os-text)]
    (try
      (tests)
      (finally
        (if before
          (set-os-text! before)
          (ui/on-fx-thread #(.clear (Clipboard/getSystemClipboard))))))))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app keep-os-clipboard)

(defn- raw [] (state/get-raw-state))
(defn- slot [] (get-in (raw) [:ui :clipboard]))

(defn- flush!
  "Wait until every event dispatched so far was handled (events run in order)."
  []
  (ui/sync-fx!)
  (let [marker (+ 60.25 (rand-int 100))]
    (events/dispatch! {:event/type :timing/set-bpm :bpm marker})
    (ui/wait-for! #(= marker (get-in (raw) [:timing :bpm])))))

(defn- shortcut! [robot ^KeyCode code]
  (.push ^org.testfx.api.FxRobot robot (into-array KeyCode [KeyCode/SHORTCUT code]))
  (ui/sync-fx!))

(def ^:private cue-items-path [:chains :cue-chains [0 0] :items])
(defn- cue-items [] (get-in (raw) cue-items-path))
(defn- selected [] (get-in (raw) [:list-ui [:cue-chain 0 0] :selected-ids]))

(defn- open-cue-editor! []
  (events/dispatch! {:event/type :cue-chain/open-editor :col 0 :row 0})
  (ui/wait-for! #(some (fn [t] (and t (str/starts-with? t "Cue Chain Editor"))) (ui/window-titles)))
  (ui/assert-visible! ".chain-item"))

(defn- add-cue-items! [ids]
  (doseq [id ids]
    (events/dispatch! {:event/type :cue-chain/add-preset :col 0 :row 0 :item-id id}))
  (ui/wait-for! #(= (+ 1 (count ids)) (count (ui/query-all ".chain-item")))))

(defn- select-cue-item!
  "Click the nth row of the cue list, so the list has focus and a selection."
  [robot n]
  (ui/click-node! robot (nth (ui/query-all ".chain-item") n))
  (ui/wait-for! #(= #{(:id (nth (cue-items) n))} (selected))))

(defn- copy-event []
  {:event/type :list/copy-selected :component-id [:cue-chain 0 0]
   :items-path cue-items-path :clipboard-type :cue-chain-items})

(def ^:private paste-event
  {:event/type :list/paste-items :component-id [:cue-chain 0 0]
   :items-path cue-items-path
   :on-change-event :chain/set-items
   :on-change-params {:domain :cue-chains :entity-key [0 0]}
   :clipboard-type :cue-chain-items})


;; The slot and the OS clipboard (p5, p9)

(deftest list-copy-lands-in-the-clipboard-test
  ;; p5, p9: before Step 4a the slot stayed nil after a list copy
  (let [robot (ui/robot)]
    (set-os-text! "")
    (open-cue-editor!)
    (add-cue-items! [:square])
    (select-cue-item! robot 1)
    (shortcut! robot KeyCode/C)
    (ui/wait-for! #(= :cue-chain-items (:type (slot))))
    (is (= [:square] (mapv :preset-id (:items (slot)))))
    (is (number? (:copied-at (slot))))
    (is (= 2 (count (cue-items))) "a copy is no edit")))

(deftest copy-puts-edn-on-the-system-clipboard-test
  (let [robot (ui/robot)]
    (set-os-text! "")
    (open-cue-editor!)
    (select-cue-item! robot 0)
    (ui/click! robot "#list-editor-cue-chain-0-0 #chain-copy-selection")
    (ui/wait-for! #(some? (clipboard/<-system-text (os-text))))
    (is (= (slot) (clipboard/<-system-text (os-text))) "the OS text reads back as the slot's clip")
    (is (not (str/includes? (os-text) "\n")) "one line of EDN")))


;; The event agent never waits for the FX thread (p10)

(defn- ms-until-marker!
  "Dispatch evs, then a BPM marker: ms until the marker is in state, i.e.
   until the agent has run everything queued before it."
  [evs]
  (let [marker (+ 61.5 (rand-int 100))
        t0 (System/nanoTime)]
    (doseq [e evs] (events/dispatch! e))
    (events/dispatch! {:event/type :timing/set-bpm :bpm marker})
    (loop []
      (if (= marker (get-in (raw) [:timing :bpm]))
        (/ (- (System/nanoTime) t0) 1e6)
        (do (Thread/sleep 1) (recur))))))

(defn- behind-a-busy-fx-thread
  "ms until a BPM change queued behind evs lands, with the FX thread busy
   for 500 ms meanwhile."
  [evs]
  (Platform/runLater #(Thread/sleep 500))
  (let [ms (ms-until-marker! evs)]
    (ui/sync-fx!)
    ms))

(deftest copy-and-paste-do-not-hold-up-other-events-test
  ;; p10: before Step 4a, 502–535 ms behind a list copy and 502–579 ms
  ;; behind a list paste
  (set-os-text! "")
  (events/dispatch! {:event/type :list/select-item :component-id [:cue-chain 0 0]
                     :item-id (:id (first (cue-items))) :mode :single :items-path cue-items-path})
  (flush!)
  (testing "a paste with an empty slot: the read waits for the FX thread, the agent doesn't"
    (is (nil? (slot)))
    (is (< (behind-a-busy-fx-thread [paste-event]) 250))
    (ui/wait-for! #(= "Nothing to paste here" (get-in (raw) [:ui :notice :text]))))
  (testing "a copy"
    (is (< (behind-a-busy-fx-thread [(copy-event)]) 250)))
  (ui/wait-for! #(= :cue-chain-items (:type (slot))))
  (testing "a paste, the slot holding a clip"
    (let [n (count (cue-items))]
      (is (< (behind-a-busy-fx-thread [paste-event]) 250))
      (ui/wait-for! #(= (inc n) (count (cue-items)))))))


;; Cut (p16)

(deftest cut-with-a-failing-os-write-keeps-the-items-test
  ;; p16: before Step 4a, ⌘X deleted (3 → 2) and ⌘V then pasted nothing (2 → 2)
  (let [robot (ui/robot)]
    (set-os-text! "")
    (open-cue-editor!)
    (add-cue-items! [:square :triangle])
    (select-cue-item! robot 1)
    (with-redefs [events/put-system-clipboard-text! (fn [_] (throw (IllegalStateException. "no pasteboard")))]
      (shortcut! robot KeyCode/X)
      (ui/wait-for! #(= [:circle :triangle] (mapv :preset-id (cue-items))))
      (is (= [:square] (mapv :preset-id (:items (slot)))) "the cut items are in the slot")
      (select-cue-item! robot 0)
      (shortcut! robot KeyCode/V)
      (ui/wait-for! #(= [:circle :square :triangle] (mapv :preset-id (cue-items)))))
    (is (= "" (os-text)) "the OS write failed, and nothing else was lost")))


;; Copy, quit, start, paste (D8 A)

(deftest fresh-app-pastes-from-the-system-clipboard-test
  (let [robot (ui/robot)]
    (set-os-text! "")
    (open-cue-editor!)
    (add-cue-items! [:square])
    (select-cue-item! robot 1)
    (shortcut! robot KeyCode/C)
    (ui/wait-for! #(= :cue-chain-items (:type (clipboard/<-system-text (os-text)))))
    (app/stop!)
    (ui/sync-fx!)
    (app/start-test!)
    (ui/sync-fx!)
    (testing "a new app: nothing copied in it yet, the copy on the OS clipboard"
      (is (nil? (slot)))
      (is (= 1 (count (cue-items)))))
    (open-cue-editor!)
    (select-cue-item! robot 0)
    (shortcut! robot KeyCode/V)
    (ui/wait-for! #(= [:circle :square] (mapv :preset-id (cue-items))))
    (ui/wait-for! #(= :cue-chain-items (:type (slot))))
    (is (= [:square] (mapv :preset-id (:items (slot)))) "the slot holds the clip it read")))


;; Paste buttons and menu items follow the clipboard (p5, p11, p12; D13 B)

(defn- node ^javafx.scene.Node [selector] (first (ui/query-all selector)))

(defn- disabled? [selector]
  (ui/wait-for! #(some? (node selector)))
  (ui/on-fx-thread #(.isDisabled (node selector))))

(def ^:private cue-paste "#list-editor-cue-chain-0-0 #chain-paste-items")
(def ^:private item-fx-paste "#list-editor-item-effects-0-0 #chain-paste-items")
(def ^:private chain-paste "#chain-paste-items")

(defn- two-projectors!
  "Fake 1 and Fake 2, added as the Add Projector dialog adds them; Fake 1's
   chain an RGB curves and an Axis Transform, the Axis Transform selected,
   Fake 1 selected. Returns their ids."
  []
  (events/dispatch! {:event/type :projectors/add-manual :name "Fake 1" :host "127.0.0.1"})
  (flush!)
  (Thread/sleep 2)
  (events/dispatch! {:event/type :projectors/add-manual :name "Fake 2" :host "127.0.0.2"})
  (flush!)
  (let [id-of (fn [n] (some (fn [[id p]] (when (= n (:name p)) id)) (:projectors (raw))))
        a (id-of "Fake 1")
        b (id-of "Fake 2")]
    ;; A new projector comes with a calibration chain of its own: set both
    ;; as the chain list sets them
    (events/dispatch! {:event/type :chain/set-items :domain :projector-effects :entity-key a
                       :items [{:id (random-uuid) :effect-id :rgb-curves :enabled? true
                                :params {:r-curve-points [[0.0 0.0] [1.0 1.0]]
                                         :g-curve-points [[0.0 0.0] [1.0 1.0]]
                                         :b-curve-points [[0.0 0.0] [1.0 1.0]]}}
                               {:id (random-uuid) :effect-id :axis-transform :enabled? true
                                :params {:mirror-x? false :mirror-y? false :swap-axes? false}}]})
    (events/dispatch! {:event/type :chain/set-items :domain :projector-effects :entity-key b :items []})
    (events/dispatch! {:event/type :projectors/select-projector :projector-id a})
    (flush!)
    (events/dispatch! {:event/type :list/select-item :component-id [:projector-effects a]
                       :item-id (:id (second (get-in (raw) [:chains :projector-effects a :items])))
                       :mode :single :items-path [:chains :projector-effects a :items]})
    (flush!)
    [a b]))

(defn- chain-of [id] (mapv :effect-id (get-in (raw) [:chains :projector-effects id :items])))

(def ^:private os-readers
  "The functions that read the OS clipboard: the :clipboard/read effect's,
   and, until Step 4a's last commit removes it, the old helper's."
  [#'events/system-clipboard-text #'clipboard/read-from-system-clipboard])

(defn- count-os-reads
  "(f) with every OS clipboard read counted; the count."
  [f]
  (let [reads (atom 0)]
    (with-redefs-fn (into {} (map (fn [v] (let [orig @v] [v (fn [& args] (swap! reads inc) (apply orig args))])))
                          os-readers)
      f)
    @reads))

(deftest nothing-reads-the-system-clipboard-while-rendering-test
  ;; p11: before Step 4a the projector panel read the OS clipboard on every
  ;; render, and the effects grid's chain editor did too
  (let [robot (ui/robot)
        [a _] (two-projectors!)
        reads (count-os-reads
               (fn []
                 (ui/click! robot "#projectors-tab")
                 (ui/wait-for! #(some? (node chain-paste)))
                 (events/dispatch! {:event/type :list/select-item :component-id [:projector-effects a]
                                    :item-id (:id (first (get-in (raw) [:chains :projector-effects a :items])))
                                    :mode :single :items-path [:chains :projector-effects a :items]})
                 (flush!)
                 (ui/click! robot "#chain-copy-selection")
                 (ui/wait-for! #(= :item-effects (:type (slot))))
                 (events/dispatch! {:event/type :effect-chain/open-editor :col 0 :row 3})
                 (ui/wait-for! #(some (fn [t] (and t (str/starts-with? t "Effects Chain"))) (ui/window-titles)))
                 (ui/wait-for! #(some? (node "#list-editor-effect-chain-0-3")))
                 (flush!)))]
    (is (zero? reads) "no OS read while rendering: the slot held a clip, so no paste read either")))

(deftest copy-lights-the-same-chains-paste-test
  ;; p11: before Step 4a, after Copy the same chain's Paste stayed off until
  ;; something else re-rendered the panel
  (let [robot (ui/robot)
        [a b] (two-projectors!)]
    (events/dispatch! {:event/type :grid/copy-cell :col 0 :row 0})
    (ui/click! robot "#projectors-tab")
    (ui/wait-for! #(= :cue-chain (:type (slot))))
    (ui/wait-for! #(disabled? chain-paste))
    (is (disabled? chain-paste) "a copied cue: nothing a calibration chain takes")
    (ui/click! robot "#chain-copy-selection")
    (ui/wait-for! #(not (disabled? chain-paste)))
    (testing "and another projector's chain takes it"
      (events/dispatch! {:event/type :projectors/select-projector :projector-id b})
      (ui/wait-for! #(= "CONFIGURE: Fake 2" (ui/node-text "#projector-config-title")))
      (ui/wait-for! #(not (disabled? chain-paste)))
      (ui/click! robot chain-paste)
      (ui/wait-for! #(= [:axis-transform] (chain-of b))))
    (is (= [:rgb-curves :axis-transform] (chain-of a)))))

(deftest cue-editor-paste-lights-after-a-copy-test
  ;; p5: before Step 4a both of the Cue Chain Editor's Paste buttons stayed
  ;; off after a copy ([true true])
  (let [robot (ui/robot)]
    (events/dispatch! {:event/type :grid/copy-cell :col 1 :row 0})
    (open-cue-editor!)
    (add-cue-items! [:square])
    (select-cue-item! robot 1)
    (ui/wait-for! #(some? (node item-fx-paste)))
    (ui/wait-for! #(and (disabled? cue-paste) (disabled? item-fx-paste)))
    (is (= [true true] [(disabled? cue-paste) (disabled? item-fx-paste)]) "a copied cell: neither list takes it")
    (shortcut! robot KeyCode/C)
    (ui/wait-for! #(= :cue-chain-items (:type (slot))))
    (ui/wait-for! #(not (disabled? cue-paste)))
    (is (not (disabled? item-fx-paste)) "an item's effects take cue items too until Step 4b (§7, first table)")
    (ui/click! robot cue-paste)
    (ui/wait-for! #(= [:circle :square :square] (mapv :preset-id (cue-items))))))

(defn- items-change-on-paste?
  "Does ⌘V in the list whose rows are row-selector change (items)? The
   first row is clicked first, so the list has focus."
  [robot row-selector items]
  (let [before (items)]
    (ui/click-node! robot (node row-selector))
    (flush!)
    (shortcut! robot KeyCode/V)
    (flush!)
    (not= before (items))))

(deftest paste-button-and-shortcut-agree-test
  ;; p12: before Step 4a, with effects on the clipboard the effects grid's
  ;; chain editor lit Paste, and Paste then pasted nothing (1 → 1)
  (let [robot (ui/robot)
        [a _] (two-projectors!)
        fx-items #(get-in (raw) [:chains :effect-chains [0 3] :items])
        fx-row "#list-editor-effect-chain-0-3 .chain-item"
        fx-paste "#list-editor-effect-chain-0-3 #chain-paste-items"
        open-fx-editor! (fn []
                          (events/dispatch! {:event/type :effect-chain/open-editor :col 0 :row 3})
                          (ui/wait-for! #(some? (node fx-paste))))
        close-fx-editor! (fn []
                           (events/dispatch! {:event/type :ui/close-dialog :dialog-id :effect-chain-editor})
                           (ui/wait-for! #(nil? (node fx-paste))))]
    (ui/click! robot "#projectors-tab")
    (ui/wait-for! #(some? (node chain-paste)))
    (testing "effects on the clipboard (a projector chain's Copy)"
      (ui/click! robot "#chain-copy-selection")
      (ui/wait-for! #(= :item-effects (:type (slot))))
      (open-fx-editor!)
      (let [enabled? (not (disabled? fx-paste))]
        (is (= enabled? (items-change-on-paste? robot fx-row fx-items)) "the effects grid's chain"))
      (close-fx-editor!)
      (let [enabled? (not (disabled? chain-paste))]
        (is enabled?)
        (is (= enabled? (items-change-on-paste? robot (str "#list-editor-projector-effects-" (name a) " .chain-item") #(chain-of a)))
            "the projector chain")))
    (testing "cue items on the clipboard (the effects grid's chain copies as cue items until Step 4b)"
      (open-fx-editor!)
      (ui/click-node! robot (node fx-row))
      (flush!)
      (shortcut! robot KeyCode/C)
      (ui/wait-for! #(= :cue-chain-items (:type (slot))))
      (let [enabled? (not (disabled? fx-paste))]
        (is (= enabled? (items-change-on-paste? robot fx-row fx-items)) "the effects grid's chain"))
      (close-fx-editor!)
      (let [enabled? (not (disabled? chain-paste))]
        (is (not enabled?))
        (is (= enabled? (items-change-on-paste? robot (str "#list-editor-projector-effects-" (name a) " .chain-item") #(chain-of a)))
            "the projector chain")))))

(defn- edit-paste-item ^javafx.scene.control.MenuItem []
  (let [^javafx.scene.control.MenuButton menu (node "#edit-menu")]
    (ui/on-fx-thread #(some (fn [^javafx.scene.control.MenuItem i] (when (= "Paste" (.getText i)) i))
                            (.getItems menu)))))

(defn- edit-paste-disabled? [] (ui/on-fx-thread #(.isDisable (edit-paste-item))))

(defn- cell-paste-disabled?
  "Is the Paste item of the right-click menu of the cell at selector disabled?"
  [selector]
  (let [cell (node selector)]
    (ui/on-fx-thread
     #(let [^javafx.scene.control.Labeled holder (.getParent cell)]
        (first (some (fn [^javafx.scene.control.MenuItem i]
                       (when (str/starts-with? (str (.getText i)) "Paste") [(.isDisable i)]))
                     (.getItems (.getContextMenu holder))))))))

(defn- shortcut-in-main-window!
  "⌘V (Ctrl+V elsewhere) as the main window's scene gets it with nothing
   focused taking it: the Edit menu's accelerator."
  [^KeyCode code]
  (let [meta? (= KeyCode/COMMAND (ui/shortcut-key-code))]
    (ui/on-fx-thread #(javafx.event.Event/fireEvent
                       (node "#main-layout")
                       (javafx.scene.input.KeyEvent. javafx.scene.input.KeyEvent/KEY_PRESSED "" "" code
                                                     false (not meta?) false meta?)))
    (ui/sync-fx!)))

(deftest paste-is-a-maybe-before-the-first-copy-test
  ;; D13 B: with nothing copied in this app, every Paste is on, and a paste
  ;; that finds nothing on the OS clipboard says so
  (let [robot (ui/robot)
        [a _] (two-projectors!)]
    (set-os-text! "hello from another app")
    (is (nil? (slot)))
    (testing "the grid: Edit > Paste and a cell's menu"
      (ui/click! robot "#grid-cell-5-2")
      (ui/wait-for! #(= [5 2] (get-in (raw) [:grid :selected-cell])))
      (ui/wait-for! #(false? (edit-paste-disabled?)))
      (is (false? (cell-paste-disabled? "#grid-cell-5-2")))
      (is (false? (cell-paste-disabled? "#grid-cell-0-0"))))
    (testing "the projector chain"
      (ui/click! robot "#projectors-tab")
      (ui/wait-for! #(some? (node chain-paste)))
      (is (not (disabled? chain-paste))))
    (testing "the Cue Chain Editor's two lists, and a paste that finds nothing"
      (open-cue-editor!)
      (select-cue-item! robot 0)
      (ui/wait-for! #(some? (node item-fx-paste)))
      (is (= [false false] [(disabled? cue-paste) (disabled? item-fx-paste)]))
      (ui/click! robot cue-paste)
      (ui/wait-for! #(= "Nothing to paste here" (get-in (raw) [:ui :notice :text])))
      (ui/wait-for! #(= "Nothing to paste here"
                        (some-> (node "#status-notice-text") (as-> n (ui/on-fx-thread (fn [] (.getText ^javafx.scene.control.Labeled n)))))))
      (is (= [:circle] (mapv :preset-id (cue-items))) "nothing pasted")
      (is (nil? (slot)) "and nothing copied"))
    (is (= [:rgb-curves :axis-transform] (chain-of a)))))

(deftest menu-paste-agrees-with-the-clipboard-test
  ;; Invariant 5: Edit > Paste, the cells' Paste and ⌘V agree. Before Step
  ;; 4a Edit > Paste was on for any clip and the cells' Paste always on
  (let [robot (ui/robot)
        cell #(get-in (raw) [:chains :cue-chains %])]
    (events/dispatch! {:event/type :list/select-item :component-id [:cue-chain 0 0]
                       :item-id (:id (first (cue-items))) :mode :single :items-path cue-items-path})
    (events/dispatch! (copy-event))
    (ui/wait-for! #(= :cue-chain-items (:type (slot))))
    (ui/click! robot "#grid-cell-5-2")
    (ui/wait-for! #(= [5 2] (get-in (raw) [:grid :selected-cell])))
    (testing "cue items copied: nothing a cell takes"
      (ui/wait-for! #(true? (edit-paste-disabled?)))
      (is (true? (cell-paste-disabled? "#grid-cell-5-2")))
      (is (true? (cell-paste-disabled? "#grid-cell-0-0")))
      (shortcut-in-main-window! KeyCode/V)
      (flush!)
      (is (nil? (cell [5 2])) "⌘V does nothing either"))
    (testing "a cue copied: every cell's Paste is on, and ⌘V pastes it"
      (ui/click! robot "#grid-cell-0-0")
      (ui/wait-for! #(= [0 0] (get-in (raw) [:grid :selected-cell])))
      (shortcut-in-main-window! KeyCode/C)
      (ui/wait-for! #(= :cue-chain (:type (slot))))
      (ui/click! robot "#grid-cell-5-2")
      (ui/wait-for! #(= [5 2] (get-in (raw) [:grid :selected-cell])))
      (ui/wait-for! #(false? (edit-paste-disabled?)))
      (is (false? (cell-paste-disabled? "#grid-cell-5-2")))
      (shortcut-in-main-window! KeyCode/V)
      (ui/wait-for! #(= (mapv :preset-id (:items (cell [0 0]))) (mapv :preset-id (:items (cell [5 2])))))
      (is (some? (cell [5 2]))))
    (testing "on the effects tab, a cue on the clipboard: its cells' Paste is off"
      (ui/click! robot "#effects-tab")
      (ui/wait-for! #(some? (node "#effects-cell-0-0")))
      (is (true? (cell-paste-disabled? "#effects-cell-0-0")))
      (ui/wait-for! #(true? (edit-paste-disabled?))))))


;; The step grid copies and pastes through the one clipboard (p18)

(deftest step-grid-paste-follows-the-one-clipboard-test
  ;; p18: before Step 4a the step grid kept a clipboard of its own: its Copy
  ;; reached neither [:ui :clipboard] nor the OS clipboard, and its Paste
  ;; ignored what the lists copied
  (let [target {:domain :cue-zone-patterns :entity-key [0 0] :effect-path [0]}]
    (set-os-text! "")
    (events/dispatch! {:event/type :list/select-item :component-id [:cue-chain 0 0]
                       :item-id (:id (first (cue-items))) :mode :single :items-path cue-items-path})
    (events/dispatch! (copy-event))
    (ui/wait-for! #(= :cue-chain-items (:type (slot))))
    (events/dispatch! {:event/type :step-grid/open :target target})
    (ui/wait-for! #(some? (node "#step-grid-paste")))
    (is (disabled? "#step-grid-paste") "cue items copied: nothing a grid takes")
    (ui/on-fx-thread #(.fire ^javafx.scene.control.ButtonBase (node "#step-grid-copy")))
    (ui/wait-for! #(= :step-grid (:type (slot))))
    (is (= (get-in (raw) [:ui :clipboard :grid :steps]) (get-in (raw) [:chains :cue-chains [0 0] :zone-patterns 0 :params :steps])))
    (ui/wait-for! #(= (slot) (clipboard/<-system-text (os-text))))
    (ui/wait-for! #(not (disabled? "#step-grid-paste")))
    (is (nil? (get-in (raw) [:ui :step-grid :clipboard])) "no clipboard of its own")))
