(ns laser-show.views.components.step-grid
  "The step grid window's parts (zones plan Z6, plans/2026-11-19-zones/08
   §4, §7): the grid body, one pane that takes the mouse itself (so a drag
   paints across cells, :step-grid/pointer) with a region per cell,
   #step-cell-<row>-<step>, and the beat and bar lines under them; the row
   labels (each row's zone and where it goes, with its menu), the step
   numbers (with theirs) and + Row; the toolbar: length, Rate, Step on,
   Restart on (while Step on is the beat), Offset, Start from, Copy, Paste,
   Clear; the playhead over the cells and the window's Now line, drawn from
   the show clock's newest tick (clock-view/ext-clock-redraw on the
   snapshot's :zone-steps), never from app state. What a key does on the
   grid is key-event, a pure function."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.animation.show-time :as show-time]
            [laser-show.events.core :as events]
            [laser-show.routing.step-grid :as step-grid]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.subs :as subs]
            [laser-show.views.components.clock-view :as clock-view]
            [laser-show.views.components.commit-field :as commit-field]
            [laser-show.views.components.parameter-controls :as param-controls]
            [laser-show.views.components.shortcuts :as shortcuts]
            [laser-show.views.components.target-control :as target-control]
            [laser-show.views.components.zone-pattern-editor :as zone-pattern-editor])
  (:import [javafx.application Platform]
           [javafx.scene Node]
           [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.scene.control Labeled]
           [javafx.scene.input KeyCode KeyEvent MouseEvent]
           [javafx.scene.paint Color]))

(def cell-w "A cell's width, px: 32 steps fit the window." 22.0)
(def cell-h "A cell's height, px." 26.0)
(def ^:private label-w 150.0)
(def ^:private numbers-h 18.0)


;; The keys (08 §7)

(defn key-event
  "The event a key pressed on the grid at target sends, or nil: the arrows
   move the cursor, Shift+← and Shift+→ rotate every row a step earlier or
   later, Space and Enter toggle the cursor's cell, Delete and Backspace
   clear its step, Home and End go to the first step and the last, ⌘C and
   ⌘V (Ctrl+C, Ctrl+V elsewhere: shortcut?) copy the grid and paste one.
   Undo and redo are the window's (views/dialogs/step_grid_editor.clj)."
  [code {:keys [shift? shortcut?]} target]
  (let [ev (fn [type & {:as more}] (merge {:event/type type :target target} more))]
    (cond
      shortcut? (condp = code
                  KeyCode/C (ev :step-grid/copy)
                  KeyCode/V (ev :step-grid/paste)
                  nil)
      (and shift? (= KeyCode/LEFT code)) (ev :step-grid/rotate :by -1)
      (and shift? (= KeyCode/RIGHT code)) (ev :step-grid/rotate :by 1)
      :else (condp = code
              KeyCode/LEFT (ev :step-grid/cursor :move [0 -1])
              KeyCode/RIGHT (ev :step-grid/cursor :move [0 1])
              KeyCode/UP (ev :step-grid/cursor :move [-1 0])
              KeyCode/DOWN (ev :step-grid/cursor :move [1 0])
              KeyCode/SPACE (ev :step-grid/toggle)
              KeyCode/ENTER (ev :step-grid/toggle)
              KeyCode/DELETE (ev :step-grid/clear-step)
              KeyCode/BACK_SPACE (ev :step-grid/clear-step)
              KeyCode/HOME (ev :step-grid/cursor :to :first)
              KeyCode/END (ev :step-grid/cursor :to :last)
              nil))))

(defn- on-key
  "The grid's key handler: the key's event (key-event), the key consumed so
   Space presses no button and the arrows move no focus."
  [target]
  (fn [^KeyEvent e]
    (when-let [event (key-event (.getCode e) {:shift? (.isShiftDown e) :shortcut? (.isShortcutDown e)} target)]
      (.consume e)
      (events/dispatch! event))))


;; Beats and bars (S-6)

