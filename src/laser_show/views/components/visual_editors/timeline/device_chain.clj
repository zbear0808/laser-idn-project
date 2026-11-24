(ns laser-show.views.components.visual-editors.timeline.device-chain
  "The effects of the selected clip, track or folder as a row of cards, like
   Ableton's device view: each card has an on/off switch, move left/right,
   remove, and the effect's parameters (the shared effect-parameter-editor).
   Effects are added from the browser on the right: a click appends, a drag
   inserts where it's dropped (between cards; the insertion line is the
   drop-before / drop-after pseudo-class on a card)."
  (:require [cljfx.api :as fx]
            [clj-font-awesome.core :as fa]
            [laser-show.animation.chains :as chains]
            [laser-show.animation.effects :as effects]
            [laser-show.events.core :as events]
            [laser-show.views.components.drag-drop-cell :as dd]
            [laser-show.views.components.effect-parameter-editor :as param-editor]
            [laser-show.views.components.visual-editors.timeline.drop :as drop]
            [laser-show.views.components.visual-editors.timeline.inspector :as inspector])
  (:import [javafx.css PseudoClass]
           [javafx.scene Node Parent]
           [javafx.scene.input DragEvent TransferMode]))


(defn- op-event [col row target op]
  {:event/type :timeline/apply-op :col col :row row
   :op (merge op (if (= :clip (:kind target))
                   {:clip-id (:id (:entity target))}
                   {:track-id (:id (:entity target))}))})

(defn- card-button [{:keys [id icon tooltip disable on-action]}]
  (cond-> {:fx/type :button
           :style-class ["button" "tl-icon-button"]
           :focus-traversable false
           :graphic {:fx/type fa/icon :name icon :size 10}
           :tooltip {:fx/type :tooltip :text tooltip}
           :disable (boolean disable)
           :on-action on-action}
    id (assoc :id id)))

