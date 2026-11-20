(ns laser-show.views.components.rig-strip
  "The rig strip on the Projectors tab (plans/2026-11-19-zones/04 §7): one
   card per projector output, left to right in rig order (routing.rig), each
   with its place in E, its name and address, its output status and its On
   box, and a menu (Identify, turn on every output of its device).

   Reordering: drag a card onto another (its left half puts it before, its
   right half after, and a bar shows where), or select it and press ◀ ▶ or
   Alt+← / Alt+→; ← and → alone move the selection. The group rows line up
   under the cards in the same scroll pane (views/components/group_matrix).

   A card is built from a vector of sections; Z2's thumbnail section is
   empty, and Z3 fills it."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.css.core :as css]
            [laser-show.events.core :as events]
            [laser-show.subs :as subs]
            [laser-show.views.components.drag-drop-cell :as dnd]
            [laser-show.views.components.group-matrix :as group-matrix])
  (:import [javafx.scene Node]
           [javafx.scene.input DragEvent KeyCode KeyEvent MouseButton MouseEvent TransferMode]))


(def card-width
  "Every card's width, so the group rows under the cards line up with them."
  136)

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
   (a calibration effect skipped because it fails) comes last. A safety
   error (zones plan Z1) is shown as it is: \"Safety error: output off\"."
  [{:keys [state kind fps scan-limited? error warning]}]
  (case state
    :streaming (cond-> (cond
                         (nil? fps) "Streaming"
                         scan-limited? (format "Streaming · %.1f fps (limited by scan rate)" (double fps))
                         :else (format "Streaming · %.1f fps" (double fps)))
                 warning (str " · " warning))
    :starting "Starting…"
    :error (if (= :safety kind) error (str "Error: " error))
    nil))


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
               :text card-name
               :max-width (- card-width 40)
               :style-class ["label-bold"]}]})

(defn- card-host
  "The address, and the On box at the right."
  [{:keys [id host-label enabled?]}]
  {:fx/type :h-box
   :spacing 4
   :alignment :center-left
   :children [{:fx/type :label
               :text host-label
               :style-class ["text-small" "label-secondary"]}
              {:fx/type :region :h-box/hgrow :always}
              {:fx/type :check-box
               :id (str "rig-card-enable-" (id-text id))
               :text "On"
               :min-width :use-pref-size
               :selected (boolean enabled?)
               :on-selected-changed {:event/type :projectors/set-enabled
                                     :projector-id id
                                     :enabled? :fx/event}}]})

(defn- card-status
  "The dot and the status line: the output's while it streams, \"Not found
   in the last scan\" (amber) when a scan this session didn't find it."
  [{:keys [fx/context id found?]}]
  (let [{:keys [state warning] :as status} (fx/sub-ctx context subs/projector-output-status id)
        streaming-text (output-status-text status)
        text (or streaming-text (when-not found? "Not found in the last scan"))]
    {:fx/type :h-box
     :spacing 6
     :alignment :center-left
     :children (cond-> [{:fx/type status-indicator
                         :id (str "projector-dot-" (id-text id))
                         :status (case state
                                   :streaming :connected
                                   :starting :connecting
                                   :error :offline
                                   nil)}]
                 text (conj (cond-> {:fx/type :label
                                     :id (str "projector-status-" (id-text id))
                                     :text text
                                     :wrap-text true
                                     :max-width (- card-width 30)
                                     :style-class ["text-small" "label-secondary"]}
                              (:detail status) (assoc :tooltip {:fx/type :tooltip :text (:detail status)})
                              (= :error state) (assoc :style (str "-fx-text-fill: " (css/red) ";"))
                              (or (and (= :streaming state) warning) (nil? streaming-text))
                              (assoc :style (str "-fx-text-fill: " (css/orange) ";")))))}))

(defn- card-thumbnail
  "What the laser draws. Empty in Z2; Z3 fills it."
  [_card]
  nil)

