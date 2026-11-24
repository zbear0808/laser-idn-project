(ns laser-show.views.components.zone-pattern-editor
  "Zone patterns in the editors (zones plan Z5, 07 §4.8, §7.1, §7.2, §7.4):
   the Pattern chip in both cue editors' footers (pattern-field), a zone
   pattern's visual editor (pattern-visual: the rows its style uses, Now,
   and how it folds onto this rig) and a Map cycle's (map-cycle-visual).
   The effect parameter editor shows the two for their :zone-pattern and
   :map-cycle renderers, on a cue's pattern (the :cue-zone-patterns domain,
   the pattern window) and in an effects cell. A step grid (zones plan Z6)
   shows a small read-only grid and Edit grid…; any other pattern Step
   grid…, and the chip's menu has it too: the grid's own window
   (views/dialogs/step_grid_editor.clj).

   What they say is worked out by pure functions (chip-text, chip-tooltip,
   cell-tooltip, rows, fold-text, now-text), tested without a window."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.animation.modulator-registry :as reg]
            [laser-show.animation.show-time :as show-time]
            [laser-show.events.core :as events]
            [laser-show.routing.rig :as rig]
            [laser-show.routing.step-grid :as step-grid]
            [laser-show.routing.targets :as targets]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.routing.zone-remap :as zone-remap]
            [laser-show.subs :as subs]
            [laser-show.views.components.clock-view :as clock-view]
            [laser-show.views.components.modulator-param-control :as mod-param]
            [laser-show.views.components.parameter-controls :as param-controls])
  (:import [javafx.event ActionEvent]
           [javafx.scene.control Labeled RadioButton]))


;; What a pattern says of itself (07 §7.1, §7.2)

(defn- enabled
  "pattern, unless it is switched off (a disabled one is none)."
  [pattern]
  (when (and (map? pattern) (not (false? (:enabled? pattern)))) pattern))

(defn- style-of
  "params' style; nil while a modulator picks it."
  [params]
  (let [s (:style params)] (when (keyword? s) s)))

(defn- lane-refs [params]
  (vec (distinct (filter keyword? (:zones params)))))

(defn- step-source
  "What params step on: :clock (the beat), or a press's source."
  [params]
  (let [s (:step-on params)] (if (keyword? s) s :clock)))