(defn beat-lines
  "The steps a line goes before: {step :beat or :bar}, a beat every rate
   steps and a bar every 4 × rate (Z4's SQ-8), counted from the offset, while
   the grid steps on the beat at a whole number of steps a beat; none
   otherwise, and none before the first step."
  [{:keys [rate offset step-on]} len]
  (let [rate (when (number? rate) (double rate))
        offset (if (number? offset) (double offset) 0.0)]
    (if (and rate (pos? rate) (== rate (Math/rint rate)) (== offset (Math/rint offset))
             (= :clock (or step-on :clock)))
      (let [r (long rate)
            o (long offset)]
        (into {} (keep (fn [k]
                         (let [d (- k o)]
                           (cond
                             (zero? (mod d (* 4 r))) [k :bar]
                             (zero? (mod d r)) [k :beat]))))
              (range 1 len)))
      {})))


;; The grid body

(defn- cell
  [row step lit? cursor?]
  {:fx/type :region
   :fx/key [:cell row step]
   :id (str "step-cell-" row "-" step)
   :layout-x (* step cell-w)
   :layout-y (* row cell-h)
   :min-width cell-w :pref-width cell-w :max-width cell-w
   :min-height cell-h :pref-height cell-h :max-height cell-h
   :style-class (cond-> ["step-cell"]
                  lit? (conj "step-cell-on")
                  cursor? (conj "step-cell-cursor"))})

(defn- line [step kind height]
  {:fx/type :region
   :fx/key [:line step]
   :layout-x (- (* step cell-w) 1.0)
   :layout-y 0.0
   :min-width 2.0 :pref-width 2.0 :max-width 2.0
   :min-height height :pref-height height :max-height height
   :mouse-transparent true
   :style-class [(if (= :bar kind) "step-grid-bar-line" "step-grid-beat-line")]})

(defn grid-body
  "The cells, rows × length, in one pane that takes the mouse and the keys
   itself (#step-grid-body). Props: :target :grid (subs/step-grid)."
  [{:keys [target grid]}]
  (let [{:keys [rows lit length cursor params]} grid
        n (count rows)
        w (* length cell-w)
        h (* n cell-h)
        pointer {:event/type :step-grid/pointer :target target :cell-w cell-w :cell-h cell-h}
        [cr cs] cursor]
    {:fx/type fx/ext-on-instance-lifecycle
     ;; The grid has the keys when the window opens
     :on-created (fn [^Node node] (Platform/runLater #(.requestFocus node)))
     :desc
     {:fx/type :pane
     :id "step-grid-body"
     :focus-traversable true
     :min-width w :pref-width w :max-width w
     :min-height h :pref-height h :max-height h
     :style-class ["step-grid-body"]
     :on-mouse-pressed (fn [^MouseEvent e]
                         ;; Consumed: the scroll pane around the grid would
                         ;; take the focus the grid's keys need
                         (.requestFocus ^Node (.getSource e))
                         (.consume e)
                         (events/dispatch! (assoc pointer :fx/event e)))
     :on-mouse-dragged pointer
     :on-mouse-released pointer
     :on-key-pressed (on-key target)
     :children (into (mapv (fn [[k kind]] (line k kind h)) (sort (beat-lines params length)))
                     (for [r (range n)
                           k (range length)]
                       (cell r k (contains? (nth lit k) (:ref (nth rows r))) (and (= r cr) (= k cs)))))}}))


;; The playhead (08 §7, invariant 7): from the snapshot only

(defn- playing-step
  "The step the pattern id is on in the newest tick, while it is fresh;
   nil while it doesn't play (its cue stopped, its cell off)."
  [snapshot id]
  (when (and snapshot (not (show-time/stale? snapshot (System/nanoTime))))
    (get-in snapshot [:zone-steps id])))

(defn now-text
  "The window's Now line: \"Step 5 of 8\" for step (mod length), or \"Not
   playing\"."
  [step length]
  (if (and (number? step) (pos? (long length)))
    (str "Step " (inc (mod (long step) (long length))) " of " length)
    "Not playing"))

(def ^:private playhead-fill (Color/web "#4C9AFF" 0.35))
(def ^:private playhead-edge (Color/web "#FFFFFF" 0.9))

(defn redraw-playhead!
  "The playhead from the show clock's newest tick: the column of the step
   the pattern is on, step mod L; nothing while it doesn't play."
  [^Canvas canvas snapshot {:keys [id length]}]
  (let [^GraphicsContext gc (.getGraphicsContext2D canvas)
        w (.getWidth canvas)
        h (.getHeight canvas)
        step (playing-step snapshot id)]
    (.clearRect gc 0 0 w h)
    (when (and (number? step) (pos? (long length)))
      (let [x (* (double (mod (long step) (long length))) cell-w)]
        (.setFill gc playhead-fill)
        (.fillRect gc x 0 cell-w h)
        (.setStroke gc playhead-edge)
        (.setLineWidth gc 2.0)
        (.strokeRect gc (+ x 1.0) 1.0 (- cell-w 2.0) (- h 2.0))))))

(defn playhead
  "A canvas over the cells (#step-grid-playhead) that the mouse goes
   through. Props: :grid (subs/step-grid)."
  [{:keys [grid]}]
  (let [{:keys [id length rows]} grid]
    {:fx/type clock-view/ext-clock-redraw
     :redraw! #'redraw-playhead!
     :desc {:fx/type :canvas
            :id "step-grid-playhead"
            :width (* length cell-w)
            :height (max 1.0 (* (count rows) cell-h))
            :mouse-transparent true
            :style-class ["step-playhead"]
            :user-data {:id id :length length}}}))

(defn redraw-now!
  "The Now line from the newest tick (now-text)."
  [^Labeled node snapshot {:keys [id length]}]
  (let [text (now-text (playing-step snapshot id) length)]
    (when (not= text (.getText node))
      (.setText node text))))

(defn now-line
  "\"Step 5 of 8\" while the pattern plays, \"Not playing\" while it
   doesn't (#step-grid-now). Props: :grid."
  [{:keys [grid]}]
  (let [{:keys [id length]} grid]
    {:fx/type clock-view/ext-clock-redraw
     :redraw! #'redraw-now!
     :desc {:fx/type :label
            :id "step-grid-now"
            :text (now-text nil length)
            :min-width :use-pref-size
            :user-data {:id id :length length}
            :style-class ["label" "zone-pattern-now"]}}))


;; The rows and the step numbers

(defn- ref-items
  "The Target menu's :pattern rows (Every laser left out: it can't be a
   row), a row already in the grid ticked and off, a click on another sending
   (event ref)."
  [menu-rows rows event]
  (into []
        (keep (fn [{:keys [kind text checked? target]}]
                (case kind
                  :header {:fx/type :menu-item :text text :disable true :style-class ["menu-item" "target-menu-header"]}
                  :separator {:fx/type :separator-menu-item}
                  (:ref :zone :group) (when-not (= :lane/lasers target)
                                        {:fx/type :menu-item
                                         :text (str (if checked? "✓ " "   ") text)
                                         :disable (boolean (some #{target} rows))
                                         :on-action (event target)})
                  nil)))
        menu-rows))

(defn- row-menu
  "A row's menu (08 §7): Change ▸, Move up, Move down, Fill every step,
   Every 2nd, Every 4th, Clear row, Remove row."
  [target ref menu-rows rows]
  (let [ev (fn [type & {:as more}] (merge {:event/type type :target target :row ref} more))]
    {:fx/type :context-menu
     :items [{:fx/type :menu
              :text "Change"
              :items (ref-items menu-rows rows #(ev :step-grid/set-row :ref %))}
             {:fx/type :separator-menu-item}
             {:fx/type :menu-item :text "Move up" :on-action (ev :step-grid/move-row :delta -1)}
             {:fx/type :menu-item :text "Move down" :on-action (ev :step-grid/move-row :delta 1)}
             {:fx/type :separator-menu-item}
             {:fx/type :menu-item :text "Fill every step" :on-action (ev :step-grid/fill-row :every 1)}
             {:fx/type :menu-item :text "Every 2nd" :on-action (ev :step-grid/fill-row :every 2)}
             {:fx/type :menu-item :text "Every 4th" :on-action (ev :step-grid/fill-row :every 4)}
             {:fx/type :menu-item :text "Clear row" :on-action (ev :step-grid/clear-row)}
             {:fx/type :separator-menu-item}
             {:fx/type :menu-item :text "Remove row" :on-action (ev :step-grid/remove-row)}]}))

(defn row-labels
  "Each row's label, its zone and where it goes (\"A → Left\"), with its
   menu, and + Row under them: a click adds the next zone letter, its menu
   any zone, group or laser. Props: :target :grid."
  [{:keys [fx/context target grid]}]
  (let [rows (mapv :ref (:rows grid))
        menu-rows (target-control/menu-model (assoc (target-control/menu-context context) :scope :pattern :lane rows))]
    {:fx/type :v-box
     :min-width label-w :pref-width label-w :max-width label-w
     :children (-> [{:fx/type :region :min-height numbers-h :pref-height numbers-h :max-height numbers-h}]
                   (into (map-indexed (fn [i {:keys [ref text]}]
                                        {:fx/type :label
                                         :id (str "step-row-" i)
                                         :text text
                                         :min-height cell-h :pref-height cell-h :max-height cell-h
                                         :max-width label-w
                                         :style-class ["label" "step-row-label"]
                                         :tooltip {:fx/type :tooltip :text (str text ". Right-click for the row's menu")}
                                         :context-menu (row-menu target ref menu-rows rows)}))
                         (:rows grid))
                   (conj {:fx/type :split-menu-button
                          :id "step-grid-add-row"
                          :text "+ Row"
                          :focus-traversable false
                          :style-class ["split-menu-button" "step-grid-add-row"]
                          :tooltip {:fx/type :tooltip :text "Adds a row for the next zone letter; its menu, any zone, group or laser"}
                          :on-action {:event/type :step-grid/add-row :target target}
                          :items (ref-items menu-rows rows (fn [ref] {:event/type :step-grid/add-row :target target :ref ref}))}))}))

(defn step-numbers
  "The steps' numbers over the cells, a bar's first in bold; each one's menu:
   Clear step, Light every row. Props: :target :grid."
  [{:keys [target grid]}]
  (let [{:keys [length params]} grid
        lines (beat-lines params length)]
    {:fx/type :h-box
     :min-height numbers-h :pref-height numbers-h :max-height numbers-h
     :children (vec (for [k (range length)]
                      {:fx/type :label
                       :fx/key k
                       :id (str "step-number-" k)
                       :text (str (inc k))
                       :alignment :center
                       :min-width cell-w :pref-width cell-w :max-width cell-w
                       :style-class (cond-> ["label" "step-number"]
                                      (or (zero? k) (= :bar (get lines k))) (conj "step-number-bar"))
                       :context-menu {:fx/type :context-menu
                                      :items [{:fx/type :menu-item :text "Clear step"
                                               :on-action {:event/type :step-grid/clear-step :target target :step k}}
                                              {:fx/type :menu-item :text "Light every row"
                                               :on-action {:event/type :step-grid/fill-step :target target :step k}}]}}))}))


;; The toolbar

(def ^:private start-names
  {:alternate "Alternate" :chase "Chase" :ping-pong "Ping-pong" :random "Random" :center-out "Center-out" :all-on "All on"})

(defn start-tooltip
  "What a Start from button does, on a grid of len steps: \"Replaces the
   grid with the first 8 steps of Chase over these rows\"."
  [style len]
  (if (= :all-on style)
    "Lights every row on every step"
    (str "Replaces the grid with the first " len (if (= 1 len) " step" " steps") " of " (start-names style) " over these rows")))

(defn- small-button [id text tooltip on-action & {:keys [selected? disable]}]
  (cond-> {:fx/type :button
           :id id
           :text text
           :focus-traversable false
           :min-width :use-pref-size
           :style-class (cond-> ["button" "step-grid-button"] selected? (conj "step-grid-button-selected"))
           :tooltip {:fx/type :tooltip :text tooltip}
           :on-action on-action}
    (some? disable) (assoc :disable disable)))

(defn- caption [text]
  {:fx/type :label :text text :min-width :use-pref-size :style-class ["label-secondary"]})

(defn toolbar
  "Length (8, 16, 32 and any from 1 to 64), Rate, Step on, Restart on while
   Step on is the beat, Offset (Z5's controls, ∿ to follow a modulator),
   Start from, Copy, Paste and Clear. Props: :fx/context :target :grid."
  [{:keys [fx/context target grid]}]
  (let [{:keys [length params]} grid
        can-paste? (fx/sub-ctx context subs/can-paste? :step-grid)
        keys (select-keys target [:domain :entity-key :effect-path])
        props {:current-params params
               :params-map (param-controls/params-vector->map zone-patterns/pattern-parameters)
               :on-change-event (assoc keys :event/type :chain/update-param)
               :on-text-event (assoc keys :event/type :chain/update-param-from-text)
               :modulator-event-base keys}
        ev (fn [type & {:as more}] (merge {:event/type type :target target} more))]
    {:fx/type :v-box
     :spacing 8
     :children
     (vec
      (concat
       [{:fx/type :h-box
         :spacing 6
         :alignment :center-left
         :children [(caption "Steps")
                    (small-button "step-grid-length-8" "8" "8 steps" (ev :step-grid/set-length :n 8) :selected? (= 8 length))
                    (small-button "step-grid-length-16" "16" "16 steps" (ev :step-grid/set-length :n 16) :selected? (= 16 length))
                    (small-button "step-grid-length-32" "32" "32 steps" (ev :step-grid/set-length :n 32) :selected? (= 32 length))
                    {:fx/type commit-field/commit-field
                     :id "step-grid-length"
                     :text (str length)
                     :pref-width 48
                     :on-commit (fn [typed]
                                  (when-let [n (parse-long (str/trim (str typed)))]
                                    (ev :step-grid/set-length :n n)))}]}
        {:fx/type :h-box
         :spacing 16
         :alignment :top-left
         :children [(zone-pattern-editor/control props :rate)
                    {:fx/type :label :text (zone-pattern-editor/rate-words params) :min-height 26
                     :min-width :use-pref-size :style-class ["label" "label-hint" "zone-pattern-hint"]}
                    (zone-pattern-editor/control props :step-on)]}
        {:fx/type :h-box
         :spacing 16
         :alignment :top-left
         :children (cond-> [(zone-pattern-editor/control props :offset)]
                     (= :clock (or (:step-on params) :clock)) (conj (zone-pattern-editor/control props :restart-on)))}
        {:fx/type :h-box
         :spacing 6
         :alignment :center-left
         :children (-> [(caption "Start from")]
                       (into (for [style step-grid/start-styles]
                               (small-button (str "step-grid-start-" (name style)) (start-names style)
                                             (start-tooltip style length) (ev :step-grid/start-from :style style))))
                       (into [{:fx/type :region :h-box/hgrow :always :min-width 12}
                              (small-button "step-grid-copy" "Copy" (str "Copy this grid (" (shortcuts/shortcut-text :c) ")")
                                            (ev :step-grid/copy))
                              (small-button "step-grid-paste" "Paste" (str "Paste the grid you copied (" (shortcuts/shortcut-text :v) ")")
                                            (ev :step-grid/paste) :disable (not can-paste?))
                              (small-button "step-grid-clear" "Clear" "Clears every step: the rows and the length stay"
                                            (ev :step-grid/clear))]))}]))}))

(defn moves-and-pads
  "Moves (Z5's three radio buttons) and Follows the pattern pads (Z6-Q2 A:
   a grid doesn't, until it is ticked). Props: :target :grid."
  [{:keys [target grid]}]
  (let [keys (select-keys target [:domain :entity-key :effect-path])
        on-change (assoc keys :event/type :chain/update-param)
        params (:params grid)]
    {:fx/type :v-box
     :spacing 8
     :children [{:fx/type zone-pattern-editor/moves-radios
                 :current-params params
                 :params-map (param-controls/params-vector->map zone-patterns/pattern-parameters)
                 :on-change-event on-change}
                {:fx/type :check-box
                 :id "step-grid-follow-pads"
                 :text "Follows the pattern pads"
                 :focus-traversable false
                 :selected (true? (:follow-pads? params))
                 :on-selected-changed (assoc on-change :param-key :follow-pads?)}]}))

(defn grid-view
  "The row labels and the grid (its numbers over its cells, the playhead
   over them), side by side; past 32 steps the grid scrolls sideways.
   Props: :target :grid."
  [{:keys [target grid]}]
  {:fx/type :h-box
   :spacing 6
   :children [{:fx/type row-labels :target target :grid grid}
              {:fx/type :scroll-pane
               :id "step-grid-scroll"
               :h-box/hgrow :always
               :focus-traversable false
               :fit-to-height true
               :vbar-policy :never
               :hbar-policy :as-needed
               :style-class ["scroll-pane-base" "step-grid-scroll"]
               :content {:fx/type :v-box
                         :children [{:fx/type step-numbers :target target :grid grid}
                                    {:fx/type :stack-pane
                                     :alignment :top-left
                                     :children [{:fx/type grid-body :target target :grid grid}
                                                {:fx/type playhead :grid grid}]}]}}]})
