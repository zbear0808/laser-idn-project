(ns laser-show.views.components.rig-strip
  "The rig strip on the Projectors tab (plans/2026-11-19-zones/04 §7): one
   card per projector output, left to right in rig order (routing.rig), each
   with its place in E, its name and address, its output status and its On
   box, and a menu (Identify, turn on every output of its device).

   Reordering: drag a card onto another (its left half puts it before, its
   right half after, and a bar shows where), or select it and press ◀ ▶ or
   Alt+← / Alt+→; ← and → alone move the selection. The group rows line up
   under the cards in the same scroll pane (views/components/group_matrix).

   A card is built from a vector of sections. Its load section (zones plan
   Z3) shows what the laser really sent (preview/output-thumbnail), a load
   meter and what the fit guard did; below its minimum fps the card turns
   amber."
  (:require [cljfx.api :as fx]
            [laser-show.css.core :as css]
            [laser-show.events.core :as events]
            [laser-show.subs :as subs]
            [laser-show.views.components.drag-drop-cell :as dnd]
            [laser-show.views.components.group-matrix :as group-matrix]
            [laser-show.views.components.load-warning :as load-warning]
            [laser-show.views.components.preview :as preview])
  (:import [javafx.application Platform]
           [javafx.beans.value ChangeListener]
           [javafx.scene Node]
           [javafx.scene.control ScrollPane]
           [javafx.scene.input DragEvent KeyCode KeyEvent MouseButton MouseEvent TransferMode]
           [javafx.scene.layout GridPane Region]))


(def card-width
  "Every card's width, so the group rows under the cards line up with them."
  136)

(def ^:private thumbnail-size
  "A card's thumbnail is this square, beside its meter and note."
  44)

(defn- id-text
  "A projector id as part of a node id."
  [id]
  (if (keyword? id) (name id) (str id)))


;; Status (the dot and the line)


(defn status-indicator
  "Colored circle indicating device/connection status."
  [{:keys [id status size]
    :or {size 8}}]
  (let [color (case status
                :online (css/green)
                :occupied (css/orange)
                :offline (css/red)
                :connected (css/green)
                :connecting (css/blue)
                (css/text-muted))]
    (cond-> {:fx/type :circle
             :radius (/ size 2)
             :fill color}
      id (assoc :id id))))

(defn output-status-text
  "One line for a projector's output status, or nil when it isn't streaming.
   The fps is how many new frames the projector draws per second; a warning
   (a calibration effect skipped because it fails, or the output driver's
   own) comes last. A safety error (zones plan Z1) is shown as it is:
   \"Safety error: output off\", and so is a device another program has
   (:busy?, output drivers 1D): \"In use by ShowCtl on 10.0.0.3\"."
  [{:keys [state kind fps scan-limited? error warning busy?]}]
  (case state
    :streaming (cond-> (cond
                         (nil? fps) "Streaming"
                         scan-limited? (format "Streaming · %.1f fps (limited by scan rate)" (double fps))
                         :else (format "Streaming · %.1f fps" (double fps)))
                 warning (str " · " warning))
    :starting "Starting…"
    :error (if (or (= :safety kind) busy?) error (str "Error: " error))
    nil))

(defn card-status-text
  "{:text :tooltip} of a card's status line, or nil for none: the output's
   status (output-status-text), but short where the core's own message
   doesn't fit a card (\"Driver not installed\", \"Driver not responding\"),
   the whole message in the tooltip; with no status, \"Not found in the last
   scan\" when a scan this session didn't find it."
  [status {:keys [found? driver-installed?]}]
  (let [{:keys [state kind error detail]} status
        text (output-status-text status)]
    (cond
      (and (= :error state) (= :stuck kind)) {:text "Driver not responding" :tooltip error}
      (and (= :error state) (false? driver-installed?)) {:text "Driver not installed" :tooltip error}
      text {:text text :tooltip (or detail text)}
      (not found?) {:text "Not found in the last scan" :tooltip "Not found in the last scan"})))


;; The header


(defn header-text
  "What the strip's header says of its cards: \"4 outputs · 3 on\", or what
   to do when none is on or there are none."
  [cards]
  (let [n (count cards)
        on (count (filter :enabled? cards))]
    (cond
      (zero? n) "No lasers yet: Scan Network finds them, or add one with Add Manual..."
      (zero? on) "Tick On on a card to send it output."
      :else (str n (if (= 1 n) " output" " outputs") " · " on " on"))))

