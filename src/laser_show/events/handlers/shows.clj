(ns laser-show.events.handlers.shows
  "Event handlers for the timeline show library (plans/2026-11-25-timeline-shows).

   Handles:
   - Library edits: a new show, a new show copied from a grid cue
   - Opening the timeline editor on a show
   - Playing by hand (:shows/play, :shows/stop: one show at a time, TS-9)
   - A trigger's toggle (:shows/toggle-run: a pad starts or stops its own
     show as a grid press does, TS-11)
   - The Shows tab's migration banner

   State paths:
   - Shows: [:chains :shows show-id] (shows/show-path)
   - Their playback: [:playback :active-cues [:show show-id]] (shows/play-key)
   - Tab state: [:ui :shows] (:selected, :manual)

   A handler that names no show, or a show that isn't there, returns
   {:state state}: nothing changes."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [laser-show.animation.chains :as chains]
            [laser-show.animation.cue-timing :as cue-timing]
            [laser-show.events.helpers :as h]
            [laser-show.routing.rig :as rig]
            [laser-show.routing.targets :as targets]
            [laser-show.timeline.model :as model]
            [laser-show.timeline.playback :as playback]
            [laser-show.timeline.shows :as shows]))


;; Helpers


(defn- show-at [state id]
  (when (some? id)
    (get-in state (shows/show-path id))))

(defn- start-show
  "state with show k (a playback key) started from beat 0 as a new run,
   which the show clock starts at its next tick. Like any cue start it
   resumes a held transport (cue-timing/activate-cue)."
  [state k]
  (update state :playback cue-timing/activate-cue k (random-uuid) {:timeline? true :start-beat 0.0}))

(defn- stop-show [state k]
  (update state :playback cue-timing/deactivate-cue k))

(defn- stop-chase
  "state with show k, which timecode drives as the show clock's episode
   chase-id, stopped for that episode only ([:playback :chase-stopped]), as
   a grid press stops a chased cue (follow-up B7)."
  [state k chase-id]
  (cond-> (stop-show state k)
    (some? chase-id) (assoc-in [:playback :chase-stopped k] chase-id)))

(defn- chase-stopped?
  "Is show k one that follows timecode and that a toggle stopped for an
   episode (stop-chase)?"
  [state k]
  (and (contains? (get-in state [:playback :chase-stopped]) k)
       (playback/chases-mtc? (shows/cue-at state k))))

(defn- follow-again
  "state with show k following timecode again: the show clock takes it over
   at its next tick while timecode runs inside it."
  [state k]
  (update-in state [:playback :chase-stopped] dissoc k))


;; Library