(defn- effect-card
  [{:keys [fx/context col row target idx n effect]}]
  (let [group? (chains/group? effect)
        effect-def (when-not group? (effects/get-effect (:effect-id effect)))
        enabled? (:enabled? effect true)
        domain (if (= :clip (:kind target)) :timeline-items :timeline-tracks)
        ui-key (if (= :clip (:kind target)) :items :tracks)
        dialog-data (fx/sub-val context get-in [:ui :dialogs :timeline-cue-editor :effect-ui ui-key])
        ev #(op-event col row target (assoc % :effect-instance-id (:id effect)))]
    {:fx/type :v-box
     :style-class (cond-> ["tl-device"] (not enabled?) (conj "tl-device-off"))
     :children
     [{:fx/type :h-box
       :style-class "tl-device-header"
       :alignment :center-left
       :spacing 4
       :children [{:fx/type :check-box
                   :id (str "timeline-device-enabled-" idx)
                   :focus-traversable false
                   :selected (boolean enabled?)
                   :tooltip {:fx/type :tooltip :text (if enabled? "Turn off" "Turn on")}
                   :on-selected-changed (fn [on]
                                          (when (not= on (boolean enabled?))
                                            (events/dispatch! (ev {:op :set-effect-enabled :enabled? on}))))}
                  {:fx/type :label
                   :id (str "timeline-device-name-" idx)
                   :style-class "tl-device-name"
                   :max-width Double/MAX_VALUE
                   :h-box/hgrow :always
                   :text (cond
                           group? (str "Group (" (count (:items effect)) ")")
                           effect-def (:name effect-def)
                           :else (str (some-> (:effect-id effect) name)))}
                  (card-button {:icon :chevron-left :tooltip "Move left" :disable (zero? idx)
                                :on-action (ev {:op :move-effect :to (dec idx)})})
                  (card-button {:icon :chevron-right :tooltip "Move right" :disable (= idx (dec n))
                                :on-action (ev {:op :move-effect :to (inc idx)})})
                  (card-button {:id (str "timeline-device-remove-" idx) :icon :xmark :tooltip "Remove"
                                :on-action (ev {:op :remove-effect})})]}
      (if effect-def
        {:fx/type param-editor/effect-parameter-editor
         :v-box/vgrow :always
         :fx/context context
         :domain domain
         :entity-key [col row]
         :item-path (:path target)
         :owner-id (:id (:entity target))
         :effect-path [idx]
         :effect effect
         :effect-def effect-def
         :dialog-data dialog-data}
        {:fx/type :label
         :style-class "tl-field-label"
         :wrap-text true
         :text (if group?
                 "An effect group from a converted cue. Edit it in the Cue Chain Editor."
                 "Unknown effect")})]}))

(def ^:private drop-before (PseudoClass/getPseudoClass "drop-before"))
(def ^:private drop-after (PseudoClass/getPseudoClass "drop-after"))
(def ^:private drop-target-class (PseudoClass/getPseudoClass "drop-target"))

(defn- cards-of [^Parent chain]
  (some-> ^Parent (.lookup chain "#timeline-device-cards") .getChildrenUnmodifiable vec))

(defn- chain-drop
  "The browser drop at the event's position, plus where its insertion line
   goes: {:event :index :cards}, or nil."
  [^DragEvent e target col row]
  (when-let [payload (dd/read-payload e :bank-item drop/valid-payload?)]
    (let [chain ^Parent (.getSource e)
          box (.lookup chain "#timeline-device-cards")
          cards (or (cards-of chain) [])
          index (if box
                  (let [p (.sceneToLocal ^Node box (.getSceneX e) (.getSceneY e))]
                    (drop/chain-insert-index
                     (mapv (fn [^Node c] (let [b (.getBoundsInParent c)] [(.getMinX b) (.getWidth b)])) cards)
                     (.getX p)))
                  0)]
      (when-let [t (drop/drop-target payload {:type :chain :target target :index index} {:col col :row row})]
        (assoc t :index index :cards cards)))))

(defn- show-insertion!
  "Insertion line before card i, after the last card, or (no cards) on the
   hint. nil clears it. Pseudo-classes: cljfx re-renders don't touch them."
  [^Parent chain {:keys [index cards] :as at}]
  (let [n (count cards)]
    (doseq [[i ^Node c] (map-indexed vector (or (cards-of chain) []))]
      (.pseudoClassStateChanged c drop-before (boolean (and at (< index n) (= i index))))
      (.pseudoClassStateChanged c drop-after (boolean (and at (>= index n) (= i (dec n))))))
    (when-let [hint (.lookup chain "#timeline-device-hint")]
      (.pseudoClassStateChanged hint drop-target-class (boolean (and at (zero? n)))))))

(defn device-chain
  "Props: :col :row :target (inspector/selection-target)."
  [{:keys [col row target]}]
  (let [{:keys [kind entity]} target
        fx-list (vec (:effects entity))
        hint (fn [text] {:fx/type :label
                         :id "timeline-device-hint"
                         :style-class "tl-hint"
                         :wrap-text true
                         :text text})]
    {:fx/type :v-box
     :id "timeline-device-chain"
     :style-class "tl-device-chain"
     :on-drag-over (fn [^DragEvent e]
                     (when (dd/has-payload? (.getDragboard e) :bank-item)
                       (let [at (chain-drop e target col row)]
                         (when at (.acceptTransferModes e (into-array TransferMode [TransferMode/COPY])))
                         (show-insertion! (.getSource e) at)
                         (.consume e))))
     :on-drag-exited (fn [^DragEvent e] (show-insertion! (.getSource e) nil))
     :on-drag-dropped (fn [^DragEvent e]
                        (when (dd/has-payload? (.getDragboard e) :bank-item)
                          (let [at (chain-drop e target col row)]
                            (show-insertion! (.getSource e) nil)
                            (when at (events/dispatch! (:event at)))
                            (.setDropCompleted e (some? at))
                            (.consume e))))
     :children
     [{:fx/type :label
       :style-class "tl-inspector-title"
       :text (if (#{:clip :track} kind)
               (str "EFFECTS · " (inspector/target-name target))
               "EFFECTS")}
      (cond
        (not (#{:clip :track} kind))
        (hint (if (= :clips kind)
                "Select one clip, a track or a folder to see its effects."
                "Select a clip, a track or a folder to see its effects."))

        (empty? fx-list)
        (hint (str "No effects on " (inspector/target-name target)
                   ". Click or drag an effect from the browser on the right to add one."
                   (when (= :track kind) " Track and folder effects apply to every clip on them.")))

        :else
        {:fx/type :scroll-pane
         :v-box/vgrow :always
         :fit-to-height true
         :vbar-policy :never
         :style "-fx-background: #121212; -fx-background-color: #121212;"
         :content {:fx/type :h-box
                   :id "timeline-device-cards"
                   :spacing 6
                   :children (vec (map-indexed
                                   (fn [i fx]
                                     {:fx/type effect-card
                                      :fx/key (:id fx i)
                                      :col col :row row :target target
                                      :idx i :n (count fx-list) :effect fx})
                                   fx-list))}})]}))