(defn rig-header
  "Scan Network, Add Manual... and what the cards hold; at the right, the
   middle laser and the split width (group-matrix/rig-settings)."
  [{:keys [fx/context]}]
  (let [cards (fx/sub-ctx context subs/rig-cards)
        scanning? (fx/sub-ctx context subs/projector-scanning?)]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children (into [{:fx/type :button
                       :id "projectors-scan-button"
                       :text (if scanning? "Scanning..." "Scan Network")
                       :disable scanning?
                       :style-class ["button-primary"]
                       :on-action {:event/type :projectors/scan-network}}
                      {:fx/type :button
                       :id "projectors-add-manual-button"
                       :text "Add Manual..."
                       :style-class ["button-secondary"]
                       :on-action {:event/type :ui/open-dialog
                                   :dialog-id :add-projector-manual}}
                      {:fx/type :label
                       :id "rig-header-count"
                       :text (header-text cards)
                       :style-class ["label-secondary"]}
                      {:fx/type :region :h-box/hgrow :always}
                      {:fx/type group-matrix/rig-settings
                       :settings (fx/sub-ctx context subs/rig-settings)}])}))


;; A card's sections


(defn- card-title [{:keys [id position excluded?] card-name :name}]
  {:fx/type :h-box
   :spacing 6
   :alignment :center-left
   :children [(cond-> {:fx/type :label
                       :id (str "rig-card-number-" (id-text id))
                       :text (if position (str position) "–")
                       :min-width :use-pref-size
                       :style "-fx-text-fill: #A0A0A0; -fx-font-size: 12; -fx-font-weight: bold;"}
                (and excluded? (not position))
                (assoc :tooltip {:fx/type :tooltip :text "Not in All or the automatic groups"}))
              {:fx/type :label
               :id (str "rig-card-name-" (id-text id))
               :text card-name
               :max-width (- card-width 40)
               ;; the card cuts a long name off: all of it here
               :tooltip {:fx/type :tooltip :text (str card-name)}
               :style-class ["label-bold"]}]})

(defn- card-host
  "The address (driver/summary-parts: \"10.0.0.7 #1\"; for another driver
   its name, \"LSPS\", with its settings, \"127.0.0.1:7266\", on a line of
   their own under it, the card's full width), and the On box at the right."
  [{:keys [id host-label address-lines enabled?]}]
  (let [[line detail] address-lines]
    {:fx/type :v-box
     :children (cond-> [{:fx/type :h-box
                         :spacing 4
                         :alignment :center-left
                         :children [{:fx/type :label
                                     :id (str "rig-card-address-" (id-text id))
                                     :text (or line host-label)
                                     :max-width (- card-width 56)
                                     :tooltip {:fx/type :tooltip :text (str host-label)}
                                     :style-class ["text-small" "label-secondary"]}
                                    {:fx/type :region :h-box/hgrow :always}
                                    {:fx/type :check-box
                                     :id (str "rig-card-enable-" (id-text id))
                                     :text "On"
                                     :min-width :use-pref-size
                                     :selected (boolean enabled?)
                                     :on-selected-changed {:event/type :projectors/set-enabled
                                                           :projector-id id
                                                           :enabled? :fx/event}}]}]
                 detail (conj {:fx/type :label
                               :id (str "rig-card-address-detail-" (id-text id))
                               :text detail
                               :max-width (- card-width 12)
                               :tooltip {:fx/type :tooltip :text (str host-label)}
                               :style-class ["text-small" "label-secondary"]}))}))

(def ^:private three-lines
  "The most a card's status or driver line takes (10 px text): longer is
   cut off with \"…\", its tooltip has all of it."
  43)

