;; EVIDENCE for plans/2026-11-13-keyboard-focus-window-lifecycle/.
;; Not part of the test suite and not on any classpath.
;;
;; Each deftest prints PROBE lines describing what the app does TODAY (master cb32414,
;; JavaFX 28-ea+11, headless glass). The plan docs quote them. When a phase fixes a
;; behaviour, turn the matching probe into a real test in test/laser_show/ui_test/
;; with an assertion on the NEW behaviour.
;;
;; p1-p8 were written at cb32414. p5-p7 were re-run unchanged at 3229c95 (2026-11-20)
;; for Phase 4 (04-phase4-clipboard.md) and print the same. p9-p17 were added at
;; 3229c95 for Phase 4:
;;   p9   the dev-mode lost-update detector names [:ui :clipboard] after a list copy and
;;        after a cell copy (the copy helpers write the slot outside the pipeline)
;;   p10  a copy and a paste make the event agent wait for the FX thread: with the FX
;;        thread busy for 500 ms, a BPM change queued behind them waits too
;;   p11  the projector chain's Paste button reads the OS clipboard while rendering, and
;;        stays off after a Copy until something else re-renders the panel
;;   p12  the effects grid's chain editor lights Paste for effects it then doesn't paste
;;   p13  the Cue Chain Editor's two lists take each other's items (one clipboard type)
;;   p14  a cell paste keeps the copied ids; an empty cell's copy empties the target cell
;;   p15  a copied list item gets a new id, its :effects keep theirs
;;   p16  Cut with a failing OS write still deletes, and leaves nothing to paste
;;   p17  how long the clipboard's EDN takes to write (pretty-printed) and to read back,
;;        for a small, a large and a timeline-sized clip (no UI; any thread)
;;   p18  the step grid (zones Z6) keeps a clipboard of its own: its Copy reaches neither
;;        [:ui :clipboard] nor the OS clipboard, and its Paste ignores what lists copy
;;        (added at a38ea43)
;; p9 sets -Dlaser-show.dev for its own duration only. p9, p11 and p16 wrap one
;; function with with-redefs (a recorder around the original, or a failing OS write);
;; each says which. p10 keeps the FX thread busy with a 500 ms sleep, to show what
;; waits for it (a render or a file chooser keeps it busy the same way). The OS
;; clipboard here is the headless platform's, not the Mac's. All of p5-p17 were run
;; again at cc9e000 (2026-11-20) and at a38ea43 (2026-11-21) with the same findings.
;; After Step 4a (2026-11-21) p11 and p16 wrap the functions Step 4a reads and writes the
;; OS clipboard with (events/system-clipboard-text, events/put-system-clipboard-text!);
;; the a38ea43 run wrapped the old ones (git history has that version).
;; Since plan 02 (direct state writes, 8723420) a direct write to app state throws under
;; start-test!, so p2, p7, p11, p13 and p14 write their setup with events/write-state!.
;; p6, p7, p13-p15 were re-run at 8723420 (2026-11-22) with the same findings.
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
;;
;; Add name patterns after the namespace to run some of them, e.g. `p5- p9- p16-`.