(defn- handle-create
  "Add an empty show titled :title (\"Untitled\" without one) and select it."
  [{:keys [title state]}]
  (let [id (random-uuid)]
    {:state (-> state
                (assoc-in (shows/show-path id) (shows/new-show {:name title}))
                (assoc-in [:ui :shows :selected] #{id}))}))

(defn- open-editor
  "Effects that open the timeline editor on show id. A show that somehow
   skipped normalization is normalized here as a backfill: no undo step,
   not unsaved. A show with no clips opens with the browser, where its
   presets are. Opening it on another show than the one it showed lets go
   of the dialog's effect UI, which names that show's items."
  [state id]
  (let [path (shows/show-path id)
        show (get-in state path)
        new-track-id (random-uuid)
        normalize #(model/normalize-timeline-cue % {:new-track-id new-track-id})
        show' (normalize show)
        changed? (not (identical? show show'))
        another? (not= id (get-in state [:ui :dialogs :timeline-cue-editor :show-id]))]
    (cond-> {:state (cond-> state
                      changed? (assoc-in path show')
                      (empty? (:items show')) (assoc-in [:ui :timeline-prefs :add-panel :open?] true)
                      another? (update-in [:ui :dialogs :timeline-cue-editor] dissoc :effect-ui))
             :dispatch {:event/type :ui/open-dialog
                        :dialog-id :timeline-cue-editor
                        :data {:show-id id :editing-name? false}}}
      changed? (assoc :history/backfill
                      (fn [doc]
                        (if (get-in doc path)
                          (update-in doc path normalize)
                          doc))))))

(defn- handle-open-editor
  [{:keys [show-id state]}]
  (if (show-at state show-id)
    (open-editor state show-id)
    {:state state}))

(defn- cue->show
  "The show a copy of the grid cue at [col row] becomes: new ids throughout
   (chains/fresh-ids), converted without changing what it renders
   (model/convert-to-timeline), with timecode off and no :id or
   :trigger-mode. A cue with no target of its own takes its column's
   default, so the show draws where the cue did; a cue with no name is
   \"Untitled\"."
  [state col row cue]
  (let [names (into {} (map (juxt :id identity)) (rig/target-groups state))
        column-target (get-in state [:grid :column-targets col])
        show (-> (chains/fresh-ids cue)
                 (dissoc :id :trigger-mode :timeline/chase)
                 (model/convert-to-timeline names)
                 (assoc :editor-type :timeline)
                 model/normalize-timeline-cue)
        name (:name show)]
    (cond-> show
      (and (nil? (targets/normalize-target (:target show)))
           (some? (targets/normalize-target column-target)))
      (assoc :target column-target)

      (not (and (string? name) (not (str/blank? name))))
      (assoc :name "Untitled"))))

(defn- handle-new-from-cue
  "Copy the cue at [col row] into a new show, select it, show the Shows tab
   and open the editor on it. The cell stays as it is. One undo step."
  [{:keys [col row state]}]
  (if-not (and (int? col) (int? row))
    {:state state}
    (let [id (random-uuid)
          show (cue->show state col row (get-in state [:chains :cue-chains [col row]]))
          added (-> state
                    (assoc-in (shows/show-path id) show)
                    (assoc-in [:ui :shows :selected] #{id})
                    (assoc-in [:ui :active-tab] :shows))]
      (-> (open-editor added id)
          (dissoc :history/backfill)
          (assoc :history/description "New Show from Cue")))))


;; Playing


(defn- handle-toggle-run
  "A trigger's toggle of show :show-id, as a grid press on a timeline cell:
   - timecode drives it: stopped for this episode, until timecode enters
     it again (stop-chase);
   - so stopped: it follows timecode again (follow-again);
   - it plays: stopped;
   - it follows timecode but isn't driven: left alone, timecode starts it;
   - otherwise: started from beat 0, with the :cue pulse every cue start
     gives the tap reactions.
   [:ui :shows :manual] is left alone: a pad plays its own show."
  [{:keys [show-id state]}]
  (if-let [show (show-at state show-id)]
    (let [k (shows/play-key show-id)
          intent (get-in state [:playback :active-cues k])]
      {:state (cond
                (:chasing? intent) (stop-chase state k (:chase-id intent))
                (some? intent) (stop-show state k)
                (chase-stopped? state k) (follow-again state k)
                (playback/chases-mtc? show) state
                :else (-> (start-show state k)
                          (update-in [:playback :pulses :cue :id] (fnil inc 0))))})
    {:state state}))

(defn- handle-play
  "Play show :show-id by hand, from beat 0 (TS-9). A show that follows
   timecode is refused with a notice. The show played by hand before it
   ([:ui :shows :manual]) stops, unless timecode drives it; grid cues play
   on. :manual becomes this show."
  [{:keys [show-id state]}]
  (let [show (show-at state show-id)
        manual (get-in state [:ui :shows :manual])
        manual-k (when (some? manual) (shows/play-key manual))]
    (cond
      (nil? show)
      {:state state}

      (playback/chases-mtc? show)
      (h/show-notice state "Follows timecode. Untick TC to play it by hand." :info)

      :else
      {:state (cond-> state
                (and (some? manual)
                     (not= manual show-id)
                     (not (:chasing? (get-in state [:playback :active-cues manual-k]))))
                (stop-show manual-k)

                true (start-show (shows/play-key show-id))
                true (assoc-in [:ui :shows :manual] show-id))})))

(defn- handle-stop
  "Stop the show played by hand. [:ui :shows :manual] stays, so Play plays
   it again. A show timecode drives is left alone."
  [{:keys [state]}]
  (let [manual (get-in state [:ui :shows :manual])]
    (if (or (nil? manual) (playback/chases-mtc? (show-at state manual)))
      {:state state}
      {:state (stop-show state (shows/play-key manual))})))


;; Transport (S5)
;; The now-playing bar's buttons (plans/2026-11-25-timeline-shows 01 §5,
;; 07 §4.5). Each acts on the show played by hand ([:ui :shows :manual]),
;; and does nothing while timecode drives it. They change only
;; [:playback] and [:ui], so they're never undo steps.


(defn- by-hand?
  "Can show id be played by hand: it is there and doesn't follow timecode?"
  [state id]
  (let [show (show-at state id)]
    (and (some? show) (not (playback/chases-mtc? show)))))

(defn- manual-intent [state manual]
  (get-in state [:playback :active-cues (shows/play-key manual)]))

(defn- handle-toggle-play
  "⏯: pause or resume the show played by hand; play it again from beat 0
   once it has stopped or ended. With none played by hand yet, play the
   first selected show the table shows, else its first show, that doesn't
   follow timecode."
  [{:keys [state] :as event}]
  (let [manual (get-in state [:ui :shows :manual])
        intent (when (some? manual) (manual-intent state manual))]
    (cond
      (and (some? manual) (not (by-hand? state manual)))
      {:state state}

      (some? intent)
      {:state (update-in state [:playback :active-cues (shows/play-key manual) :paused?] not)}

      (some? manual)
      (handle-play (assoc event :show-id manual))

      :else
      (let [visible (shows/visible-ids state)
            selected (get-in state [:ui :shows :selected] #{})
            pick (->> (concat (filter selected visible) visible)
                      (filter #(by-hand? state %))
                      first)]
        (if pick
          (handle-play (assoc event :show-id pick))
          {:state state})))))

(defn- handle-seek
  "Move the show played by hand to :beat, at the show clock's next tick.
   Only while it plays or is paused, and when :show-id, if given, is still
   that show: a release that comes after another show took over seeks
   nothing."
  [{:keys [show-id beat state]}]
  (let [manual (get-in state [:ui :shows :manual])
        intent (when (some? manual) (manual-intent state manual))]
    (if (and (some? intent)
             (not (:chasing? intent))
             (number? beat)
             (or (nil? show-id) (= show-id manual))
             (by-hand? state manual))
      {:state (update-in state [:playback :active-cues (shows/play-key manual)]
                         cue-timing/request-seek beat (random-uuid))}
      {:state state})))

(defn- neighbour
  "The show after (step 1) or before (step -1) the one played by hand in
   the table's order, skipping shows that follow timecode; the first (or
   last) such show when the one played by hand isn't shown; nil at either
   end."
  [state step]
  (let [visible (shows/visible-ids state)
        manual (get-in state [:ui :shows :manual])
        i (.indexOf ^java.util.List visible manual)
        candidates (cond
                     (neg? i) (if (pos? step) visible (rseq visible))
                     (pos? step) (subvec visible (inc i))
                     :else (rseq (subvec visible 0 i)))]
    (first (filter #(by-hand? state %) candidates))))

(defn- handle-step
  "⏭ (step 1) and ⏮ (step -1): play the neighbouring show (neighbour)."
  [{:keys [state] :as event} step]
  (let [manual (get-in state [:ui :shows :manual])]
    (if (and (some? manual) (some? (show-at state manual)) (not (by-hand? state manual)))
      {:state state}
      (if-let [id (neighbour state step)]
        (handle-play (assoc event :show-id id))
        {:state state}))))


;; Public API


(defn handle
  "Dispatch :shows/* events to their handlers."
  [{:keys [event/type] :as event}]
  (case type
    :shows/create (handle-create event)
    :shows/new-from-cue (handle-new-from-cue event)
    :shows/open-editor (handle-open-editor event)
    :shows/toggle-run (handle-toggle-run event)
    :shows/play (handle-play event)
    :shows/stop (handle-stop event)
    :shows/toggle-play (handle-toggle-play event)
    :shows/seek (handle-seek event)
    :shows/next (handle-step event 1)
    :shows/previous (handle-step event -1)
    (do
      (log/warn "Unknown shows event:" type)
      {})))