(defn- card-status
  "The dot and the status line (card-status-text): the output's while it
   streams (bold amber while the laser is below its minimum fps; amber while
   its device is in use by another program), \"Not found in the last scan\"
   (amber) when a scan this session didn't find it. It wraps to the card's
   width, up to three lines."
  [{:keys [fx/context card]}]
  (let [id (:id card)
        {:keys [state warning low? busy?] :as status} (fx/sub-ctx context subs/projector-output-status id)
        streaming-text (output-status-text status)
        {:keys [text tooltip]} (card-status-text status card)]
    {:fx/type :h-box
     :spacing 6
     :alignment :top-left
     :children (cond-> [{:fx/type :stack-pane
                         ;; one line's height, so the dot stays beside the first line
                         :min-height 14 :pref-height 14 :max-height 14
                         :children [{:fx/type status-indicator
                                     :id (str "projector-dot-" (id-text id))
                                     :status (case state
                                               :streaming :connected
                                               :starting :connecting
                                               :error :offline
                                               nil)}]}]
                 text (conj (cond-> {:fx/type :label
                                     :id (str "projector-status-" (id-text id))
                                     :text text
                                     :wrap-text true
                                     :max-width (- card-width 30)
                                     :max-height three-lines
                                     :tooltip {:fx/type :tooltip :text tooltip :wrap-text true :max-width 320}
                                     :style-class ["text-small" "label-secondary"]}
                              (= :error state) (assoc :style (str "-fx-text-fill: " (css/red) ";"))
                              (and (= :error state) busy?) (assoc :style (str "-fx-text-fill: " (css/orange) ";"))
                              (or (and (= :streaming state) warning) (nil? streaming-text))
                              (assoc :style (str "-fx-text-fill: " (css/orange) ";"))
                              (and (= :streaming state) low?)
                              (assoc :style (str "-fx-text-fill: " (css/orange) "; -fx-font-weight: bold;")))))}))

(defn- load-meter
  "The points the laser is asked for over the points that still reach its
   minimum (load-warning/load-percent): green, amber from 80%, red past
   100%, where the guard acts. Its tooltip says the numbers."
  [{:keys [id load pct]}]
  (let [track 46
        level (load-warning/meter-level pct)]
    {:fx/type :label
     :id (str "rig-card-load-" (id-text id))
     :content-display :graphic-only
     :padding 0
     :tooltip {:fx/type :tooltip :text (load-warning/meter-tooltip load)}
     :graphic {:fx/type :h-box
               :spacing 4
               :alignment :center-left
               :children [{:fx/type :stack-pane
                           :alignment :center-left
                           :min-width track :pref-width track :max-width track
                           :min-height 6 :pref-height 6 :max-height 6
                           :style-class ["load-meter-track"]
                           :children [{:fx/type :region
                                       :min-width 0
                                       :max-width (* track (min 1.0 (/ pct 100.0)))
                                       :pref-width (* track (min 1.0 (/ pct 100.0)))
                                       :style-class (cond-> ["load-meter"]
                                                      (= :near level) (conj "load-meter-near")
                                                      (= :over level) (conj "load-meter-over"))}]}
                          {:fx/type :label
                           :id (str "rig-card-load-pct-" (id-text id))
                           :text (str pct "%")
                           :min-width :use-pref-size
                           :style-class ["text-small" "label-secondary"]}]}}))

(defn- card-load
  "What the laser really sent, after calibration and safety (the
   thumbnail), and beside it its load meter and what the fit guard did
   (load-warning/load-note-text); while it isn't streaming, \"Not
   streaming\" and no meter (Q-Z3-2 A)."
  [{:keys [fx/context id limit]}]
  (let [{:keys [load] :as l} (fx/sub-ctx context subs/projector-load id)
        streaming? (= :streaming (:state (fx/sub-ctx context subs/projector-output-status id)))
        pct (when load (load-warning/load-percent load))
        note (load-warning/load-note-text l)]
    {:fx/type :v-box
     :spacing 2
     :children (cond-> [{:fx/type :h-box
                         :spacing 6
                         :alignment :top-left
                         :children [{:fx/type preview/output-thumbnail
                                     :projector-id id
                                     :limit limit
                                     :size thumbnail-size}
                                    {:fx/type :v-box
                                     :spacing 3
                                     :children (cond-> []
                                                 (not streaming?)
                                                 (conj {:fx/type :label
                                                        :id (str "rig-card-not-streaming-" (id-text id))
                                                        :text "Not streaming"
                                                        :style-class ["text-small" "label-hint"]})
                                                 pct (conj {:fx/type load-meter :id id :load load :pct pct}))}]}]
                 ;; One line under the thumbnail, there even when empty so the card
                 ;; keeps its height; the tooltip has all of it
                 true (conj (cond-> {:fx/type :label
                                     :id (str "rig-card-load-note-" (id-text id))
                                     :text (or note "")
                                     :min-height 13
                                     :pref-height 13
                                     :max-width (- card-width 12)
                                     :style "-fx-font-size: 10;"
                                     :style-class ["label-secondary"]}
                              (seq note) (assoc :tooltip {:fx/type :tooltip :text note}))))}))

