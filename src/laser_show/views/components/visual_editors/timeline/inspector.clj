(ns laser-show.views.components.visual-editors.timeline.inspector
  "Timeline inspector: the properties column at the left of the bottom detail
   panel. Shows the selected clip, clips, track or folder, or the timeline
   when nothing is selected; their effects sit next to it (device-chain).
   See plans/2026-11-08-timeline-rework/05-visual-design.md §3.8."
  (:require [cljfx.api :as fx]
            [laser-show.animation.chains :as chains]
            [laser-show.animation.presets :as presets]
            [laser-show.common.util :as u]
            [laser-show.events.core :as events]
            [laser-show.routing.zone-effects :as ze]
            [laser-show.subs :as subs]
            [laser-show.timeline.model :as model]
            [laser-show.timeline.timecode :as tc])
  (:import [javafx.scene.control TextField]))


(defn- apply-op! [col row op]
  (events/dispatch! {:event/type :timeline/apply-op :col col :row row :op op}))

(defn- title [text] {:fx/type :label :id "timeline-inspector-title" :text text :style-class "tl-inspector-title"})
(defn- field-label [text] {:fx/type :label :text text :style-class "tl-field-label"})

(defn- zone-name [zone-groups zone-id]
  (or (get-in zone-groups [zone-id :name]) (some-> zone-id name) "All"))

