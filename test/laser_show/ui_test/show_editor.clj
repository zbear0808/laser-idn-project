(ns laser-show.ui-test.show-editor
  "Opening the timeline editor on a library show from a UI test
   (plans/2026-11-25-timeline-shows/05-editor-rekey.md §8.1)."
  (:require [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.timeline.shows :as shows]
            [laser-show.ui-test.testfx :as ui]))

(defn dialog-show
  "The show the timeline editor's dialog names."
  []
  (get-in (state/get-raw-state) [:ui :dialogs :timeline-cue-editor :show-id]))

(defn editor-shown? []
  (some (fn [title] (and title (.startsWith ^String title "Timeline Editor")))
        (ui/window-titles)))

(defn new-show-from-cue!
  "Copy the cue at [col row] into a new show (New Show from Cue), which
   opens the editor on it, or shows it in the open editor. Its id."
  [col row]
  (let [before (dialog-show)]
    (events/dispatch! {:event/type :shows/new-from-cue :col col :row row})
    (ui/wait-for! #(let [id (dialog-show)]
                     (and (some? id) (not= before id)
                          (get-in (state/get-raw-state) (shows/show-path id))
                          (get-in (state/get-raw-state) [:ui :dialogs :timeline-cue-editor :open?]))))
    (ui/wait-for! editor-shown?)
    (dialog-show)))

(defn open-show!
  "Put show (a timeline cue map) in the library as id and open the editor
   on it. id."
  [id show]
  (events/write-state! assoc-in (shows/show-path id) show)
  (events/dispatch! {:event/type :shows/open-editor :show-id id})
  (ui/wait-for! #(= id (dialog-show)))
  (ui/wait-for! editor-shown?)
  id)
