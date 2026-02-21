(ns laser-show.views.dialogs.timeline-cue-editor
  "Timeline-based cue chain editor dialog.
   
   An alternate editor for cue chains that uses a multi-track timeline
   rather than the list-based layout of the standard cue chain editor.
   
   Provides:
   - Timeline view for sequencing items with start/duration
   - Destination zone picker
   - Trigger mode picker
   - Editable chain name"
  (:require [cljfx.api :as fx]
            [laser-show.subs :as subs]
            [laser-show.animation.presets :as presets]
            [laser-show.events.core :as events]
            [laser-show.css.core :as css]
            [laser-show.views.components.inline-edit :as inline-edit]
            [laser-show.views.components.visual-editors.timeline-editor :as timeline-editor]
            [laser-show.state.extractors :as ex]
            [laser-show.common.util :as u]))


;; Destination Zone Picker (shared with cue-chain-editor)


(defn- destination-zone-dropdown
  [{:keys [col row destination-zone zone-groups]}]
  (let [current-group-id (:zone-group-id destination-zone :all)
        current-group (u/seek #(= (:id %) current-group-id) zone-groups)]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text "Destination Zone:"
                 :style-class "zone-picker-label"}
                {:fx/type :combo-box
                 :value current-group
                 :pref-width 150
                 :items zone-groups
                 :button-cell (fn [group]
                                {:text (or (:name group) "All")})
                 :cell-factory {:fx/cell-type :list-cell
                                :describe (fn [group]
                                            {:text (or (:name group) "All")})}
                 :on-value-changed {:event/type :cue-chain/set-destination-zone-group
                                    :col col
                                    :row row}}]}))


;; Trigger Mode Picker (shared with cue-chain-editor)


(def ^:private trigger-mode-options
  [{:id :toggle :name "Toggle"}
   {:id :retrigger :name "Retrigger"}])

(defn- trigger-mode-dropdown
  [{:keys [col row trigger-mode]}]
  (let [current-mode (or trigger-mode :toggle)
        current-option (or (u/seek #(= (:id %) current-mode) trigger-mode-options)
                           (first trigger-mode-options))]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text "Trigger Mode:"
                 :style-class "zone-picker-label"}
                {:fx/type :combo-box
                 :value current-option
                 :pref-width 120
                 :items trigger-mode-options
                 :button-cell (fn [mode]
                                {:text (or (:name mode) "Toggle")})
                 :cell-factory {:fx/cell-type :list-cell
                                :describe (fn [mode]
                                            {:text (or (:name mode) "Toggle")})}
                 :on-value-changed {:event/type :cue-chain/set-trigger-mode
                                    :col col
                                    :row row}}]}))


;; Main Content