(defn- card-driver-info
  "What the output driver says of itself (its :status :info, output
   drivers 1D), as one compact line (subs/projector-driver-info), e.g. the
   scan rate it really runs at; nothing when it says nothing."
  [{:keys [fx/context id]}]
  (let [text (fx/sub-ctx context subs/projector-driver-info id)]
    (if text
      {:fx/type :label
       :id (str "projector-driver-info-" (id-text id))
       :text text
       :wrap-text true
       :max-width (- card-width 12)
       :max-height three-lines
       :tooltip {:fx/type :tooltip :text text :wrap-text true :max-width 320}
       :style "-fx-font-size: 10;"
       :style-class ["label-secondary"]}
      {:fx/type :region :max-height 0 :managed false})))

(defn- card-sections
  "A card, top to bottom: its sections (nil ones are left out)."
  [card]
  [(card-title card)
   (card-host card)
   {:fx/type card-status :card card}
   {:fx/type card-driver-info :id (:id card)}
   {:fx/type card-load :id (:id card) :limit (:limit card)}])


;; A card's menu


(defn- card-menu-items
  "Identify (Z1's event; off while the laser isn't outputting, as its button
   is; not there for one that can't be reached, an IDN laser with no host),
   on a device with more outputs, turning them all on, and on a card
   that is off, Forget this laser… (zones plan ZR: New Project keeps the
   rig, so a laser that's gone is forgotten here)."
  [{:keys [id host enabled? multi-output? identifiable?]} outputting?]
  (cond-> []
    identifiable? (conj {:fx/type :menu-item
                         :id (str "rig-card-identify-" (id-text id))
                         :text "Identify"
                         :disable (not (and outputting? enabled?))
                         :on-action {:event/type :projectors/identify :projector-id id}})
    multi-output? (conj {:fx/type :menu-item
                         :id (str "rig-card-enable-all-" (id-text id))
                         :text (str "Turn on every output of " host)
                         :on-action {:event/type :projectors/enable-all-by-ip :address host}})
    (not enabled?) (conj {:fx/type :menu-item
                          :id (str "rig-card-forget-" (id-text id))
                          :text "Forget this laser…"
                          :on-action {:event/type :rig/request-forget :projector-id id}})))


;; Dragging a card


(defn- drag-payload? [payload]
  (keyword? (:projector-id payload)))

(defn- side-of
  "Which half of the card under a drag the mouse is on."
  [^DragEvent e]
  (let [^Node card (.getSource e)]
    (if (< (.getX e) (/ (.getWidth (.getLayoutBounds card)) 2)) :before :after)))

(defn- focus-strip!
  "Give the strip the keyboard focus, so ← → and Alt+← / Alt+→ reach it."
  [^Node node]
  (loop [^Node n node]
    (when n
      (if (= "rig-strip" (.getId n))
        (.requestFocus n)
        (recur (.getParent n))))))

(defn- rig-card
  "One card, with a bar on the side a dragged card would land on, and its
   menu; amber (.rig-card-low) while its laser is below its minimum fps."
  [{:keys [fx/context card drop-mark outputting?]}]
  (let [{:keys [id selected? enabled?]} card
        low? (:low? (fx/sub-ctx context subs/projector-load id))
        side (when (= id (:projector-id drop-mark)) (:side drop-mark))
        bar (fn [s] {:fx/type :region
                     :id (str "rig-drop-bar-" (name s) "-" (id-text id))
                     :min-width 3 :pref-width 3 :max-width 3
                     :visible (= side s)
                     :style (str "-fx-background-color: " (css/blue) "; -fx-background-radius: 2;")})]
    {:fx/type :label
     :content-display :graphic-only
     :padding 0
     ;; Not for text (it has none): so the grid asks for the card's height at
     ;; its width, and a status line that wraps gets its lines
     :wrap-text true
     :context-menu {:fx/type :context-menu
                    :items (card-menu-items card outputting?)}
     :graphic {:fx/type :h-box
               :spacing 2
               :children [(bar :before)
                          {:fx/type :v-box
                           :id (str "rig-card-" (id-text id))
                           :style-class (cond-> ["rig-card"] low? (conj "rig-card-low"))
                           :min-width card-width
                           :pref-width card-width
                           :max-width card-width
                           :spacing 2
                           :padding 6
                           :opacity (if enabled? 1.0 0.55)
                           ;; while low, the border is .rig-card-low's
                           :style (str "-fx-background-color: #2D2D2D; -fx-background-radius: 6; -fx-border-radius: 6; -fx-cursor: hand; "
                                       (cond
                                         low? ""
                                         selected? (str "-fx-border-color: " (css/selection-bg) "; -fx-border-width: 2;")
                                         :else "-fx-border-color: #3D3D3D; -fx-border-width: 1;"))
                           :on-mouse-clicked (fn [^MouseEvent e]
                                               (when (= MouseButton/PRIMARY (.getButton e))
                                                 (focus-strip! (.getSource e))
                                                 (events/dispatch! {:event/type :projectors/select-projector
                                                                    :projector-id id})))
                           :on-drag-detected (fn [^MouseEvent e]
                                               (dnd/start-drag! (.getSource e) :rig-card {:projector-id id} [TransferMode/MOVE])
                                               (.consume e))
                           :on-drag-done (fn [_] (dnd/end-drag! :rig-card))
                           :on-drag-over (fn [^DragEvent e]
                                           (when (dnd/has-payload? (.getDragboard e) :rig-card)
                                             (.acceptTransferModes e (into-array TransferMode [TransferMode/MOVE]))
                                             (let [s (side-of e)]
                                               (when-not (= {:projector-id id :side s} drop-mark)
                                                 (events/dispatch! {:event/type :rig/drag-over :projector-id id :side s}))))
                                           (.consume e))
                           :on-drag-exited (fn [_] (events/dispatch! {:event/type :rig/drag-exit}))
                           :on-drag-dropped (fn [^DragEvent e]
                                              (if-let [{from :projector-id} (dnd/read-payload (.getDragboard e) :rig-card drag-payload?)]
                                                (do (events/dispatch! {:event/type :rig/move-projector
                                                                       :projector-id from :target id :side (side-of e)})
                                                    (.setDropCompleted e true))
                                                (.setDropCompleted e false))
                                              (.consume e))
                           :children (filterv some? (card-sections card))}
                          (bar :after)]}}))


;; The keys


(defn neighbour
  "The card next to selected, delta (-1 or 1) away in ids, or nil at an end;
   with nothing selected, the first card (→) or the last (←)."
  [ids selected delta]
  (let [i (.indexOf ^java.util.List ids selected)]
    (cond
      (empty? ids) nil
      (neg? i) (if (neg? delta) (peek ids) (first ids))
      :else (get ids (+ i (if (neg? delta) -1 1))))))

(defn- key-handler
  "← → move the selection; Alt+← / Alt+→ move the selected card."
  [ids selected]
  (fn [^KeyEvent e]
    (let [code (.getCode e)]
      (when (#{KeyCode/LEFT KeyCode/RIGHT} code)
        (let [delta (if (= KeyCode/LEFT code) -1 1)]
          (if (.isAltDown e)
            (when selected
              (events/dispatch! {:event/type :rig/nudge-projector :projector-id selected :delta delta}))
            (when-let [next-id (neighbour ids selected delta)]
              (events/dispatch! {:event/type :projectors/select-projector :projector-id next-id}))))
        (.consume e)))))


;; The strip


(defn- move-button [{:keys [id text tooltip disable selected delta column]}]
  {:fx/type :button
   :id id
   :text text
   :disable disable
   :tooltip {:fx/type :tooltip :text tooltip}
   :style-class ["button-secondary"]
   :grid-pane/column column
   :grid-pane/row 0
   :grid-pane/valignment :center
   :on-action {:event/type :rig/nudge-projector :projector-id selected :delta delta}})

(defn- cards-height
  "About the height of the cards' row, n cards with their thumbnails (the
   status row a line high, 6 px more than its dot, so the dot stays beside
   the status's first line), a line more when a card has its driver's
   settings under its address, and room for the horizontal scroll bar
   (8 px, hovered) under more than 6 of them, about what fits at 1200 px:
   the strip is never shorter, so the divider above the inspector can't
   cut a card."
  [n detail-line?]
  (+ 72 6 (+ thumbnail-size 2) 14 (if detail-line? 14 0) (if (> n 6) 8 0)))

(defn- strip-height
  "About the height the cards and rows need (the cards' row, a row each,
   + Group): the strip is never taller, so the divider stops below + Group."
  [rows n detail-line?]
  (+ (cards-height n detail-line?) (* 17 rows) 38))

(defn- fit-to-cards!
  "Once pane's grid is laid out, its min height is what its cards' row
   really takes, and its max what all its rows do (cards-height and
   strip-height are only the first guess): a status or driver line that
   wraps (up to three lines) makes a card taller than the guess, and the
   divider above the inspector must not cut it."
  [^ScrollPane pane]
  (let [fit! (fn []
               (when-let [^GridPane grid (.getContent pane)]
                 (let [insets (.getInsets pane)
                       row (transduce (comp (filter #(.isManaged ^Node %))
                                            (filter #(zero? (or (GridPane/getRowIndex %) 0)))
                                            (map (fn [^Node c] (.prefHeight c (.prefWidth c -1)))))
                                      max 0.0 (.getChildren grid))
                       grid-insets (.getInsets grid)
                       hbar (if (> (.prefWidth grid -1) (.getWidth (.getViewportBounds pane))) 8.0 0.0)
                       around (+ (.getTop insets) (.getBottom insets) hbar)
                       lo (Math/ceil (+ row (.getTop grid-insets) (.getBottom grid-insets) around))
                       hi (max lo (Math/ceil (+ (.prefHeight grid -1) around)))]
                   (when (or (not= lo (.getMinHeight pane)) (not= hi (.getMaxHeight pane)))
                     (.setMinHeight pane lo)
                     (.setMaxHeight pane hi)))))
        ;; after the layout pass, which the split pane above takes the sizes in
        on-layout (reify ChangeListener (changed [_ _ _ _] (Platform/runLater fit!)))]
    (when-let [^Region grid (.getContent pane)]
      (.addListener (.layoutBoundsProperty grid) on-layout))
    (.addListener (.contentProperty pane)
                  (reify ChangeListener
                    (changed [_ _ old new]
                      (some-> ^Region old .layoutBoundsProperty (.removeListener on-layout))
                      (some-> ^Region new .layoutBoundsProperty (.addListener on-layout)))))))

(defn rig-strip
  "The cards between ◀ and ▶, and under them the group rows
   (group-matrix/rows), in one scroll pane, so more than about 8 lasers
   scroll together. It sits above the inspector with a divider between
   them (views/tabs/projectors.clj), which can make it no shorter than its
   cards and no taller than its rows."
  [{:keys [fx/context]}]
  (let [cards (fx/sub-ctx context subs/rig-cards)
        matrix (fx/sub-ctx context subs/rig-matrix)
        drop-mark (fx/sub-val context get-in [:rig-ui :drop])
        outputting? (boolean (fx/sub-val context get-in [:backend :streaming :running?]))
        ids (mapv :id cards)
        selected (some #(when (:selected? %) (:id %)) cards)
        i (.indexOf ^java.util.List ids selected)
        n (count ids)
        detail-line? (boolean (some (comp second :address-lines) cards))]
    {:fx/type fx/ext-on-instance-lifecycle
     :on-created fit-to-cards!
     :desc
     {:fx/type :scroll-pane
      :id "rig-strip-scroll"
      :vbar-policy :as-needed
      :hbar-policy :as-needed
      :min-height (cards-height n detail-line?)
      :max-height (strip-height (count (:rows matrix)) n detail-line?)
      :style-class ["scroll-pane-dark"]
      :content {:fx/type :grid-pane
                :id "rig-strip"
                :focus-traversable true
                :hgap 4
                :vgap 0
                :padding 4
                :on-key-pressed (key-handler ids selected)
                :children (-> [(move-button {:id "rig-move-left-button" :text "◀" :tooltip "Move left (Alt+←)"
                                             :disable (not (pos? i)) :selected selected :delta -1 :column 0})]
                              (into (map-indexed (fn [k card]
                                                   {:fx/type rig-card
                                                    :fx/key (:id card)
                                                    :grid-pane/column (inc k)
                                                    :grid-pane/row 0
                                                    :grid-pane/valignment :top
                                                    :card card
                                                    :drop-mark drop-mark
                                                    :outputting? outputting?}))
                                    cards)
                              (conj (move-button {:id "rig-move-right-button" :text "▶" :tooltip "Move right (Alt+→)"
                                                  :disable (not (and (>= i 0) (< i (dec n)))) :selected selected :delta 1
                                                  :column (inc n)}))
                              (into (group-matrix/rows matrix cards)))}}}))
