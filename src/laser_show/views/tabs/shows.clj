(ns laser-show.views.tabs.shows
  "The Shows tab (plans/2026-11-25-timeline-shows 01 §7), bare for now: + New
   Show and the count, the banner for what Open moved from the grid, and the
   library as a plain list, a row per show with ▶ (play it) and ✎ (open it
   in the timeline editor). The preview stays beside it (views/root.clj)."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.subs :as subs]))

(defn- quoted-names
  "'A', 'A' and 'B', 'A', 'B' and 'C'."
  [names]
  (let [qs (map #(str "'" % "'") names)]
    (if (next qs)
      (str (str/join ", " (butlast qs)) " and " (last qs))
      (first qs))))

(defn migration-text
  "The banner's text for [:ui :shows :migrated] (01 §6)."
  [{:keys [moved timecode-off]}]
  (str "Moved " moved " timeline cue" (when (not= 1 moved) "s") " from the grid into Shows. "
       (when (seq timecode-off)
         (str "Timecode turned off for " (quoted-names timecode-off)
              (if (next timecode-off) " (they overlapped). " " (it overlapped). ")))
       "Saving writes the new layout; older versions won't see these shows."))

(defn- migration-banner
  "What Open moved, until its ✕ dismisses it. Always there, hidden while
   there is nothing to say."
  [{:keys [fx/context]}]
  (let [migrated (fx/sub-ctx context subs/show-migration)]
    {:fx/type :h-box
     :id "shows-migration-banner"
     :style-class "shows-banner"
     :alignment :center-left
     :spacing 8
     :visible (some? migrated)
     :managed (some? migrated)
     :children [{:fx/type :label
                 :id "shows-migration-text"
                 :style-class "shows-banner-text"
                 :wrap-text true
                 :max-width Double/MAX_VALUE
                 :h-box/hgrow :always
                 :text (if migrated (migration-text migrated) "")}
                {:fx/type :button
                 :id "shows-banner-dismiss"
                 :text "✕"
                 :style-class "button-close"
                 :focus-traversable false
                 :on-action {:event/type :shows/dismiss-migration}}]}))

(defn- count-label [n]
  (str n (if (= 1 n) " show" " shows")))

(defn- header
  "+ New Show, and how many shows there are."
  [{:keys [fx/context]}]
  (let [n (count (fx/sub-ctx context subs/show-list-rows))]
    {:fx/type :h-box
     :alignment :center-left
     :spacing 8
     :children [{:fx/type :button
                 :id "shows-new"
                 :text "+ New Show"
                 :style-class "button-primary"
                 :focus-traversable false
                 :on-action {:event/type :shows/create}}
                {:fx/type :region :h-box/hgrow :always}
                {:fx/type :label
                 :id "shows-count"
                 :style-class "text-count"
                 :text (count-label n)}]}))

(defn- show-row
  "A show: its title (\"Untitled\", muted, without one), ▶ and ✎."
  [{:keys [id title]}]
  {:fx/type :h-box
   :id (str "shows-row-" id)
   :style-class "list-item"
   :alignment :center-left
   :spacing 4
   :children [{:fx/type :label
               :id (str "shows-title-" id)
               :style-class (if title "label-primary" "text-muted")
               :max-width Double/MAX_VALUE
               :h-box/hgrow :always
               :text (or title "Untitled")}
              {:fx/type :button
               :id (str "shows-play-" id)
               :text "▶"
               :style-class "button-icon"
               :focus-traversable false
               :tooltip {:fx/type :tooltip :text "Play"}
               :on-action {:event/type :shows/play :show-id id}}
              {:fx/type :button
               :id (str "shows-edit-" id)
               :text "✎"
               :style-class "button-icon"
               :focus-traversable false
               :tooltip {:fx/type :tooltip :text "Open in Editor"}
               :on-action {:event/type :shows/open-editor :show-id id}}]})

(defn- show-list
  "The library, a row per show (subs/show-list-rows), or the placeholder."
  [{:keys [fx/context]}]
  (let [rows (fx/sub-ctx context subs/show-list-rows)]
    (if (seq rows)
      {:fx/type :scroll-pane
       :id "shows-list"
       :fit-to-width true
       :hbar-policy :never
       :style-class "scroll-pane-dark"
       :content {:fx/type :v-box
                 :spacing 4
                 :padding 4
                 :children (mapv #(assoc % :fx/type show-row :fx/key (:id %)) rows)}}
      {:fx/type :label
       :id "shows-empty"
       :style-class "label-hint"
       :wrap-text true
       :text "No shows yet. + New Show, or New Show from Cue in the cue editor."})))

(defn shows-tab
  "The Shows tab: the header, the banner, the list."
  [_]
  {:fx/type :v-box
   :id "shows-tab-root"
   :style-class "container-primary"
   :padding 16
   :spacing 8
   :children [{:fx/type header}
              {:fx/type migration-banner}
              {:fx/type show-list
               :v-box/vgrow :always}]})
