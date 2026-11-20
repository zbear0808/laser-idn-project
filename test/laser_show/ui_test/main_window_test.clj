(ns laser-show.ui-test.main-window-test
  "Main window: default layout, top-level tabs, typing into the toolbar and the
   unsaved-changes check when the window is closed. The Cue Chain Editor fits
   its smallest size (its footer, a preset's parameters) and its footer's
   controls have tooltips, and the zone group editor looks like the other
   setup dialogs."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.event Event]
           [javafx.scene Node Parent]
           [javafx.scene.control ComboBox Control Labeled]
           [javafx.scene.layout BackgroundFill Region]
           [javafx.scene.paint Color]
           [javafx.stage Stage Window WindowEvent]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(deftest user-can-type-and-switch-tabs
  (let [robot (ui/robot)]
    (ui/fill! robot "#bpm-input" "140")
    (is (= "140" (ui/node-text "#bpm-input")))
    (is (some? (ui/query "#settings-tab")))
    (ui/click! robot "#settings-tab")))

(deftest application-starts-with-default-layout
  (is (ui/visible? "#main-layout"))
  (ui/assert-visible! "#play-btn")
  (ui/assert-visible! "#bpm-input")
  (ui/assert-visible! "#grid-tab")
  (ui/assert-visible! "#preview-panel")
  (ui/assert-visible! "#status-bar")
  (ui/assert-text! "#bpm-input" "120.0")
  (is (false? (get-in (state/get-raw-state) [:playback :playing?])))
  (ui/assert-no-node! "#stop-btn"))

(deftest top-level-tabs-update-content-and-preview
  (let [robot (ui/robot)]
    (doseq [[tab preview?] [["effects" true]
                            ["projectors" false]
                            ["zones" false]
                            ["settings" false]
                            ["grid" true]]]
      (ui/click! robot (str "#" tab "-tab"))
      (ui/wait-for! #(= (keyword tab)
                        (get-in (state/get-raw-state) [:ui :active-tab])))
      (is (= (keyword tab)
             (get-in (state/get-raw-state) [:ui :active-tab])))
      (ui/wait-for! #(= preview? (ui/visible? "#preview-panel")))
      (is (= preview? (ui/visible? "#preview-panel"))))))


;; Closing the window

(def ^:private saved-project "test-output/main-window-test.zip")

(defn- main-stage ^Stage []
  (some-> (first (ui/query-all "#main-layout")) .getScene .getWindow))

(defn- close-main-window!
  "Send the window's close request, as the OS close button does."
  []
  (.interact (ui/robot)
             (reify Runnable
               (run [_]
                 (let [stage (main-stage)]
                   (Event/fireEvent stage (WindowEvent. stage WindowEvent/WINDOW_CLOSE_REQUEST))))))
  (ui/sync-fx!))

(defn- confirm-open? []
  (true? (get-in (state/get-raw-state) [:ui :dialogs :unsaved-confirm :open?])))

(defn- make-project-dirty! []
  (events/dispatch! {:event/type :grid/clear-cell :col 0 :row 0})
  (ui/wait-for! #(true? (get-in (state/get-raw-state) [:project :dirty?]))))

(defn- with-exit-counter
  "Run f with the exit function replaced by a counter, so asking the app to exit
   doesn't end the test JVM."
  [f]
  (let [exits (atom 0)]
    (events/set-exit-fn! #(swap! exits inc))
    (try (f exits)
         (finally (events/set-exit-fn! nil)))))

(deftest closing-a-clean-project-exits-without-asking-test
  (with-exit-counter
    (fn [exits]
      (close-main-window!)
      (ui/wait-for! #(= 1 @exits))
      (is (not (confirm-open?)))
      (ui/assert-no-node! "#unsaved-confirm-dialog"))))

(deftest closing-a-dirty-project-asks-first-test
  (with-exit-counter
    (fn [exits]
      (make-project-dirty!)
      (close-main-window!)
      (ui/wait-for! #(ui/visible? "#unsaved-cancel"))
      (ui/assert-visible! "#unsaved-save")
      (ui/assert-visible! "#unsaved-discard")
      (is (zero? @exits))
      (is (.isShowing (main-stage)) "the window itself was not closed"))))

(deftest cancel-keeps-the-window-and-the-project-test
  (with-exit-counter
    (fn [exits]
      (make-project-dirty!)
      (close-main-window!)
      (ui/wait-for! #(ui/visible? "#unsaved-cancel"))
      (ui/click! (ui/robot) "#unsaved-cancel")
      (ui/wait-for! #(not (confirm-open?)))
      (ui/assert-no-node! "#unsaved-confirm-dialog")
      (is (zero? @exits))
      (is (.isShowing (main-stage)))
      (is (true? (get-in (state/get-raw-state) [:project :dirty?]))))))

(deftest discard-exits-test
  (with-exit-counter
    (fn [exits]
      (make-project-dirty!)
      (close-main-window!)
      (ui/wait-for! #(ui/visible? "#unsaved-discard"))
      (ui/click! (ui/robot) "#unsaved-discard")
      (ui/wait-for! #(= 1 @exits))
      (is (not (confirm-open?))))))

(deftest save-then-exit-test
  (let [file (io/file saved-project)]
    (io/make-parents file)
    (when (.exists file) (.delete file))
    (try
      (with-exit-counter
        (fn [exits]
          ;; a project that already has a file, so no native chooser is needed
          (state/swap-state! assoc-in [:project :current-file] saved-project)
          (make-project-dirty!)
          (close-main-window!)
          (ui/wait-for! #(ui/visible? "#unsaved-save"))
          (ui/click! (ui/robot) "#unsaved-save")
          (ui/wait-for! #(= 1 @exits))
          (is (.exists file) "the project was written before the app was asked to exit")
          (is (false? (get-in (state/get-raw-state) [:project :dirty?])))))
      (finally
        (when (.exists file) (.delete file))))))


;; Dialogs that fit and match

(defn- stage-titled
  "The showing stage whose title starts with prefix, or nil."
  ^Stage [prefix]
  (ui/on-fx-thread
   #(some (fn [^Window w]
            (when (and (instance? Stage w) (.isShowing w)
                       (some-> ^Stage w .getTitle (str/starts-with? prefix)))
              w))
          (vec (Window/getWindows)))))

(defn- scene-root ^Parent [^Stage stage]
  (ui/on-fx-thread #(.getRoot (.getScene stage))))

(defn- all-nodes
  "node and every node under it, skins' included."
  [^Node node]
  (ui/on-fx-thread
   #(vec (tree-seq (fn [n] (instance? Parent n))
                   (fn [^Parent p] (seq (.getChildrenUnmodifiable p)))
                   node))))

(defn- text-of [^Labeled l]
  (ui/on-fx-thread #(.getText l)))

(deftest cue-chain-editor-footer-fits-test
  (events/dispatch! {:event/type :cue-chain/open-editor :col 0 :row 0})
  (ui/wait-for! #(some? (stage-titled "Cue Chain Editor")))
  (let [editor (stage-titled "Cue Chain Editor")
        size #(ui/on-fx-thread (fn [] [(.getWidth editor) (.getHeight editor)]))]
    (testing "it opens larger than the smallest size it can be made"
      (is (= [960.0 640.0] (ui/on-fx-thread #(vector (.getMinWidth editor) (.getMinHeight editor)))))
      (let [[w h] (size)]
        (is (< 960 w))
        (is (< 640 h))))
    (testing "at that size, every label, button and choice in the footer shows its whole text"
      (ui/set-size! editor 960 640)
      (ui/wait-for! #(= [960.0 640.0] (size)))
      (let [footer (ui/on-fx-thread #(.lookup (scene-root editor) ".dialog-footer"))
            labeled (filterv #(instance? Labeled %) (all-nodes footer))
            texts (set (map text-of labeled))]
        (is (every? texts ["Default zone:" "Trigger:" "Open in Timeline Editor" "Close"])
            "the timeline editor's short labels")
        (ui/wait-for! #(every? (fn [l] (= (text-of l) (ui/shown-text l))) labeled))
        (doseq [l labeled]
          (is (= (text-of l) (ui/shown-text l))))))))

(deftest cue-chain-editor-footer-says-what-it-does-test
  (events/dispatch! {:event/type :cue-chain/open-editor :col 0 :row 0})
  (ui/wait-for! #(some? (stage-titled "Cue Chain Editor")))
  (let [footer (ui/on-fx-thread #(.lookup (scene-root (stage-titled "Cue Chain Editor")) ".dialog-footer"))
        controls (filterv #(instance? Control %) (all-nodes footer))
        tip (fn [^Control c] (ui/on-fx-thread #(some-> c .getTooltip .getText)))
        by-text (into {} (keep (fn [c] (when (instance? Labeled c) [(text-of c) (tip c)]))) controls)
        combos (filterv #(instance? ComboBox %) controls)]
    (testing "the two choices say what they choose"
      (is (= 2 (count combos)))
      (is (every? #(seq (tip %)) combos)))
    (testing "and the buttons what they do; Close says it"
      (is (seq (by-text "⚡ Triggers...")))
      (is (seq (by-text "Open in Timeline Editor")))
      (is (contains? by-text "Close")))))

(defn- labeled-with-text
  "The first label or button under root whose text is text."
  [root text]
  (some #(when (and (instance? Labeled %) (= text (text-of %))) %) (all-nodes root)))

(defn- inside?
  "Are bounds b inside bounds a?"
  [[ax0 ay0 ax1 ay1] [bx0 by0 bx1 by1]]
  (and (<= ax0 bx0) (<= bx1 ax1) (<= ay0 by0) (<= by1 ay1)))

(deftest cue-chain-editor-keeps-preset-params-in-view-test
  (let [robot (ui/robot)]
    (events/dispatch! {:event/type :cue-chain/open-editor :col 0 :row 0})
    (ui/wait-for! #(some? (stage-titled "Cue Chain Editor")))
    (let [editor (stage-titled "Cue Chain Editor")
          root (scene-root editor)]
      (ui/set-size! editor 960 640)
      (testing "with an effect of the circle selected, its parameters below take their room"
        (ui/wait-for! #(some? (labeled-with-text root "Scale")))
        (ui/click-node! robot (labeled-with-text root "Scale"))
        (ui/wait-for! #(seq (ui/on-fx-thread (fn [] (.lookupAll root "#list-editor-item-effects-0-0 .chain-item")))))
        (ui/click-node! robot (first (ui/on-fx-thread #(vec (.lookupAll root "#list-editor-item-effects-0-0 .chain-item")))))
        (ui/wait-for! #(some? (labeled-with-text root "PARAMETERS: Scale"))))
      (testing "and the circle's three parameters still show, none scrolled away"
        (let [params (ui/on-fx-thread #(.getParent ^Node (labeled-with-text root "PRESET: Circle")))
              viewport (ui/on-fx-thread #(.lookup ^Node params ".viewport"))
              rows (mapv #(labeled-with-text params %) ["Color" "Radius" "Point Count"])]
          (is (every? some? rows))
          (ui/wait-for! #(every? (fn [row] (inside? (ui/scene-bounds viewport) (ui/scene-bounds row))) rows))
          (doseq [row rows]
            (is (inside? (ui/scene-bounds viewport) (ui/scene-bounds row)) (text-of row))))))))

(defn- fill-hex
  "A region's background colour as #RRGGBB, with its CSS applied."
  [^Region region]
  (ui/on-fx-thread
   #(do (.applyCss region)
        (when-let [^Color c (some-> region .getBackground .getFills ^BackgroundFill first .getFill)]
          (let [byte (fn [v] (Math/round (* 255.0 (double v))))]
            (format "#%02X%02X%02X" (byte (.getRed c)) (byte (.getGreen c)) (byte (.getBlue c))))))))

(defn- dialog-look
  "A setup dialog's background and its name field's."
  [^Stage stage name-field]
  (let [root (scene-root stage)]
    {:background (fill-hex root)
     :field (fill-hex (ui/on-fx-thread #(.lookup root name-field)))}))

(defn- inline-colours
  "The nodes under root that set a colour in their inline style, but plain
   regions: the colour swatches, whose colour is the zone group's."
  [root]
  (filterv (fn [^Node n]
             (and (not= Region (class n))
                  (re-find #"-fx-(background-color|text-fill)" (or (ui/on-fx-thread #(.getStyle n)) ""))))
           (all-nodes root)))

(deftest zone-group-editor-uses-theme-classes-test
  (let [robot (ui/robot)]
    (ui/click! robot "#zones-tab")
    (ui/click! robot "#zone-groups-add-button")
    (ui/wait-for! #(some? (stage-titled "New Zone Group")))
    (let [editor (stage-titled "New Zone Group")
          zone-group-look (dialog-look editor "#zone-group-name-field")]
      (testing "its text and fields take the theme's classes, not colours of their own"
        (is (empty? (inline-colours (scene-root editor))))
        (doseq [field ["#zone-group-name-field" "#zone-group-description-field"]]
          (is (ui/has-style-class? field "text-field") field)))
      (events/dispatch! {:event/type :ui/close-dialog :dialog-id :zone-group-editor})
      (ui/wait-for! #(nil? (stage-titled "New Zone Group")))
      (testing "so it looks like the Add Projector dialog"
        (ui/click! robot "#projectors-tab")
        (ui/click! robot "#projectors-add-manual-button")
        (ui/wait-for! #(some? (stage-titled "Add Projector")))
        (is (= (dialog-look (stage-titled "Add Projector") "#add-projector-name-field")
               zone-group-look))))))

(deftest confirm-dialog-uses-theme-classes-test
  (events/dispatch! {:event/type :ui/open-dialog
                     :dialog-id :confirm
                     :data {:title "Delete \"Balcony\"?"
                            :message "3 cues and 2 projectors use it."
                            :details "A1 Intro · B4 Tunnel · C2 Drop"
                            :choice {:key :retarget-to :label "Send its cues to:"
                                     :options [{:value :all :text "All"} {:value :left :text "Left"}]}
                            :choice-value :all
                            :confirm-label "Delete group"
                            :danger? true
                            :confirm-event {:event/type :ui/close-dialog :dialog-id :confirm}}})
  (ui/wait-for! #(some? (stage-titled "Delete \"Balcony\"?")))
  (let [dialog (stage-titled "Delete \"Balcony\"?")
        root (scene-root dialog)]
    (testing "its text takes the theme's classes, not colours of its own"
      (is (empty? (inline-colours root))))
    (testing "it shows the question, the details, the choice and both buttons"
      (is (some? (labeled-with-text root "3 cues and 2 projectors use it.")))
      (is (some? (labeled-with-text root "A1 Intro · B4 Tunnel · C2 Drop")))
      (is (some? (ui/on-fx-thread #(.lookup root "#confirm-choice"))))
      (is (= "Delete group" (ui/on-fx-thread #(.getText ^Labeled (.lookup root "#confirm-ok")))))
      (is (some? (ui/on-fx-thread #(.lookup root "#confirm-cancel")))))
    (testing "it has the setup dialogs' background"
      (let [background (fill-hex root)]
        (events/dispatch! {:event/type :ui/close-dialog :dialog-id :confirm})
        (ui/wait-for! #(nil? (stage-titled "Delete \"Balcony\"?")))
        (events/dispatch! {:event/type :ui/open-dialog :dialog-id :add-projector-manual})
        (ui/wait-for! #(some? (stage-titled "Add Projector")))
        (is (= (fill-hex (scene-root (stage-titled "Add Projector"))) background))))))