(ns probes.keyboard-probes-test
  (:require [clojure.test :refer [deftest use-fixtures]]
            [laser-show.animation.chains :as chains]
            [laser-show.animation.cue-chains :as cue-chains]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.state.serialization :as ser]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.application Platform]
           [javafx.event EventHandler]
           [javafx.scene.input KeyCode KeyEvent Clipboard ClipboardContent]
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
    (events/write-state! assoc-in [:playback :playing?] true)
    (ui/sync-fx!)
    ;; A retrigger asks the show clock to restart (show clock 01 §4)
    (let [restart (fn [] (get-in (raw) [:playback :requests :restart :id]))
          before (restart)]
      (ui/press! robot KeyCode/R)
      (Thread/sleep 300) (ui/sync-fx!)
      (say "p2 R in #bpm-input (playing) -> restart requested:" (not= before (restart))
           "(false = no retrigger)"))
    (events/write-state! assoc-in [:grid :selected-cell] [0 0])
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
    (events/write-state! assoc-in [:list-ui [:cue-chain 0 0] :selected-ids] #{first-id})
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


;; Phase 4 at 3229c95: where the clipboard is written and read (state/clipboard.clj,
;; handlers/list.clj:234-281, events/helpers.clj:49-96)

(def ^:private cue-items-path [:chains :cue-chains [0 0] :items])

(defn- copy-event
  "A list copy as list.clj's dispatch-copy-selected! sends it."
  [component-id items-path clipboard-type]
  {:event/type :list/copy-selected :component-id component-id
   :items-path items-path :clipboard-type clipboard-type})

(def ^:private cue-paste-event
  "A paste into the cue chain [0 0] list, as the Cue Chain Editor's list sends it."
  {:event/type :list/paste-items :component-id [:cue-chain 0 0]
   :items-path cue-items-path
   :on-change-event :chain/set-items
   :on-change-params {:domain :cue-chains :entity-key [0 0]}
   :clipboard-type :cue-chain-items})

(defn- set-os-clip! [^String s]
  (run-fx #(.setContent (Clipboard/getSystemClipboard) (doto (ClipboardContent.) (.putString s)))))

(defn- undo-label [] (:description (peek (get-in (raw) [:history :undo-stack]))))

(defn- select-first-cue-item! []
  (events/write-state! assoc-in [:list-ui [:cue-chain 0 0] :selected-ids] #{(:id (first (cue-items)))}))

(deftest p9-the-lost-update-detector-names-the-clipboard
  ;; EXPECT today: both copies report [[:ui :clipboard]] as written by another writer between
  ;; the event's snapshot and its write-back; the list copy's slot is then nil, the cell
  ;; copy's slot is the handler's own value (no :copied-at)
  ;; with-redefs: a recorder around events/check-lost-update! (the original still runs)
  (let [seen (atom [])
        check @#'events/check-lost-update!
        changed-paths @#'events/changed-paths
        old (System/getProperty "laser-show.dev")]
    (System/setProperty "laser-show.dev" "true")
    (try
      (with-redefs [events/check-lost-update!
                    (fn [event-type snapshot replaced]
                      (when-not (identical? snapshot replaced)
                        (swap! seen conj [event-type (vec (changed-paths snapshot replaced 1))]))
                      (check event-type snapshot replaced))]
        (select-first-cue-item!)
        (events/dispatch! (copy-event [:cue-chain 0 0] cue-items-path :cue-chain-items))
        (flush!)
        (say "p9 list copy: written by another writer meanwhile ="
             (filterv #(= :list/copy-selected (first %)) @seen)
             "; [:ui :clipboard] after =" (get-in (raw) [:ui :clipboard]))
        (events/dispatch! {:event/type :grid/copy-cell :col 0 :row 0})
        (flush!)
        (say "p9 cell copy: written by another writer meanwhile ="
             (filterv #(= :grid/copy-cell (first %)) @seen)
             "; slot keys after =" (vec (sort (keys (get-in (raw) [:ui :clipboard]))))))
      (finally
        (if old (System/setProperty "laser-show.dev" old) (System/clearProperty "laser-show.dev"))))))

(defn- ms-until-marker!
  "Dispatch events, then a BPM marker: ms until the marker is in state, i.e. until
   the agent has run everything queued before it."
  [evs]
  (let [marker (+ 61.5 (rand-int 100))
        t0 (System/nanoTime)]
    (doseq [e evs] (events/dispatch! e))
    (events/dispatch! {:event/type :timing/set-bpm :bpm marker})
    (loop []
      (if (= marker (get-in (raw) [:timing :bpm]))
        (/ (- (System/nanoTime) t0) 1e6)
        (do (Thread/sleep 1) (recur))))))

(defn- busy-fx! [ms] (Platform/runLater #(Thread/sleep (long ms))))

(deftest p10-copy-and-paste-make-the-event-agent-wait-for-the-fx-thread
  ;; EXPECT today: the system clipboard refuses a call off the FX thread; with the FX thread
  ;; busy for 500 ms, a BPM change is applied at once on its own, and only after ~500 ms
  ;; behind a list copy or a list paste
  (let [copy (copy-event [:cue-chain 0 0] cue-items-path :cue-chain-items)
        r #(Math/round (double %))]
    (say "p10 the system clipboard's getString off the FX thread ->"
         (let [cb (run-fx #(Clipboard/getSystemClipboard))]
           (try (.getString ^Clipboard cb) "allowed"
                (catch IllegalStateException e (str "IllegalStateException: " (.getMessage e))))))
    (select-first-cue-item!)
    (flush!)
    (busy-fx! 500)
    (let [alone (ms-until-marker! [])]
      (ui/sync-fx!)
      (busy-fx! 500)
      (let [behind-copy (ms-until-marker! [copy])]
        (ui/sync-fx!)
        (busy-fx! 500)
        (let [behind-paste (ms-until-marker! [cue-paste-event])]
          (ui/sync-fx!)
          (say "p10 FX thread busy for 500 ms: a BPM change is applied after" (r alone) "ms on its own,"
               (r behind-copy) "ms behind a list copy," (r behind-paste) "ms behind a list paste"))))
    (let [idle (sort (mapv (fn [_] (ms-until-marker! [copy])) (range 21)))]
      (say "p10 FX thread idle: a list copy then a BPM change, median of 21 ="
           (format "%.1f ms" (double (nth idle 10))) "(min" (format "%.1f," (double (first idle)))
           "max" (format "%.1f)" (double (last idle)))))))

(defn- with-two-projectors!
  "Projectors :a and :b, :a selected; :a's chain is RGB curves and an Axis Transform,
   the Axis Transform selected (setup_dialogs_test's with-calibration-chains!)."
  []
  (events/write-state!
   #(-> %
        (assoc :projectors {:a {:name "Fake 1" :host "127.0.0.1" :port 7255 :service-id 1
                                :enabled? true :zone-groups [] :exclude-from-all? false
                                :output-config {:color-bit-depth 8 :xy-bit-depth 16}}
                            :b {:name "Fake 2" :host "127.0.0.2" :port 7255 :service-id 1
                                :enabled? true :zone-groups [:all]
                                :output-config {:color-bit-depth 8 :xy-bit-depth 16}}})
        (assoc-in [:projector-ui :active-projector] :a)
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

(defn- reader-site
  "Where an OS clipboard read happens: the thread, and the view or handler on the stack."
  []
  (let [st (mapv str (.getStackTrace (Thread/currentThread)))
        on? (fn [re] (some #(re-find re %) st))]
    [(.getName (Thread/currentThread))
     (cond (on? #"projector_config_panel") :rendering-projector-config-panel
           (on? #"effect_chain_editor_content") :rendering-effect-chain-editor
           (on? #"handle_paste_items") :paste-handler
           (on? #"effect_clipboard_read") :clipboard-read-effect
           :else :other)]))

(defn- disabled-now? [selector]
  (let [n (ui/query selector)]
    (run-fx #(.isDisabled ^javafx.scene.Node n))))

(deftest p11-projector-chain-paste-reads-the-os-clipboard-while-rendering
  ;; EXPECT today: OS clipboard reads on the FX thread inside projector-config-panel; after
  ;; Copy, the same chain's Paste stays disabled until a selection change re-renders it
  ;; with-redefs: a recorder around the function that reads the OS clipboard (the original
  ;; runs): clipboard/read-from-system-clipboard before Step 4a, events/system-clipboard-text,
  ;; the :clipboard/read effect's, since
  (let [robot (ui/robot)
        reads (atom [])
        orig @#'events/system-clipboard-text]
    (set-os-clip! "")
    (with-two-projectors!)
    (with-redefs [events/system-clipboard-text (fn [] (swap! reads conj (reader-site)) (orig))]
      (ui/click! robot "#projectors-tab")
      (ui/wait-for! #(some? (ui/query "#chain-copy-selection")))
      (let [n-mount (count @reads)]
        (say "p11 OS clipboard reads while the Projectors tab mounts =" (frequencies @reads))
        (say "p11 the chain's Paste disabled before Copy =" (disabled-now? "#chain-paste-items"))
        (ui/click! robot "#chain-copy-selection")
        (flush!)
        (say "p11 after Copy: OS head =" (some-> (os-clip) (subs 0 21))
             "; [:ui :clipboard] =" (get-in (raw) [:ui :clipboard])
             "; the same chain's Paste disabled =" (disabled-now? "#chain-paste-items")
             "; OS reads since mount =" (- (count @reads) n-mount))
        (events/dispatch! {:event/type :list/select-item :component-id [:projector-effects :a]
                           :item-id #uuid "00000000-0000-0000-0000-0000000000a1" :mode :single})
        (flush!)
        (say "p11 after selecting the other effect (the panel re-renders): Paste disabled ="
             (disabled-now? "#chain-paste-items") "; OS reads since mount =" (- (count @reads) n-mount)
             (frequencies (drop n-mount @reads)))))))

(deftest p12-effect-chain-editor-lights-paste-for-effects-it-does-not-paste
  ;; EXPECT today: with effects (:item-effects, what a projector chain's Copy puts there) on
  ;; the OS clipboard, the effects grid's chain editor enables Paste; clicking it pastes nothing
  (let [robot (ui/robot)
        n-items #(count (get-in (raw) [:chains :effect-chains [0 3] :items]))]
    (set-os-clip! (ser/serialize-for-clipboard
                   {:type :item-effects :copied-at 1
                    :effects [{:id (random-uuid) :effect-id :scale :enabled? true :params {}}]}))
    (events/dispatch! {:event/type :effect-chain/open-editor :col 0 :row 3})
    (ui/wait-for! #(some (fn [t] (and t (.startsWith ^String t "Effects Chain"))) (ui/window-titles)))
    (ui/wait-for! #(some? (ui/query "#chain-paste-items")))
    (let [n0 (n-items)]
      (say "p12 the editor's Paste disabled =" (disabled-now? "#chain-paste-items"))
      (ui/click-node! robot (ui/query "#chain-paste-items"))
      (flush!)
      (ui/sync-fx!)
      (say "p12 click Paste -> effects in cell [0 3] before/after =" n0 (n-items)
           "; notice =" (pr-str (get-in (raw) [:ui :notice :text]))
           "; [:ui :clipboard] type =" (get-in (raw) [:ui :clipboard :type])
           "; Paste disabled now =" (disabled-now? "#chain-paste-items")))))

(deftest p13-the-cue-editor-lists-take-each-others-items
  ;; EXPECT today: a preset lands in an item's effects, and an effect among the cue's items
  ;; (both lists copy and paste as :cue-chain-items, the default)
  (let [effects-path (conj cue-items-path 0 :effects)
        fx-id (random-uuid)
        shape #(mapv (fn [i] (select-keys i [:type :preset-id :effect-id])) %)]
    (select-first-cue-item!)
    (events/dispatch! (copy-event [:cue-chain 0 0] cue-items-path :cue-chain-items))
    (flush!)
    ;; the item effects list's paste, with the props cue_chain_editor.clj:548-571 gives it
    (events/dispatch! {:event/type :list/paste-items :component-id [:item-effects 0 0]
                       :items-path effects-path
                       :on-change-event :cue-chain/set-item-effects
                       :on-change-params {:col 0 :row 0 :item-path [0]}
                       :clipboard-type :cue-chain-items})
    (flush!)
    (say "p13 item 0's effects after pasting a copied cue item =" (shape (get-in (raw) effects-path)))
    (events/write-state! #(-> %
                            (assoc-in effects-path [{:id fx-id :effect-id :scale :enabled? true :params {}}])
                            (assoc-in [:list-ui [:item-effects 0 0] :selected-ids] #{fx-id})
                            (assoc-in [:list-ui [:cue-chain 0 0]] {:selected-ids #{} :last-selected-id nil})))
    (events/dispatch! (copy-event [:item-effects 0 0] effects-path :cue-chain-items))
    (flush!)
    (events/dispatch! cue-paste-event)
    (flush!)
    (say "p13 cue chain [0 0] after pasting a copied item effect =" (shape (cue-items)))))

(deftest p14-cell-paste-keeps-ids-and-an-empty-copy-empties-a-cell
  ;; EXPECT today: A1 and B2 share item ids after copy/paste; Edit > Copy on an empty cell
  ;; stores {:type :cue-chain :data nil}, and Edit > Paste of it leaves the target cell nil
  (events/dispatch! {:event/type :grid/copy-cell :col 0 :row 0})
  (flush!)
  (events/dispatch! {:event/type :grid/paste-cell :col 1 :row 1})
  (flush!)
  (let [ids #(mapv :id (get-in (raw) [:chains :cue-chains % :items]))]
    (say "p14 copy A1, paste on B2: the same item ids =" (= (ids [0 0]) (ids [1 1]))))
  (events/write-state! #(-> % (assoc-in [:ui :active-tab] :grid) (assoc-in [:grid :selected-cell] [5 2])))
  (events/dispatch! {:event/type :edit/copy})
  (flush!)
  (say "p14 Edit > Copy on the empty cell [5 2] -> [:ui :clipboard] =" (get-in (raw) [:ui :clipboard]))
  (events/write-state! assoc-in [:grid :selected-cell] [2 0])
  (let [before (get-in (raw) [:chains :cue-chains [2 0] :items 0 :preset-id])]
    (events/dispatch! {:event/type :edit/paste})
    (flush!)
    (say "p14 Edit > Paste on [2 0] (a" before "cue) -> [2 0] =" (get-in (raw) [:chains :cue-chains [2 0]])
         "; undo label =" (undo-label))))

(deftest p15-a-copied-item-keeps-its-effects-ids
  ;; EXPECT today: the copy has a new :id, its :effects keep theirs, in a group too
  (let [e1 (random-uuid)
        item {:id (random-uuid) :type :preset :preset-id :circle :effects [{:id e1 :effect-id :scale}]}
        c (chains/deep-copy-item item)
        g (chains/deep-copy-item {:id (random-uuid) :type :group :items [item]})]
    (say "p15 deep-copy-item: new item id =" (not= (:id item) (:id c))
         "; its effect's id kept =" (= e1 (get-in c [:effects 0 :id])))
    (say "p15 in a group: new child id =" (not= (:id item) (get-in g [:items 0 :id]))
         "; the child's effect's id kept =" (= e1 (get-in g [:items 0 :effects 0 :id])))))

(deftest p16-cut-with-a-failing-os-write-deletes-and-leaves-nothing-to-paste
  ;; EXPECT today: items 3 -> 2, [:ui :clipboard] nil, the OS clipboard unchanged, then
  ;; paste 2 -> 2; the undo label is the delete's
  ;; with-redefs: the OS write fails. Before Step 4a clipboard/copy-to-system-clipboard!
  ;; returned false, what it returned when setContent threw (state/clipboard.clj:71-73);
  ;; since Step 4a events/put-system-clipboard-text! throws, as setContent would
  (let [robot (ui/robot)]
    (set-os-clip! "")
    (open-cue-editor!)
    (add-cue-items! [:square :triangle])
    (ui/click-node! robot (nth (ui/query-all ".chain-item") 1))
    (ui/wait-for! #(seq (selected)))
    (with-redefs [events/put-system-clipboard-text! (fn [_] (throw (IllegalStateException. "no pasteboard")))]
      (let [n0 (count (cue-items))]
        (push-combo! robot (ui/shortcut-key-code) KeyCode/X)
        (flush!)
        (say "p16 Shortcut+X with the OS write failing -> items" n0 "->" (count (cue-items))
             "; [:ui :clipboard] =" (get-in (raw) [:ui :clipboard]) "; OS =" (pr-str (os-clip))
             "; undo label =" (undo-label))
        (let [n1 (count (cue-items))]
          (push-combo! robot (ui/shortcut-key-code) KeyCode/V)
          (flush!)
          (say "p16 then Shortcut+V -> items" n1 "->" (count (cue-items))))))))

(defn- median-ms
  "Median wall time of (f) over 11 runs, after 5 warm-up runs."
  [f]
  (dotimes [_ 5] (f))
  (let [runs (sort (for [_ (range 11)]
                     (let [t0 (System/nanoTime)] (f) (/ (- (System/nanoTime) t0) 1e6))))]
    (nth runs 5)))

(deftest p17-how-long-the-clipboard-edn-takes
  ;; EXPECT: pretty-printing grows with the clip; reading back is several times faster
  (let [fx (fn [] {:id (random-uuid) :effect-id :scale :enabled? true
                   :params {:x-scale 1.0 :y-scale 1.0 :uniform? true}})
        preset (fn [n-fx] (assoc (cue-chains/create-preset-instance :circle) :effects (vec (repeatedly n-fx fx))))
        tracks (vec (for [i (range 8)] {:id (random-uuid) :type :track :name (str "Track " i) :effects []}))
        clips (vec (for [i (range 300)]
                     (assoc (preset 2) :track-id (:id (nth tracks (mod i 8)))
                            :timeline/start (double i) :timeline/duration 1.0)))
        cases [["3 presets (a small list copy)" {:type :cue-chain-items :copied-at 1 :items (vec (repeatedly 3 #(preset 0)))}]
               ["50 presets x 3 effects" {:type :cue-chain-items :copied-at 1 :items (vec (repeatedly 50 #(preset 3)))}]
               ["a timeline cue cell, 300 clips x 2 effects" {:type :cue-chain :copied-at 1
                                                              :data {:editor-type :timeline :tracks tracks :items clips}}]]]
    (doseq [[label clip] cases]
      (let [text (ser/serialize-for-clipboard clip)]
        (say "p17" label ":" (count text) "chars; write (pprint)" (format "%.2f ms" (double (median-ms #(ser/serialize-for-clipboard clip))))
             "; write (pr-str)" (format "%.2f ms" (double (median-ms #(pr-str clip))))
             "; read back" (format "%.2f ms" (double (median-ms #(ser/deserialize text :silent? true)))))))))

(deftest p18-the-step-grid-has-a-clipboard-of-its-own
  ;; EXPECT at a38ea43: the step grid's Copy (zones Z6) writes [:ui :step-grid :clipboard]
  ;; only, so the one clipboard and the OS clipboard keep what a list copied; its Paste is
  ;; off before any copy and doesn't follow what the other lists copy
  (set-os-clip! "")
  (let [target {:domain :cue-zone-patterns :entity-key [0 0] :effect-path [0]}
        head #(when-let [s (os-clip)] (subs s 0 (min 24 (count s))))]
    (events/dispatch! {:event/type :step-grid/open :target target})
    (ui/wait-for! #(some? (ui/query "#step-grid-paste")))
    (say "p18 the step grid's Paste disabled before any copy =" (disabled-now? "#step-grid-paste"))
    (select-first-cue-item!)
    (events/dispatch! (copy-event [:cue-chain 0 0] cue-items-path :cue-chain-items))
    (flush!)
    (say "p18 after a cue list copy: the step grid's Paste disabled =" (disabled-now? "#step-grid-paste")
         "; OS head =" (pr-str (head)))
    (events/dispatch! {:event/type :step-grid/copy :target target})
    (flush!)
    (say "p18 after the step grid's Copy: [:ui :step-grid :clipboard] keys ="
         (vec (sort (keys (get-in (raw) [:ui :step-grid :clipboard]))))
         "; [:ui :clipboard] type =" (get-in (raw) [:ui :clipboard :type])
         "; OS head =" (pr-str (head))
         "; Paste disabled =" (disabled-now? "#step-grid-paste"))))
