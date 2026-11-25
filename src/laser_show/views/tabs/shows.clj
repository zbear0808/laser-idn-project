(ns laser-show.views.tabs.shows
  "The Shows tab (plans/2026-11-25-timeline-shows 01 §7, 06 §4.5): the header
   (+ New Show, the search and its scope, the count, incoming MIDI
   timecode) over the table (views/components/show_table.clj). The preview
   stays beside it (views/root.clj). ⌘F goes to the search; ⌘N stays File >
   New (06 P7)."
  (:require [cljfx.api :as fx]
            [cljfx.lifecycle :as lifecycle]
            [cljfx.mutator :as mutator]
            [cljfx.prop :as prop]
            [clojure.string :as str]
            [laser-show.events.core :as events]
            [laser-show.subs :as subs]
            [laser-show.views.components.applied-value :as applied-value]
            [laser-show.views.components.now-playing :as now-playing]
            [laser-show.views.components.show-table :as show-table])
  (:import [javafx.scene Node]
           [javafx.scene.control TextField]
           [javafx.scene.input KeyCode KeyEvent]))


;; The search field (06 §4.5, probe g)


(defn- apply-query-text!
  "The search field's text from the query, unless the user is typing in it
   (its own text is newer than a render that lags the event queue: a bound
   :text garbled typing in the probe, 06 P4), except an empty query
   (Escape, ✕, Clear search), which always applies."
  [^TextField f text]
  (when (and (not= text (.getText f))
             (or (= "" text) (not (identical? f (some-> f .getScene .getFocusOwner)))))
    (.setText f text)
    (.end f)))

(def ^:private ext-with-query-text
  (fx/make-ext-with-props
   {:query-text (prop/make (mutator/setter apply-query-text!) lifecycle/scalar)}))

(defn- search-key-filter
  "Escape in a search field with text clears it, and goes no further; in an
   empty one it goes on to Transport's Stop, as in any field."
  [e]
  (when (and (instance? KeyEvent e)
             (= KeyEvent/KEY_PRESSED (.getEventType ^KeyEvent e))
             (= KeyCode/ESCAPE (.getCode ^KeyEvent e))
             (seq (.getText ^TextField (.getSource ^KeyEvent e))))
    (.consume ^KeyEvent e)
    (events/dispatch! {:event/type :shows/set-query :text ""})))

(def ^:private scope-labels
  {:all "All columns" :name "Title" :artist "Artist" :album "Album" :key "Key"
   :genre "Genre" :bpm "BPM" :duration "Duration"})

(defn- scope-cell [scope] {:text (get scope-labels scope "")})

(defn- scope-change [scope] {:event/type :shows/set-scope :scope scope})

(defn- count-text
  "\"12 shows\", \"3 of 12 shows · 2 on TC · 2 selected\"."
  [{:keys [total visible on-tc selected-visible]}]
  (str (if (= total visible) total (str visible " of " total))
       (if (= 1 total) " show" " shows")
       (when (pos? on-tc) (str " · " on-tc " on TC"))
       (when (pos? selected-visible) (str " · " selected-visible " selected"))))

(defn- mtc-chip
  "Incoming MIDI timecode: green while it runs, amber while it freewheels,
   grey when there is none. It subscribes itself, so only it redraws as
   timecode runs."
  [{:keys [fx/context]}]
  (let [{:keys [state text]} (fx/sub-ctx context subs/mtc-status)
        state (or state :stopped)]
    {:fx/type :label
     :id "shows-mtc-chip"
     :style-class ["label" "shows-tc-chip" (str "shows-tc-" (name state))]
     :text (str "MTC " (if (= :stopped state) "—" text))
     :tooltip {:fx/type :tooltip
               :text (case state
                       :running "MIDI timecode is coming in"
                       :freewheel "MIDI timecode dropped out: carrying on at the last speed"
                       "No MIDI timecode coming in")}}))

(defn- header
  "+ New Show, the search and its scope, the count and the MTC chip."
  [{:keys [fx/context]}]
  (let [{:keys [query scope]} (fx/sub-ctx context subs/show-table-ui)
        status (fx/sub-ctx context subs/show-table-status)]
    {:fx/type :h-box
     :style-class "shows-header"
     :alignment :center-left
     :spacing 8
     :children [{:fx/type :button
                 :id "shows-new"
                 :text "+ New Show"
                 :style-class "button-primary"
                 :focus-traversable false
                 :on-action {:event/type :shows/create}}
                {:fx/type ext-with-query-text
                 :props {:query-text query}
                 :desc {:fx/type :text-field
                        :id "shows-search"
                        :prompt-text "Search shows…"
                        :pref-width 260
                        :style-class (cond-> ["text-field" "text-field-dark" "shows-search"]
                                       (:query-problem status) (conj "text-field-invalid"))
                        :event-filter search-key-filter
                        :on-text-changed {:event/type :shows/set-query}}}
                {:fx/type :button
                 :id "shows-search-clear"
                 :text "✕"
                 :style-class "button-icon"
                 :focus-traversable false
                 :visible (not (str/blank? query))
                 :managed (not (str/blank? query))
                 :tooltip {:fx/type :tooltip :text "Clear search"}
                 :on-action {:event/type :shows/set-query :text ""}}
                {:fx/type applied-value/applied-control
                 :property :value
                 :address [:ui :shows :scope]
                 :value scope
                 :on-change scope-change
                 :desc {:fx/type :combo-box
                        :id "shows-scope"
                        :focus-traversable false
                        :items [:all :name :artist :album :key :genre :bpm :duration]
                        :button-cell scope-cell
                        :cell-factory {:fx/cell-type :list-cell :describe scope-cell}}}
                {:fx/type :region :h-box/hgrow :always}
                {:fx/type :label
                 :id "shows-count"
                 :style-class "text-count"
                 :text (count-text status)}
                {:fx/type mtc-chip}]}))

(defn- tab-key-filter
  "⌘F anywhere on the tab: the search field, all of its text selected."
  [e]
  (when (and (instance? KeyEvent e)
             (= KeyEvent/KEY_PRESSED (.getEventType ^KeyEvent e))
             (.isShortcutDown ^KeyEvent e)
             (= KeyCode/F (.getCode ^KeyEvent e)))
    (when-let [^TextField f (some-> ^Node (.getSource ^KeyEvent e) (.lookup "#shows-search"))]
      (.consume ^KeyEvent e)
      (.requestFocus f)
      (.selectAll f))))

(defn shows-tab
  "The Shows tab: the header, the table."
  [_]
  {:fx/type :v-box
   :id "shows-tab-root"
   :style-class "container-primary"
   :padding 16
   :spacing 8
   :event-filter tab-key-filter
   :children [{:fx/type header}
              {:fx/type show-table/show-table
               :v-box/vgrow :always}
              {:fx/type now-playing/now-playing-bar}]})
