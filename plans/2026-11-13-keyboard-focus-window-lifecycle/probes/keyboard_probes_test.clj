;; EVIDENCE for plans/2026-11-13-keyboard-focus-window-lifecycle/.
;; Not part of the test suite and not on any classpath.
;;
;; Each deftest prints PROBE lines describing what the app does TODAY (master cb32414,
;; JavaFX 28-ea+11, headless glass). The plan docs quote them. When a phase fixes a
;; behaviour, turn the matching probe into a real test in test/laser_show/ui_test/
;; with an assertion on the NEW behaviour.
;;
;; Run from the repo root (macOS Apple Silicon; use :win, :linux or :mac elsewhere).
;; The plan folder is the classpath root, so this namespace is probes.keyboard-probes-test:
;;
;;   export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
;;   export PATH="$JAVA_HOME/bin:$PATH"
;;   P=plans/2026-11-13-keyboard-focus-window-lifecycle
;;   clj -Sdeps "{:aliases {:probe {:extra-paths [\"$P\"]}}}" \
;;       -M:test-ui:mac-arm:test-ui-headless:probe -d "$P/probes" \
;;       -n probes.keyboard-probes-test 2>&1 | grep PROBE

(ns probes.keyboard-probes-test
  (:require [clojure.test :refer [deftest use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.event EventHandler]
           [javafx.scene.input KeyCode KeyEvent Clipboard]
           [javafx.stage Window]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

;; ui/run-on-fx-thread! is private in the DSL
(def ^:private run-fx @#'ui/run-on-fx-thread!)

(defn- say [& xs] (apply println "PROBE" xs))
(defn- playing? [] (boolean (get-in (state/get-raw-state) [:playback :playing?])))
(defn- raw [] (state/get-raw-state))

(defn- flush!
  "Wait until every event dispatched so far was handled (events run in order)."
  []
  (ui/sync-fx!)
  (let [marker (+ 60.25 (rand-int 100))]
    (events/dispatch! {:event/type :timing/set-bpm :bpm marker})
    (ui/wait-for! #(= marker (get-in (raw) [:timing :bpm])))))

(defn- push-combo! [robot & codes]
  (.push ^org.testfx.api.FxRobot robot (into-array KeyCode codes))
  (ui/sync-fx!))

(defn- focus-owners []
  (run-fx
   (fn []
     (vec (for [^Window w (Window/getWindows) :when (.isShowing w)
                :let [o (some-> w .getScene .getFocusOwner)]]
            (when o (str (.getSimpleName (class o)) (when-let [id (.getId o)] (str "#" id)))))))))

(defn- cue-items [] (get-in (raw) [:chains :cue-chains [0 0] :items]))
(defn- selected [] (get-in (raw) [:list-ui [:cue-chain 0 0] :selected-ids]))
(defn- os-clip [] (run-fx #(.getString (Clipboard/getSystemClipboard))))

(defn- open-cue-editor! []
  (events/dispatch! {:event/type :cue-chain/open-editor :col 0 :row 0})
  (ui/wait-for! #(some (fn [t] (and t (.startsWith ^String t "Cue Chain Editor"))) (ui/window-titles)))
  (ui/assert-visible! ".chain-item"))

(defn- add-cue-items! [ids]
  (doseq [id ids]
    (events/dispatch! {:event/type :cue-chain/add-preset :col 0 :row 0 :item-id id}))
  (ui/wait-for! #(= (+ 1 (count ids)) (count (ui/query-all ".chain-item")))))


;; Main-window accelerators (title_bar.clj:143-163)

(deftest p1-space-accelerator-fires-when-focus-is-not-a-text-field
  ;; EXPECT today: playing? true
  (let [robot (ui/robot)]
    (ui/focus! robot "#main-layout")
    (ui/press! robot KeyCode/SPACE)
    (Thread/sleep 400) (ui/sync-fx!)
    (say "p1 Space with focus on" (focus-owners) "-> playing? =" (playing?))))

(deftest p2-single-key-accelerators-do-not-fire-inside-a-text-field
  ;; EXPECT today: playing? false, retrigger did not reset the clock, cell kept its items
  (let [robot (ui/robot)]
    (ui/focus! robot "#bpm-input")
    (ui/press! robot KeyCode/SPACE)
    (Thread/sleep 300) (ui/sync-fx!)
    (say "p2 Space in #bpm-input -> playing? =" (playing?))
    (state/swap-state! assoc-in [:playback :playing?] true)
    (state/swap-state! assoc-in [:timing :global-clock :accumulated-beats] 5.0)
    (ui/sync-fx!)
    (ui/press! robot KeyCode/R)
    (Thread/sleep 300) (ui/sync-fx!)
    (say "p2 R in #bpm-input (playing) -> global-clock beats stayed"
         (get-in (raw) [:timing :global-clock :accumulated-beats]) "(5.0 = no retrigger)")
    (state/swap-state! assoc-in [:grid :selected-cell] [0 0])
    (ui/sync-fx!)
    (let [before (count (cue-items))]
      (ui/press! robot KeyCode/DELETE)
      (Thread/sleep 300) (ui/sync-fx!)
      (say "p2 Delete in #bpm-input -> cell [0 0] items before/after =" before (count (cue-items))))))


(deftest p8-a-focused-button-consumes-space-before-the-scene-sees-it
  ;; EXPECT today: the scene FILTER sees KEY_PRESSED and KEY_RELEASED (target Button);
  ;; the scene's BUBBLE phase sees neither, so a menu accelerator on Space cannot fire.
  ;; (Headless mode cannot show what the button then does with the key.)
  (let [robot (ui/robot)
        log (atom [])]
    (run-fx
     (fn []
       (let [scene (.getScene ^javafx.scene.Node (ui/query "#main-layout"))]
         (.addEventFilter scene KeyEvent/ANY
                          (reify EventHandler
                            (handle [_ e] (swap! log conj [:filter (str (.getEventType e)) (str (.getCode e))
                                                           (some-> (.getTarget e) class .getSimpleName)]))))
         (.addEventHandler scene KeyEvent/ANY
                           (reify EventHandler
                             (handle [_ e] (swap! log conj [:bubble (str (.getEventType e)) (str (.getCode e))])))))))
    (ui/click! robot "#tap-tempo-btn")
    (ui/wait-for! #(= 1 (count (get-in (raw) [:timing :tap-times]))))
    (reset! log [])
    (ui/press! robot KeyCode/SPACE)
    (Thread/sleep 400) (ui/sync-fx!)
    (say "p8 focus owners =" (focus-owners))
    (say "p8 key events with TAP focused =" @log)))


;; List keyboard handling (list.clj:716-790)

(deftest p3-list-focus-and-shortcuts-after-clicking-an-item
  ;; EXPECT today: focus owner is the list-editor VBox (not a ScrollPane); Delete removes one item;
  ;;               Shortcut+A selects all
  (let [robot (ui/robot)]
    (open-cue-editor!)
    (add-cue-items! [:square :triangle])
    (ui/click-node! robot (nth (ui/query-all ".chain-item") 1))
    (ui/wait-for! #(seq (selected)))
    (say "p3 focus owners after clicking an item =" (focus-owners))
    (push-combo! robot (ui/shortcut-key-code) KeyCode/A)
    (flush!)
    (say "p3 Shortcut+A -> selected" (count (selected)) "of" (count (cue-items)))
    (let [n0 (count (cue-items))]
      (ui/press! robot KeyCode/DELETE)
      (flush!)
      (say "p3 Delete -> items before/after =" n0 (count (cue-items))))))

(deftest p4-list-shortcuts-are-ignored-after-focus-moves-to-a-toolbar-button
  ;; EXPECT today: items before/after = 3 3
  (let [robot (ui/robot)]
    (open-cue-editor!)
    (add-cue-items! [:square :triangle])
    (ui/click-node! robot (nth (ui/query-all ".chain-item") 1))
    (ui/wait-for! #(seq (selected)))
    (ui/focus! robot "#list-editor-cue-chain-0-0 #chain-new-group")
    (say "p4 focus owners =" (focus-owners))
    (let [n0 (count (cue-items))]
      (ui/press! robot KeyCode/DELETE)
      (flush!)
      (say "p4 Delete with focus on the toolbar button -> items before/after =" n0 (count (cue-items))
           " selected =" (count (selected))))))


;; Clipboard (state/clipboard.clj, handlers/list.clj:234-278)

(defn- paste-buttons-disabled []
  (run-fx (fn []
            (mapv #(.isDisabled ^javafx.scene.Node %)
                  (filter #(and (instance? javafx.scene.control.Button %)
                                (= "Paste" (.getText ^javafx.scene.control.Button %)))
                          (ui/query-all ".chain-toolbar-btn"))))))

(deftest p5-list-copy-lands-only-on-the-os-clipboard-and-paste-button-stays-disabled
  ;; EXPECT today: internal nil; OS head {:type :cue-chain-items; paste buttons [true true]; paste works 3 -> 4
  (let [robot (ui/robot)]
    (open-cue-editor!)
    (add-cue-items! [:square :triangle])
    (ui/click-node! robot (nth (ui/query-all ".chain-item") 1))
    (ui/wait-for! #(seq (selected)))
    (push-combo! robot (ui/shortcut-key-code) KeyCode/C)
    (flush!)
    (say "p5 [:ui :clipboard] after list copy =" (get-in (raw) [:ui :clipboard]))
    (say "p5 OS clipboard head =" (some-> (os-clip) (subs 0 24)))
    (say "p5 toolbar Paste buttons disabled? =" (paste-buttons-disabled))
    (let [n0 (count (cue-items))]
      (push-combo! robot (ui/shortcut-key-code) KeyCode/V)
      (flush!)
      (say "p5 Shortcut+V -> items before/after =" n0 (count (cue-items))))))

(deftest p6-grid-cell-copy-is-not-pasteable-into-a-list
  ;; EXPECT today: items before/after = 1 1
  (let [robot (ui/robot)]
    (events/dispatch! {:event/type :grid/copy-cell :col 0 :row 0})
    (flush!)
    (say "p6 internal clipboard type after :grid/copy-cell =" (get-in (raw) [:ui :clipboard :type]))
    (open-cue-editor!)
    (ui/click-node! robot (nth (ui/query-all ".chain-item") 0))
    (ui/wait-for! #(seq (selected)))
    (let [n0 (count (cue-items))]
      (push-combo! robot (ui/shortcut-key-code) KeyCode/V)
      (flush!)
      (say "p6 list paste of a cell copy -> items before/after =" n0 (count (cue-items))))))

(deftest p7-cue-items-can-be-pasted-into-an-effect-list
  ;; EXPECT today: the effect chain [0 0] ends up holding a :type :preset item
  (let [first-id (:id (first (cue-items)))]
    (state/assoc-in-state! [:list-ui [:cue-chain 0 0] :selected-ids] #{first-id})
    (events/dispatch! {:event/type :list/copy-selected
                       :component-id [:cue-chain 0 0]
                       :items-path [:chains :cue-chains [0 0] :items]})
    (flush!)
    (events/dispatch! {:event/type :list/paste-items
                       :component-id [:effect-chain 0 0]
                       :items-path [:chains :effect-chains [0 0] :items]
                       :on-change-event :chain/set-items
                       :on-change-params {:domain :effect-chains :entity-key [0 0]}})
    (flush!)
    (say "p7 effect chain [0 0] after pasting cue items ="
         (mapv #(select-keys % [:preset-id :effect-id :type])
               (get-in (raw) [:chains :effect-chains [0 0] :items])))))
