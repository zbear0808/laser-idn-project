(ns laser-show.views.components.rig-file-status
  "The Projectors tab's line about the rig file (plans/2026-11-19-zones/09
   §7): when it was saved, that it is saving, or why it couldn't be, with
   Retry. For a rig another window has, or a file from a newer build, an
   amber banner says why changes aren't saved. The tooltip says where the
   file is. It is saved by itself (handlers/rig_autosave.clj), so there is
   nothing to press while all is well."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.css.core :as css]
            [laser-show.views.dialogs.rig-report :as rig-report])
  (:import [java.time Instant ZoneId]
           [java.time.format DateTimeFormatter]
           [java.util Locale]))

(def ^:private banners
  {:locked "This rig is open in another Laser Show window. Changes here aren't saved."
   :newer "This rig file comes from a newer version of Laser Show. It is used, but not saved."})

(defn- saved-text
  "\"Rig saved 14:02\", with the date when it wasn't today."
  [ms now ^ZoneId zone]
  (let [at (.atZone (Instant/ofEpochMilli ms) zone)
        today? (= (.toLocalDate at) (.toLocalDate (.atZone (Instant/ofEpochMilli now) zone)))]
    (str "Rig saved " (.format at (DateTimeFormatter/ofPattern (if today? "HH:mm" "d MMM HH:mm") Locale/ENGLISH)))))

(defn status-line
  "What the line says of shown (rig-file-shown, what it reads of
   [:rig-file]) at now in zone: {:text :tone (:muted :normal :error
   :warning) :retry? :banner?}, or nil without a rig file (tests, the
   REPL)."
  [{:keys [path status read-only? written-at error saving?]} now zone]
  (when path
    (cond
      read-only? {:text (get banners status (:locked banners)) :tone :warning :banner? true}
      (= :write-failed status) {:text (str "Rig not saved: " error) :tone :error :retry? true}
      saving? {:text "Saving rig…" :tone :normal}
      written-at {:text (saved-text written-at now zone) :tone :muted}
      :else {:text "Rig saves automatically" :tone :muted})))

(defn- rig-file-shown
  "What the line shows of [:rig-file]: its :path :status :read-only?
   :written-at and :error, and :saving? (a write asked for and not yet
   done). Not the counts themselves or the group copy, which change with
   every rig edit (follow-up R3)."
  [context]
  (let [{:keys [requested written] :as rig-file} (fx/sub-val context :rig-file)]
    (assoc (select-keys rig-file [:path :status :read-only? :written-at :error])
           :saving? (< (or written 0) (or requested 0)))))

(defn path-text
  "The rig file's path for its tooltip, the home folder as ~."
  [path]
  (let [home (System/getProperty "user.home")]
    (if (and (string? path) (not (str/blank? home)) (str/starts-with? path home))
      (str "~" (subs path (count home)))
      (str path))))

(defn- tone-style [tone]
  (case tone
    :muted (str "-fx-text-fill: " (css/text-muted) "; -fx-font-size: 11;")
    :normal (str "-fx-text-fill: " (css/text-secondary) "; -fx-font-size: 11;")
    :error (str "-fx-text-fill: " (css/red) "; -fx-font-size: 11;")
    :warning (str "-fx-background-color: " (css/orange) "; -fx-background-radius: 4; -fx-padding: 4 8;"
                  " -fx-text-fill: #1E1E1E; -fx-font-size: 12; -fx-font-weight: bold;")))

(defn rig-file-status
  "The rig file's line (or banner) under the Projectors tab's title, and at
   its right, while an open show has ⚠ rows, What doesn't fit (n), which
   reopens its report. Takes no room when there is nothing to say."
  [{:keys [fx/context]}]
  (let [shown (fx/sub-ctx context rig-file-shown)
        {:keys [text tone retry? banner?] :as line} (status-line shown (System/currentTimeMillis) (ZoneId/systemDefault))
        warnings (fx/sub-ctx context rig-report/warning-count)]
    (if (and (nil? line) (zero? warnings))
      {:fx/type :region :managed false :visible false}
      {:fx/type :h-box
       :spacing 8
       :alignment :center-left
       :children (cond-> []
                   line (conj {:fx/type :label
                               :id (if banner? "rig-file-banner" "rig-file-status")
                               :text text
                               :wrap-text true
                               :min-height :use-pref-size
                               :style (tone-style tone)
                               :tooltip {:fx/type :tooltip :text (path-text (:path shown))}})
                   retry? (conj {:fx/type :button
                                 :id "rig-file-retry"
                                 :text "Retry"
                                 :min-width :use-pref-size
                                 :style-class ["button-secondary"]
                                 :on-action {:event/type :rig/retry-write}})
                   (pos? warnings) (conj {:fx/type :region :h-box/hgrow :always}
                                         {:fx/type :hyperlink
                                          :id "rig-report-link"
                                          :text (str "What doesn't fit (" warnings ")")
                                          :min-width :use-pref-size
                                          :style (str "-fx-text-fill: " (css/orange) ";")
                                          :on-action {:event/type :rig/open-report}}))})))