(defn- zone-combo
  "Zone combo: an inherit option labelled inherit-label, then every zone."
  [{:keys [zone-groups current inherit-label on-change]}]
  (let [options (into [{:id :inherit :name inherit-label}]
                      (map (fn [[id zg]] {:id id :name (:name zg (name id))}))
                      (sort-by (comp :name val) zone-groups))]
    {:fx/type :combo-box
     :items options
     :value (or (u/seek #(= (:id %) (or current :inherit)) options) (first options))
     :max-width Double/MAX_VALUE
     :button-cell (fn [opt] {:text (:name opt)})
     :cell-factory {:fx/cell-type :list-cell :describe (fn [opt] {:text (:name opt)})}
     :on-value-changed (fn [opt]
                         (when opt
                           (let [zone (when-not (= :inherit (:id opt)) (:id opt))]
                             (when (not= zone current) (on-change zone)))))}))

(defn- beats-field
  "Text field in beats; Enter commits (on-commit beats)."
  [{:keys [id value on-commit]}]
  (let [commit! (fn [^TextField tf]
                  (when-let [v (parse-double (.getText tf))]
                    (when (not= v (double value)) (on-commit v))))]
    {:fx/type :text-field
     :id id
     :text (format "%.2f" (double (or value 0.0)))
     :pref-width 80
     :on-action (fn [e] (commit! (.getSource e)))}))

(defn- leaf-tracks [tracks]
  (filterv model/leaf-track? (tree-seq model/track-group? #(:items % []) {:type :group :items tracks})))

(defn selection-target
  "What the detail panel and the effect browser act on:
   {:kind :clip :entity clip :path p}     one selected clip (p within :items)
   {:kind :clips :ids ids}                several clips
   {:kind :track :entity track :path p}   the sidebar's track or folder (p within :tracks)
   {:kind :timeline}                      nothing selected"
  [cue selection selected-track-id]
  (let [items (vec (:items cue))
        tracks (vec (:tracks cue))
        found (fn [kind coll id]
                (if-let [path (chains/find-path-by-id coll id)]
                  {:kind kind :entity (get-in coll path) :path path}
                  {:kind :timeline}))]
    (cond
      (= 1 (count selection)) (found :clip items (first selection))
      (> (count selection) 1) {:kind :clips :ids selection}
      selected-track-id (found :track tracks selected-track-id)
      :else {:kind :timeline})))

(defn clip-name [clip]
  (or (:name clip) (:name (presets/presets-by-id (:preset-id clip))) "Clip"))

(defn target-name
  "Short label for a selection-target."
  [{:keys [kind entity ids]}]
  (case kind
    :clip (clip-name entity)
    :clips (str (count ids) " clips")
    :track (:name entity (if (model/track-group? entity) "Folder" "Track"))
    "Timeline"))

(defn- clip-section
  [{:keys [col row tracks zone-groups]} clip]
  (let [track (model/find-track tracks (:track-id clip))
        track-zone (ze/resolve-track-effective-zone tracks (:track-id clip) :all)
        leaves (leaf-tracks tracks)]
    [(title "CLIP")
     {:fx/type :label
      :text (clip-name clip)
      :style-class "tl-inspector-name"}
     {:fx/type :grid-pane
      :hgap 8 :vgap 6
      :children [(assoc (field-label "Start") :grid-pane/row 0 :grid-pane/column 0)
                 (assoc (beats-field {:id "timeline-clip-start"
                                      :value (model/clip-start clip)
                                      :on-commit (fn [v]
                                                   (apply-op! col row {:op :move-clips :clip-ids #{(:id clip)}
                                                                       :delta (- v (model/clip-start clip))
                                                                       :snap? false}))})
                        :grid-pane/row 0 :grid-pane/column 1)
                 (assoc (field-label "Length") :grid-pane/row 1 :grid-pane/column 0)
                 (assoc (beats-field {:id "timeline-clip-length"
                                      :value (model/clip-length clip)
                                      :on-commit (fn [v]
                                                   (apply-op! col row {:op :trim-end :clip-id (:id clip)
                                                                       :to (+ (model/clip-start clip) v)
                                                                       :snap? false}))})
                        :grid-pane/row 1 :grid-pane/column 1)]}
     (field-label "Track")
     {:fx/type :combo-box
      :items leaves
      :value (or (u/seek #(= (:id %) (:id track)) leaves) track)
      :max-width Double/MAX_VALUE
      :button-cell (fn [t] {:text (:name t "Track")})
      :cell-factory {:fx/cell-type :list-cell :describe (fn [t] {:text (:name t "Track")})}
      :on-value-changed (fn [t]
                          (when (and t (not= (:id t) (:track-id clip)))
                            (apply-op! col row {:op :set-clip-track :clip-id (:id clip) :track-id (:id t)})))}
     (field-label "Zone")
     (zone-combo {:zone-groups zone-groups
                  :current (:zone-group-id clip)
                  :inherit-label (str "Inherit from Track (" (zone-name zone-groups track-zone) ")")
                  :on-change #(apply-op! col row {:op :set-clip-zone :id (:id clip) :zone-group-id %})})]))

(defn- clips-section [{:keys [col row]} clip-ids]
  [(title "CLIPS")
   {:fx/type :label :text (str (count clip-ids) " clips selected") :style-class "tl-inspector-name"}
   {:fx/type :button
    :text "Delete"
    :style-class "button-secondary"
    :on-action {:event/type :timeline/delete-selection :col col :row row}}])

(defn- track-section [{:keys [col row tracks zone-groups destination-zone-id]} track]
  (let [folder? (model/track-group? track)
        parent (model/parent-track tracks (:id track))
        default-zone (or destination-zone-id :all)
        parent-zone (when parent (ze/resolve-track-effective-zone tracks (:id parent) default-zone))]
    (cond-> [(title (if folder? "FOLDER PROPERTIES" "TRACK PROPERTIES"))
             {:fx/type :label :text (:name track (if folder? "Folder" "Track")) :style-class "tl-inspector-name"}
             (field-label "Zone")
             (zone-combo {:zone-groups zone-groups
                          :current (:zone-group-id track)
                          :inherit-label (if parent
                                           (str "Inherit from Folder (" (zone-name zone-groups parent-zone) ")")
                                           (str "Cue Default Zone (" (zone-name zone-groups default-zone) ")"))
                          :on-change #(apply-op! col row {:op :set-track-zone :id (:id track) :zone-group-id %})})
             {:fx/type :check-box
              :text "Muted"
              :selected (true? (:muted? track))
              :on-selected-changed (fn [m] (apply-op! col row {:op :set-track-muted :track-id (:id track) :muted? m}))}]
      (not folder?)
      (into [(field-label "Colour")
             {:fx/type :flow-pane
              :hgap 6 :vgap 6
              :children (mapv (fn [hex]
                                {:fx/type :button
                                 :style-class (cond-> ["tl-swatch"] (= hex (:color track)) (conj "tl-swatch-selected"))
                                 :style (str "-fx-background-color: " hex ";")
                                 :on-action (fn [_] (apply-op! col row {:op :set-track-color :track-id (:id track) :color hex}))})
                              model/track-palette)}]))))

(defn- text-commit-field
  "Text field committing (on-commit text) on Enter."
  [{:keys [id text on-commit pref-width]}]
  {:fx/type :text-field
   :id id
   :text text
   :pref-width (or pref-width 110)
   :on-action (fn [e] (on-commit (.getText ^TextField (.getSource e))))})

(defn- fps-name [f] (if (tc/drop-frame? f) "29.97 DF" (str f)))

(defn- mtc-rate-warning
  "Warns when incoming MTC runs at a different rate than the timeline is set
   to. Subscribes to the MTC status itself, so the rest of the inspector
   doesn't re-render as timecode runs."
  [{:keys [fx/context fps]}]
  (let [incoming (:fps (fx/sub-ctx context subs/mtc-status))
        mismatch? (and incoming (not (== (double incoming) (double fps))))]
    {:fx/type :label
     :id "timeline-mtc-rate-warning"
     :style-class "tl-warning"
     :wrap-text true
     :visible (boolean mismatch?)
     :managed (boolean mismatch?)
     :text (if mismatch?
             (str "Incoming " (fps-name incoming) " fps, timeline set to " (fps-name fps) " fps")
             "")}))

(defn- chase-toggle [col row chase]
  {:fx/type :h-box
   :children [{:fx/type :toggle-button
               :id "timeline-chase-off"
               :text "Off"
               :style-class ["toggle-button" "tl-toggle" "tl-segment"]
               :focus-traversable false
               :selected (nil? chase)
               :on-action (fn [_] (apply-op! col row {:op :set-chase :source nil}))}
              {:fx/type :toggle-button
               :id "timeline-chase-mtc"
               :text "MTC"
               :style-class ["toggle-button" "tl-toggle" "tl-segment"]
               :focus-traversable false
               :selected (= :mtc chase)
               :tooltip {:fx/type :tooltip
                         :text (str "Follow MIDI timecode from the connected MIDI inputs. Beat 0 sits at "
                                    "'Starts at'; the timeline plays while timecode runs inside it.")}
               :on-action (fn [_] (apply-op! col row {:op :set-chase :source :mtc}))}]})

(defn- timeline-section [{:keys [col row cue global-bpm]}]
  (let [{:keys [enabled? start duration] :or {start 0.0 duration 4.0}} (:loop cue)
        clips (:items cue)
        explicit (:timeline/length cue)
        length (model/effective-length cue)
        locked (:timeline/tempo-bpm cue)
        fps (tc/cue-fps cue)
        tc-start (:timeline/tc-start cue 0.0)
        chase (:timeline/chase cue)
        cell (fn [r c node] (assoc node :grid-pane/row r :grid-pane/column c))]
    [(title "TIMELINE")
     {:fx/type :label
      :text (str (count clips) " clips · "
                 (if enabled? "loops" (str "stops at beat " (format "%.2f" (double length)))))
      :style-class "tl-field-value"}
     {:fx/type :grid-pane
      :hgap 8 :vgap 6
      :children [(cell 0 0 (field-label "Length"))
                 (cell 0 1 (beats-field {:id "timeline-length" :value length
                                         :on-commit #(apply-op! col row {:op :set-length :beats % :snap? false})}))
                 (cell 0 2 {:fx/type :button
                            :id "timeline-length-auto"
                            :text "Auto"
                            :disable (nil? explicit)
                            :style-class ["button" "tl-icon-button"]
                            :tooltip {:fx/type :tooltip :text "End at the last clip"}
                            :on-action (fn [_] (apply-op! col row {:op :set-length :beats nil}))})
                 (cell 1 0 (field-label "Tempo"))
                 (cell 1 1 (if locked
                             (beats-field {:id "timeline-tempo-bpm" :value locked
                                           :on-commit #(apply-op! col row {:op :set-tempo :bpm %})})
                             {:fx/type :label :text (format "follows %.1f" (double (or global-bpm 120.0)))
                              :style-class "tl-field-value"}))
                 (cell 1 2 {:fx/type :button
                            :text (if locked "Follow" "Lock")
                            :style-class ["button" "tl-icon-button"]
                            :on-action (fn [_] (apply-op! col row {:op :set-tempo :bpm (when-not locked global-bpm)}))})
                 (cell 2 0 (field-label "Starts at"))
                 (cell 2 1 (text-commit-field
                            {:id "timeline-tc-start"
                             :text (tc/format-tc (tc/seconds->tc tc-start fps))
                             :on-commit (fn [text]
                                          (when-let [t (tc/parse-tc text fps)]
                                            (apply-op! col row {:op :set-tc-start :seconds (tc/tc->seconds t fps)})))}))
                 (cell 3 0 (field-label "Frame rate"))
                 (cell 3 1 {:fx/type :combo-box
                            :id "timeline-tc-fps"
                            :items tc/frame-rates
                            :value fps
                            :button-cell (fn [f] {:text (fps-name f)})
                            :cell-factory {:fx/cell-type :list-cell
                                           :describe (fn [f] {:text (fps-name f)})}
                            :on-value-changed (fn [f] (when (and f (not= f fps))
                                                        (apply-op! col row {:op :set-tc-fps :fps f})))})
                 (cell 4 0 (field-label "Follow timecode"))
                 (cell 4 1 (assoc (chase-toggle col row chase) :grid-pane/column-span 2))]}
     {:fx/type mtc-rate-warning :fps fps}
     {:fx/type :label
      :text "Tempo isn't locked: the timecode maps to beats at the global BPM."
      :style-class "tl-warning"
      :wrap-text true
      :visible (boolean (and chase (not locked)))
      :managed (boolean (and chase (not locked)))}
     {:fx/type :check-box
      :text "Loop"
      :selected (boolean enabled?)
      :on-selected-changed (fn [on] (apply-op! col row {:op :set-loop :enabled? on}))}
     {:fx/type :grid-pane
      :hgap 8 :vgap 6
      :children [(cell 0 0 (field-label "Loop start"))
                 (cell 0 1 (beats-field {:id "timeline-loop-start" :value start
                                         :on-commit #(apply-op! col row {:op :set-loop :start (max 0.0 %)})}))
                 (cell 1 0 (field-label "Loop length"))
                 (cell 1 1 (beats-field {:id "timeline-loop-length" :value duration
                                         :on-commit #(apply-op! col row {:op :set-loop :duration (max model/min-clip-length %)})}))]}
     {:fx/type :label
      :text (str "Without a loop the timeline stops at its end. Lock the tempo for pre-recorded shows "
                 "so beats map to fixed times. Click or drag the ruler to move the cursor; Space plays from it.")
      :wrap-text true
      :style-class "tl-field-label"}]))

(defn inspector
  "Props: :col :row :cue :tracks :zone-groups :destination-zone-id :target
   (selection-target) :global-bpm"
  [{:keys [target] :as props}]
  {:fx/type :scroll-pane
   :id "timeline-inspector"
   :fit-to-width true
   :hbar-policy :never
   :style "-fx-background: #161616; -fx-background-color: #161616;"
   :min-width 300 :pref-width 300 :max-width 300
   :content {:fx/type :v-box
             :style-class "tl-inspector"
             :children (case (:kind target)
                         :clip (clip-section props (:entity target))
                         :clips (clips-section props (:ids target))
                         :track (track-section props (:entity target))
                         (timeline-section props))}})