(defn- number-text [x]
  (let [x (double x)]
    (if (== x (Math/rint x))
      (str (long x))
      (str/replace (format "%.2f" x) #"0$" ""))))

(def ^:private press-words
  {:hit "HIT" :accent "ACCENT" :flash "FLASH" :vibe "vibe change" :any "HIT, ACCENT or FLASH"
   :cue "cue start" :zone-step "Zone step key press"})

(defn- press-word [source]
  (get press-words source (some-> source name)))

(defn- style-name [style]
  (get zone-patterns/style-names style "Modulated"))

(defn- lane-words
  "Where a pattern moves things, as its tooltips say it: across every
   laser, Alternate's and Swap's pairs (\"A ⇄ B\"), else along the lane
   (\"along A → B → C\")."
  [style refs names]
  (cond
    (= [:lane/lasers] refs) "across every laser"
    (empty? refs) "in no zones"
    (#{:alternate :swap} style) (zone-patterns/lane-text style refs names)
    :else (str "along " (zone-patterns/lane-text style refs names))))

(defn- from-words [names from]
  (cond
    (= :any from) "everything the cue draws"
    (and (keyword? from) (not= :all from)) (str "the " (targets/ref-name names from) " picture")
    :else "the All picture"))

(defn- moves-words
  "What a pattern does with what it finds: Shift moves what's in the zones,
   Swap trades it, the other styles move a picture there or take turns with
   what's already in them (:source :zones)."
  [style params names]
  (case style
    :shift "Moves what's in the zones"
    :swap "Trades what's in"
    (if (= :zones (:source params))
      "Takes turns with what's in the zones"
      (str "Moves " (from-words names (:from params))))))

(defn- steps-text [a]
  (str (number-text a) (if (== 1.0 (double a)) " step" " steps")))

(defn rate-words
  "How fast a pattern steps, as its tooltips say it: \"1 step a beat\", \"2
   steps a beat\", \"1 step every 2 beats\", \"1 step on each HIT\",
   \"standing still\"; a backwards one says so."
  [params]
  (let [rate (:rate params)]
    (if-not (number? rate)
      "at a rate a modulator sets"
      (let [r (double rate)
            a (Math/abs r)
            back (when (neg? r) ", backwards")
            source (step-source params)]
        (cond
          (zero? a) "standing still"
          (not= :clock source) (str (steps-text a) " on each " (press-word source) back)
          (and (< a 1.0) (== (/ 1.0 a) (Math/rint (/ 1.0 a)))) (str "1 step every " (number-text (/ 1.0 a)) " beats" back)
          :else (str (steps-text a) " a beat" back))))))

(defn- clock-words
  "When a pattern on the beat counts from, and what starts it again."
  [params]
  (when (= :clock (step-source params))
    (str (if (= :cue (:clock params)) ", from the cue's start" ", on the show's beats")
         (let [r (:restart-on params)]
           (when (and (keyword? r) (not= :own r))
             (str ", starting again at each " (press-word r)))))))

(defn chip-text
  "What the Pattern chip says: \"None\", or the style and its lane,
   \"Alternate · A ⇄ B\"; a step grid its length and rows, \"Steps · 8 · A
   B\" (zones plan Z6)."
  [pattern names]
  (if-let [{:keys [params]} (enabled pattern)]
    (let [style (style-of params)]
      (if (= :steps style)
        (zone-patterns/describe params names)
        (str (style-name style) " · " (zone-patterns/lane-text style (:zones params) names))))
    "None"))

(defn- grid-words
  "Where a step grid lights things: \"on 8 steps over A B\"."
  [params names]
  (str "on " (count (zone-remap/grid-steps params)) " steps over " (zone-patterns/lane-text :steps (:zones params) names)))

(defn chip-tooltip
  "The Pattern chip's tooltip (07 §7.4's words): what the pattern does, how
   fast, and from when; with none, what picking a style does."
  [pattern names]
  (if-let [{:keys [params]} (enabled pattern)]
    (let [style (style-of params)]
      (str "Zone pattern: " (style-name style) ". "
           (moves-words style params names) " "
           (if (= :steps style) (grid-words params names) (lane-words style (lane-refs params) names))
           ", " (rate-words params) (clock-words params) "."))
    "No zone pattern: the cue's pictures stay in the zones they're drawn into. Pick a style to move them on the beat or on a press."))

(defn cell-tooltip
  "An effects cell's line for a zone pattern (07 §7.2): \"Zone pattern:
   Chase across every laser, 2 steps a beat (show beats)\"."
  [params names]
  (let [style (style-of params)]
    (str "Zone pattern: " (style-name style) " "
         (if (= :steps style) (grid-words params names) (lane-words style (lane-refs params) names))
         ", " (rate-words params)
         (when (= :clock (step-source params))
           (if (= :cue (:clock params)) " (each cue from its start)" " (show beats)")))))

(def ^:private style-lanes
  "Each style by its preset, as the chip's menu says it (07 §7.1)."
  {:alternate "A ⇄ B" :chase "across every laser" :ping-pong "across every laser"
   :random "across every laser" :shift "A → B → C → D" :swap "A ⇄ B (what's in them)"})


;; The rows a style uses (07 §7.1)

(defn rows
  "The rows the pattern window shows for style (nil while a modulator picks
   it: every row) stepping on step-on: Moves for the styles that light
   places, Count from and Restart on while it steps on the beat, Path for
   Chase, Width for Chase, Ping-pong and Random, Seed for Random. A step
   grid (zones plan Z6) shows its small grid in place of Zones: its rows
   are the grid's, edited in its window."
  [style step-on]
  (let [any? (not (some #{style} zone-patterns/styles))
        lit? (or any? (contains? zone-remap/lit-styles style))]
    (vec (concat [:style :follow-pads?]
                 (when-not (= :steps style) [:zones])
                 (when (or any? (= :steps style)) [:steps])
                 (when lit? [:source])
                 [:rate :step-on]
                 (when (= :clock step-on) [:clock :restart-on])
                 [:offset]
                 (when (or any? (= :chase style)) [:path])
                 (when (or any? (#{:chase :ping-pong :random} style)) [:width])
                 (when (or any? (= :random style)) [:seed])))))

(defn lane-mode
  "How the Zones row picks the lane: Swap's two places (:pair), else a list."
  [style]
  (if (= :swap style) :pair :list))

(defn cycle-rows
  "A Map cycle's rows (07 §7.4)."
  []
  [:maps :rate :path :step-on :offset])


;; How it folds onto this rig (07 §8.5)

(def ^{:arglists '([state params names])
       :doc "routing.zone-patterns/fold-text: how a pattern folds onto state's rig under
   the map in use, the pattern window's ⓘ line."}
  fold-text
  zone-patterns/fold-text)

(defn- known? [state ref]
  (or (targets/zone-ref? ref) (rig/known-ref? state ref)))


;; Now (07 §7.1): from the snapshot

(defn- now-places [style params l step names]
  (let [say #(if (targets/zone-ref? %) (targets/zone-letter %) (targets/ref-name names %))]
    (cond
      (= :off style) "as drawn (the pads say Off)"
      (contains? zone-remap/lit-styles style)
      (let [lit (zone-remap/lit-set style params l step)]
        (if (seq lit) (str/join " " (map say (filter lit l))) "nothing"))
      (contains? zone-remap/permute-styles style)
      (let [perm (zone-remap/permutation style l step)]
        (cond
          (empty? perm) "as drawn"
          (= :swap style) (str (say (nth l 0)) " ⇄ " (say (nth l 1)))
          :else (str (say (first l)) " → " (say (get perm (first l))))))
      :else "–")))

(defn now-text
  "What the pattern shows at step (the snapshot's :zone-steps), style the
   one it plays (the pads' live style or its own), l its lane on this rig:
   \"B (step 5)\", \"A ⇄ B (step 1)\"; \"– (not playing)\" without a step."
  [style params l step names]
  (if (number? step)
    (str (now-places style params l (long step) names) " (step " step ")")
    "– (not playing)"))

(defn redraw-now!
  "The Now line from the show clock's newest tick: the pattern's step there,
   while the tick is fresh."
  [^Labeled node snapshot {:keys [id style params lane names]}]
  (let [step (when (and snapshot (not (show-time/stale? snapshot (System/nanoTime))))
               (get-in snapshot [:zone-steps id]))
        text (now-text style params lane step names)]
    (when (not= text (.getText node))
      (.setText node text))))


;; The rows

(def ^:private label-width 90)

(defn control
  "The standard control of param k, with ∿ where modulators are on (not in
   keyframe mode); the step grid window's toolbar has them too (zones plan
   Z6)."
  [{:keys [current-params params-map on-change-event on-text-event modulator-event-base]} k]
  {:fx/type (if modulator-event-base mod-param/modulatable-param-control param-controls/param-control)
   :param-key k
   :param-spec (get params-map k)
   :current-value (get current-params k)
   :on-change-event on-change-event
   :on-text-event on-text-event
   :modulator-event-base modulator-event-base
   :label-width label-width})

(defn- row
  "A row of the window: its control, then what follows it, level with the
   control's first line (a modulator's settings open below it)."
  [k & children]
  {:fx/type :h-box
   :id (str "zone-pattern-row-" (name k))
   :spacing 10
   :alignment :top-left
   :children (vec (remove nil? children))})

(def ^:private line-height
  "A control's first line: a combo box's height."
  26)

(defn- hint [text]
  {:fx/type :label :text text :min-width :use-pref-size :min-height line-height
   :style-class ["label" "label-hint" "zone-pattern-hint"]})

(defn- grid-target
  "The step grid window's target for the pattern a renderer edits
   (effect-keys: {:domain :entity-key :effect-instance-id}, the instance
   whose params it shows; its place and a keyframe's index left out)."
  [effect-keys]
  (select-keys effect-keys [:domain :entity-key :effect-instance-id]))

(def ^:private mini-cell 9.0)

(defn mini-grid
  "A step grid, small and read-only (zones plan Z6): a cell a row and a
   step, #zone-pattern-mini-cell-<row>-<step>, lit where the step lights
   the row."
  [{:keys [params]}]
  (let [{:keys [zones steps]} (step-grid/normalize params)
        w (* (count steps) mini-cell)
        h (* (count zones) mini-cell)]
    {:fx/type :pane
     :id "zone-pattern-mini-grid"
     :min-width w :pref-width w :max-width w
     :min-height h :pref-height h :max-height h
     :children (vec (for [[r ref] (map-indexed vector zones)
                          [k step] (map-indexed vector steps)]
                      {:fx/type :region
                       :id (str "zone-pattern-mini-cell-" r "-" k)
                       :layout-x (* k mini-cell)
                       :layout-y (* r mini-cell)
                       :min-width mini-cell :pref-width mini-cell :max-width mini-cell
                       :min-height mini-cell :pref-height mini-cell :max-height mini-cell
                       :style-class (cond-> ["step-cell" "step-cell-mini"] (some #{ref} step) (conj "step-cell-on"))}))}))

(defn- mini-grid-row
  "A step grid's row (zones plan Z6): its small grid, its rows and length
   in words, and Edit grid… (its own window, Z6-Q5 A)."
  [params effect-keys names]
  (let [n (count (zone-remap/grid-steps params))]
    (row :steps
         {:fx/type :label :text "Grid" :pref-width label-width :min-height line-height :style-class ["label-secondary"]}
         {:fx/type :v-box
          :spacing 4
          :children [{:fx/type mini-grid :params params}
                     {:fx/type :label
                      :id "zone-pattern-grid-text"
                      :text (if (pos? n)
                              (str n (if (= 1 n) " step" " steps") " over " (zone-patterns/lane-text :steps (:zones params) names))
                              "No steps yet")
                      :style-class ["label" "label-hint" "zone-pattern-hint"]}]}
         {:fx/type :button
          :id "zone-pattern-step-grid"
          :text "Edit grid…"
          :focus-traversable false
          :style-class ["button-secondary"]
          :tooltip {:fx/type :tooltip :text "Draw this pattern on its grid, in its own window"}
          :on-action {:event/type :step-grid/open :target (grid-target effect-keys)}})))

(defn- step-grid-row
  "Step grid…: the pattern's grid in its own window (zones plan Z6,
   Z6-Q5 A); a pattern of another style becomes one, its first 8 steps."
  [effect-keys]
  (row :step-grid
       {:fx/type :label :text "Grid" :pref-width label-width :min-height line-height :style-class ["label-secondary"]}
       {:fx/type :button
        :id "zone-pattern-step-grid"
        :text "Step grid…"
        :focus-traversable false
        :style-class ["button-secondary"]
        :tooltip {:fx/type :tooltip
                  :text "Draw this pattern on a grid, a row per zone and a column per step: a pattern of another style becomes its first 8 steps"}
        :on-action {:event/type :step-grid/open :target (grid-target effect-keys)}}))

(defn moves-radios
  "Moves, as three radio buttons (07 §7.1); a click on the one already
   chosen keeps it."
  [{:keys [current-params params-map on-change-event]}]
  (let [spec (get params-map :source)
        v (if (keyword? (:source current-params)) (:source current-params) (:default spec))]
    {:fx/type :h-box
     :spacing 10
     :alignment :center-left
     :children (into [{:fx/type :label :text (:label spec "Moves") :pref-width label-width :style-class ["label-secondary"]}]
                     (for [c (:choices spec)]
                       (cond-> {:fx/type :radio-button
                                :id (str "zone-pattern-source-" (name c))
                                :text (param-controls/choice-text spec c)
                                :selected (= c v)
                                :focus-traversable false
                                :on-action (fn [^ActionEvent e]
                                             (if (= c v)
                                               (.setSelected ^RadioButton (.getSource e) true)
                                               (events/dispatch! (assoc on-change-event :param-key :source :value c))))}
                         (= :auto c) (assoc :tooltip {:fx/type :tooltip
                                                      :text "Automatic: the All picture if the cue draws into All, else what's in the zones"}))))}))

(defn pattern-visual
  "A zone pattern's visual editor (the :zone-pattern renderer, 07 §7.1):
   the rows its style uses (rows); Style, Rate and Offset take a modulator
   (∿); Now, from the newest tick; how it folds onto this rig; what it
   should know (zone-patterns/warnings). Props: the custom renderer's
   :current-params :params-map :on-change-event :on-text-event
   :modulator-event-base, and :effect-keys {:domain :entity-key
   :effect-path :effect-instance-id}."
  [{:keys [fx/context current-params effect-keys] :as props}]
  (let [params current-params
        style (style-of params)
        shown (set (rows style (step-source params)))
        names (fx/sub-ctx context subs/names-ctx)
        world (fx/sub-ctx context subs/pattern-world)
        effect (fx/sub-ctx context subs/zone-pattern-at (:domain effect-keys) (:entity-key effect-keys) (:effect-path effect-keys))
        live (fx/sub-val context get-in [:pattern-pads :style])
        e (rig/e-order (rig/rig-inputs world))
        l (zone-remap/lane (:zones params) #(known? world %) e)
        playing (zone-remap/effective-style params live)
        warnings (zone-patterns/warnings world params names)
        show? #(contains? shown %)]
    {:fx/type :v-box
     :id "zone-pattern-visual"
     :spacing 8
     :children
     (filterv
      some?
      (concat
       [(row :style
             (control props :style)
             {:fx/type :check-box
              :id "zone-pattern-follow-pads"
              :text "Follows the pattern pads"
              :min-height line-height
              :focus-traversable false
              :selected (not (false? (:follow-pads? params)))
              :on-selected-changed (assoc (:on-change-event props) :param-key :follow-pads?)})
        (when (show? :zones)
          (row :zones
               (assoc (control props :zones)
                      :fx/type param-controls/param-ref-list
                      :pair? (= :pair (lane-mode style)))))
        (if (show? :steps)
          (mini-grid-row params effect-keys names)
          (step-grid-row effect-keys))]
       (when (show? :source)
         [(row :source (if (reg/modulated? (:source params)) (control props :source) (moves-radios props)))])
       [(row :rate (control props :rate) (hint (rate-words params)))
        (row :step-on (control props :step-on))]
       (when (show? :clock)
         [(row :clock (control props :clock))
          (row :restart-on (control props :restart-on))])
       [(row :offset (control props :offset))]
       (when (show? :path) [(row :path (control props :path))])
       (when (show? :width) [(row :width (control props :width))])
       (when (show? :seed) [(row :seed (control props :seed))])
       [(row :now
             {:fx/type :label :text "Now" :pref-width label-width :min-height line-height :style-class ["label-secondary"]}
             {:fx/type clock-view/ext-clock-redraw
              :redraw! #'redraw-now!
              :desc {:fx/type :label
                     :id "zone-pattern-now"
                     :min-height line-height
                     :text (now-text playing params l nil names)
                     :user-data {:id (:id effect) :style playing :params params :lane l :names names}
                     :style-class ["label" "zone-pattern-now"]}})
        {:fx/type :label
         :id "zone-pattern-fold"
         :text (fx/sub-ctx context subs/pattern-fold params)
         :wrap-text true
         :graphic {:fx/type :label :text "ⓘ" :style-class ["label" "zone-pattern-fold-icon"]}
         :style-class ["label" "zone-pattern-fold"]}]
       (for [{:keys [level text]} warnings]
         {:fx/type :label
          :text text
          :wrap-text true
          :style-class ["label" "zone-pattern-warning" (str "zone-pattern-warning-" (name level))]})))}))


;; The Map cycle (07 §7.4)

(def ^:private every-rates
  "The Map cycle's Every choices: a map each 1, 2 beats, 1, 2, 4 bars."
  [1.0 0.5 0.25 0.125 0.0625])

(defn- every-text [step-on rate]
  (let [n (/ 1.0 (double rate))]
    (if (= :clock step-on)
      (cond
        (== n 1.0) "1 beat"
        (< n 4.0) (str (number-text n) " beats")
        (== n 4.0) "1 bar"
        :else (str (number-text (/ n 4.0)) " bars"))
      (if (== n 1.0) "1 press" (str (number-text n) " presses")))))

(defn map-cycle-visual
  "A Map cycle's visual editor (the :map-cycle renderer): Maps, Every (a
   map for each beat, bar or press), Path, Step on, Offset, and what it
   does in a line (zone-patterns/describe-cycle)."
  [{:keys [fx/context current-params params-map on-change-event] :as props}]
  (let [params current-params
        rate (:rate params)
        source (step-source params)
        zone-maps (fx/sub-val context :zone-maps)]
    {:fx/type :v-box
     :id "map-cycle-visual"
     :spacing 8
     :children
     [(row :maps (control props :maps))
      (if (and (number? rate) (pos? (double rate)))
        (row :rate
             {:fx/type :label :text (:label (:rate params-map) "Every") :pref-width label-width :min-height line-height
              :style-class ["label-secondary"]}
             {:fx/type :combo-box
              :id "map-cycle-every"
              :items (vec (distinct (conj every-rates (double rate))))
              :value (double rate)
              :pref-width 150
              :button-cell (fn [r] {:text (if (number? r) (every-text source r) "")})
              :cell-factory {:fx/cell-type :list-cell :describe (fn [r] {:text (if (number? r) (every-text source r) "")})}
              :on-value-changed (assoc on-change-event :param-key :rate)})
        (row :rate (control props :rate)))
      (row :path (control props :path))
      (row :step-on (control props :step-on))
      (row :offset (control props :offset))
      {:fx/type :label
       :id "map-cycle-description"
       :text (zone-patterns/describe-cycle params {:zone-maps zone-maps})
       :wrap-text true
       :style-class ["label" "zone-pattern-fold"]}]}))


;; The Pattern chip (07 §7.1)

(defn pattern-field
  "\"Pattern:\" and the chip, after a cue editor's Target: None or the
   cue's pattern; its menu None, the styles (on a cue with a pattern a style
   changes the style only), Edit pattern…. Props: :col :row :id (the
   chip's)."
  [{:keys [fx/context col row id]}]
  (let [pattern (fx/sub-ctx context subs/cue-zone-pattern col row)
        names (fx/sub-ctx context subs/names-ctx)
        on (enabled pattern)
        current (when on (style-of (:params on)))
        check (fn [checked? text] (str (if checked? "✓ " "   ") text))
        set-style (fn [style] {:event/type :zone-patterns/set-cue-pattern :col col :row row :style style})]
    {:fx/type :h-box
     :spacing 6
     :alignment :center-left
     :children [{:fx/type :label :text "Pattern:" :min-width :use-pref-size :style-class ["label" "target-field-label"]}
                {:fx/type :menu-button
                 :id id
                 :text (chip-text pattern names)
                 :focus-traversable false
                 :min-width :use-pref-size
                 :style-class (cond-> ["menu-button" "target-chip" "pattern-chip"] (nil? on) (conj "pattern-chip-none"))
                 :tooltip {:fx/type :tooltip :text (chip-tooltip pattern names)}
                 :items (into [{:fx/type :menu-item :text (check (nil? on) "None") :on-action (set-style nil)}]
                              (concat
                               (for [s zone-patterns/rule-styles]
                                 {:fx/type :menu-item
                                  :text (check (= s current) (str (style-name s) "   " (style-lanes s)))
                                  :on-action (set-style s)})
                               [{:fx/type :separator-menu-item}
                                {:fx/type :menu-item
                                 :text "Step grid…"
                                 ;; The pattern by its id, even while it is off; with none, the cue
                                 ;; asks for its first
                                 :on-action (if pattern
                                              {:event/type :step-grid/open
                                               :target {:domain :cue-zone-patterns :entity-key [col row]
                                                        :effect-instance-id (:id pattern)}}
                                              {:event/type :step-grid/open
                                               :target {:domain :cue-zone-patterns :entity-key [col row]}
                                               :create-first? true})}
                                {:fx/type :menu-item
                                 :text "Edit pattern…"
                                 :disable (nil? pattern)
                                 :on-action {:event/type :ui/open-dialog :dialog-id :zone-pattern-editor :data {:col col :row row}}}]))}]}))
