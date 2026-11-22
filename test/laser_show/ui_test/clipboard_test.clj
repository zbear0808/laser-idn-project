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