(defn- timeline-cue-editor-content
  "Main content of the timeline-based cue chain editor.
   Header: editable name
   Center: timeline-editor component with list sidebar
   Footer: destination zone, trigger mode, close button"
  [{:keys [fx/context]}]
  (let [dialog-data (fx/sub-ctx context subs/dialog-data :timeline-cue-editor)
        {:keys [col row]} dialog-data

        ;; Cue chain data
        chains-state (fx/sub-val context :chains)
        cue-chain (or (get-in chains-state [:cue-chains [col row]]) {:items []})
        items (:items cue-chain [])

        ;; Zone groups
        zone-groups (fx/sub-ctx context subs/zone-groups-list)
        destination-zone-id (get-in cue-chain [:destination-zone :zone-group-id])

        ;; Timeline UI state
        timeline-ui (fx/sub-val context get-in [:ui :timeline])

        ;; Beats elapsed from global clock
        beats-elapsed (fx/sub-val context ex/global-accumulated-beats)

        ;; Clipboard for list-editor
        clipboard-items (fx/sub-val context get-in [:ui :clipboard :cue-chain-items])

        ;; Chain name
        chain-name (:name cue-chain)
        default-name (str "Cell " (char (+ 65 (or row 0))) (inc (or col 0)))
        editing-name? (:editing-name? dialog-data false)]
    {:fx/type :v-box
     :spacing 0
     :style-class "dialog-content"
     :pref-width 900
     :pref-height 500
     :children
     [;; Header with editable name
      {:fx/type :h-box
       :alignment :center-left
       :style-class "dialog-header"
       :children [{:fx/type inline-edit/inline-edit-text
                   :value chain-name
                   :placeholder default-name
                   :editing? editing-name?
                   :on-start-edit {:event/type :ui/update-dialog-data
                                   :dialog-id :timeline-cue-editor
                                   :editing-name? true}
                   :on-commit {:event/type :cue-chain/set-name
                               :col col
                               :row row}
                   :on-cancel {:event/type :ui/update-dialog-data
                               :dialog-id :timeline-cue-editor
                               :editing-name? false}}]}
      ;; Timeline editor (center) with list sidebar
      {:fx/type timeline-editor/timeline-editor
       :fx/context context
       :v-box/vgrow :always
       :col col
       :row row
       :items items
       :track-defs (:tracks cue-chain)
       :zone-groups (into {} (map (juxt :id identity)) zone-groups)
       :destination-zone-id destination-zone-id
       :timeline-ui timeline-ui
       :beats-elapsed (or beats-elapsed 0.0)
       :list-props {:component-id [:timeline-cue-chain col row]
                    :item-id-key :preset-id
                    :item-registry-fn presets/presets-by-id
                    :fallback-label "Unknown Preset"
                    :on-change-event :chain/set-items
                    :on-change-params {:domain :cue-chains :entity-key [col row]}
                    :items-path [:chains :cue-chains [col row] :items]
                    :on-copy-fn (fn [copied-items]
                                  (events/dispatch! {:event/type :cue-chain/set-clipboard
                                                     :items copied-items}))
                    :clipboard-items clipboard-items}}

      ;; Footer
      {:fx/type :h-box
       :alignment :center-left
       :spacing 12
       :style-class "dialog-footer"
       :children [{:fx/type destination-zone-dropdown
                   :col col
                   :row row
                   :destination-zone (:destination-zone cue-chain)
                   :zone-groups zone-groups}
                  {:fx/type trigger-mode-dropdown
                   :col col
                   :row row
                   :trigger-mode (:trigger-mode cue-chain)}
                  {:fx/type :region
                   :h-box/hgrow :always}
                  {:fx/type :button
                   :text "Close"
                   :style-class "button-primary"
                   :on-action {:event/type :ui/close-dialog
                               :dialog-id :timeline-cue-editor}}]}]}))


;; Dialog Window


(defn- timeline-cue-editor-scene
  [{:keys [stylesheets]}]
  {:fx/type :scene
   :stylesheets stylesheets
   :root {:fx/type timeline-cue-editor-content}})

(defn timeline-cue-editor-dialog
  "The timeline-based cue chain editor dialog window."
  [{:keys [fx/context]}]
  (let [open? (fx/sub-ctx context subs/dialog-open? :timeline-cue-editor)
        dialog-data (fx/sub-ctx context subs/dialog-data :timeline-cue-editor)
        {:keys [col row]} dialog-data
        chains-state (fx/sub-val context :chains)
        cue-chain (get-in chains-state [:cue-chains [col row]])
        chain-name (:name cue-chain)
        stylesheets (css/dialog-stylesheet-urls)
        cell-id (str "Cell " (char (+ 65 (or row 0))) (inc (or col 0)))
        window-title (str "Timeline Editor - "
                          (if (seq chain-name)
                            (str chain-name " (" cell-id ")")
                            cell-id))]
    {:fx/type :stage
     :showing open?
     :title window-title
     :modality :none
     :on-close-request {:event/type :ui/close-dialog :dialog-id :timeline-cue-editor}
     :scene {:fx/type timeline-cue-editor-scene
             :stylesheets stylesheets}}))