(defn- card-sections
  "A card, top to bottom: its sections (nil ones are left out)."
  [card]
  [(card-title card)
   (card-host card)
   {:fx/type card-status :id (:id card) :found? (:found? card)}
   (card-thumbnail card)])


;; A card's menu


(defn- card-menu-items
  "Identify (Z1's event; off while the laser isn't outputting, as its button
   is), on a device with more outputs, turning them all on, and on a card
   that is off, Forget this laser… (zones plan ZR: New Project keeps the
   rig, so a laser that's gone is forgotten here)."
  [{:keys [id host enabled? multi-output?]} outputting?]
  (cond-> [{:fx/type :menu-item
            :id (str "rig-card-identify-" (id-text id))
            :text "Identify"
            :disable (not (and outputting? enabled? (not (str/blank? (str host)))))
            :on-action {:event/type :projectors/identify :projector-id id}}]
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
   menu."
  [{:keys [card drop-mark outputting?]}]
  (let [{:keys [id selected? enabled?]} card
        side (when (= id (:projector-id drop-mark)) (:side drop-mark))
        bar (fn [s] {:fx/type :region
                     :id (str "rig-drop-bar-" (name s) "-" (id-text id))
                     :min-width 3 :pref-width 3 :max-width 3
                     :visible (= side s)
                     :style (str "-fx-background-color: " (css/blue) "; -fx-background-radius: 2;")})]
    {:fx/type :label
     :content-display :graphic-only
     :padding 0
     :context-menu {:fx/type :context-menu
                    :items (card-menu-items card outputting?)}
     :graphic {:fx/type :h-box
               :spacing 2
               :children [(bar :before)
                          {:fx/type :v-box
                           :id (str "rig-card-" (id-text id))
                           :style-class ["rig-card"]
                           :min-width card-width
                           :pref-width card-width
                           :max-width card-width
                           :spacing 2
                           :padding 6
                           :opacity (if enabled? 1.0 0.55)
                           :style (str "-fx-background-color: #2D2D2D; -fx-background-radius: 6; -fx-border-radius: 6; -fx-cursor: hand; "
                                       (if selected?
                                         (str "-fx-border-color: " (css/selection-bg) "; -fx-border-width: 2;")
                                         "-fx-border-color: #3D3D3D; -fx-border-width: 1;"))
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

(defn- strip-height
  "About the height the cards and rows need (a card, a row each, + Group):
   the strip takes it, up to a limit past which it scrolls, and the
   inspector below gets the rest."
  [rows]
  (min 400 (+ 72 (* 17 rows) 38)))

(defn rig-strip
  "The cards between ◀ and ▶, and under them the group rows
   (group-matrix/rows), in one scroll pane, so more than about 8 lasers
   scroll together."
  [{:keys [fx/context]}]
  (let [cards (fx/sub-ctx context subs/rig-cards)
        matrix (fx/sub-ctx context subs/rig-matrix)
        drop-mark (fx/sub-val context get-in [:rig-ui :drop])
        outputting? (boolean (fx/sub-val context get-in [:backend :streaming :running?]))
        ids (mapv :id cards)
        selected (some #(when (:selected? %) (:id %)) cards)
        i (.indexOf ^java.util.List ids selected)
        n (count ids)]
    {:fx/type :scroll-pane
     :id "rig-strip-scroll"
     :vbar-policy :as-needed
     :hbar-policy :as-needed
     :min-height (strip-height (count (:rows matrix)))
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
                                                   :card card
                                                   :drop-mark drop-mark
                                                   :outputting? outputting?}))
                                   cards)
                             (conj (move-button {:id "rig-move-right-button" :text "▶" :tooltip "Move right (Alt+→)"
                                                 :disable (not (and (>= i 0) (< i (dec n)))) :selected selected :delta 1
                                                 :column (inc n)}))
                             (into (group-matrix/rows matrix cards)))}}))
