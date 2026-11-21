(ns laser-show.events.handlers.rig
  "The rig strip's events (plans/2026-11-19-zones/04 §6): the order of the
   projectors, the middle laser, the split width, the bar that shows where
   a dragged card would land, and where the divider below the strip is left
   (13). Pure. A move writes the whole effective order, so the first one
   fixes every card's place. None of them restarts an engine: the frame
   provider reads the rig live.

   And the rig file's (09 §6): a write's result, Retry, and the startup
   notice. The writes themselves are asked for by the pipeline
   (handlers/rig_autosave.clj)."
  (:require [laser-show.events.handlers.rig-autosave :as rig-autosave]
            [laser-show.events.handlers.zone-groups :as zone-groups]
            [laser-show.events.helpers :as h]
            [laser-show.events.safety-guard :as guard]
            [laser-show.routing.refs :as refs]
            [laser-show.routing.rig :as rig]
            [laser-show.state.rig-file :as rig-file]))

(defn- order [state]
  (rig/effective-order (get-in state [:rig :order]) (:projectors state)))

(defn- with-order
  "state with the rig order ids, or as it is when that is the order it has."
  [state ids]
  (if (= ids (order state))
    state
    (assoc-in state [:rig :order] ids)))

(defn- handle-move-projector
  "Put projector-id before or after (:side :before or :after) the card of
   target, as a drop on a card's left or right half does. Nothing for an
   unknown card or target, a card on itself or another side. The drop bar
   goes either way."
  [{:keys [projector-id target side state]}]
  (let [ids (order state)
        state (assoc-in state [:rig-ui :drop] nil)]
    (if (and (some #{projector-id} ids) (some #{target} ids) (not= projector-id target) (#{:before :after} side))
      (let [others (filterv #(not= projector-id %) ids)
            at (cond-> (.indexOf ^java.util.List others target) (= :after side) inc)]
        {:state (with-order state (into (conj (subvec others 0 at) projector-id) (subvec others at)))})
      {:state state})))

(defn- handle-nudge-projector
  "Move projector-id one place left (a negative :delta) or right (a positive
   one), as ◀ ▶ and Alt+← / Alt+→ do; nothing at the ends."
  [{:keys [projector-id delta state]}]
  (let [ids (order state)
        i (.indexOf ^java.util.List ids projector-id)
        j (when (and (number? delta) (not (zero? delta))) (+ i (if (neg? delta) -1 1)))]
    (if (and (>= i 0) j (< -1 j (count ids)))
      {:state (assoc-in state [:rig :order] (-> ids (assoc i (ids j)) (assoc j projector-id)))}
      {:state state})))

(defn- handle-set-middle
  "How the middle laser of 3, 5, 7… joins Left and Right: one of
   rig/middle-policies; anything else is ignored."
  [{:keys [middle state]}]
  (if (contains? rig/middle-policies middle)
    {:state (assoc-in state [:rig :middle] middle)}
    {:state state}))

(defn- handle-set-split-width
  "The split width, clamped to 0.5-1.0. A slider's drag is one undo step."
  [{:keys [split-width state]}]
  (if (and (number? split-width) (not (Double/isNaN (double split-width))))
    {:state (assoc-in state [:rig :split-width] (max 0.5 (min 1.0 (double split-width))))
     :history/coalesce-key [:rig :split-width]}
    {:state state}))

(defn- handle-drag-over
  "A dragged card is over the card of projector-id, on its :side: the bar
   shows there."
  [{:keys [projector-id side state]}]
  (let [drop {:projector-id projector-id :side side}]
    (if (= drop (get-in state [:rig-ui :drop]))
      {:state state}
      {:state (assoc-in state [:rig-ui :drop] drop)})))

(defn- handle-drag-exit
  "The dragged card left a card: no bar."
  [{:keys [state]}]
  (if (get-in state [:rig-ui :drop])
    {:state (assoc-in state [:rig-ui :drop] nil)}
    {:state state}))

(defn- handle-set-divider
  "Where the user left the divider between the strip and the inspector, as
   the split pane gives it (0-1): kept in [:rig-ui :divider] for the
   session, so the tab shown again puts it back (views/tabs/projectors.clj).
   Never saved, never undone; New and Open keep it."
  [{:keys [position state]}]
  (if (and (number? position) (<= 0.0 position 1.0))
    {:state (assoc-in state [:rig-ui :divider] (double position))}
    {:state state}))


;; The rig file (09 §6, §7)

(defn- this-file?
  "Is a write's result about this window's rig file? One for another path
   (a test or a window before) is ignored."
  [state path]
  (and (some? path) (= path (get-in state [:rig-file :path]))))

(defn- answered
  "state with request seq answered."
  [state seq]
  (update-in state [:rig-file :written] #(max (or % 0) (or seq 0))))

(defn- handle-written
  "The rig file was written at :at: saved. An event waiting for it (Exit's
   Try again) carries on."
  [{:keys [path seq at then state]}]
  (if (this-file? state path)
    (cond-> {:state (-> state
                        (answered seq)
                        (update :rig-file assoc :status :saved :error nil :written-at at))}
      then (assoc :dispatch then))
    {:state state}))

(defn- handle-write-failed
  "The rig file couldn't be written: the status line says why, with Retry,
   and so does a notice. Exit asks first while it stays so."
  [{:keys [path seq error state]}]
  (if (this-file? state path)
    (h/show-notice (-> state
                       (answered seq)
                       (update :rig-file assoc :status :write-failed :error error))
                   (str "Rig not saved: " error)
                   :error)
    {:state state}))

(defn- handle-retry-write
  "Retry, and Exit's Try again: write the rig as it is now, at once. :then
   is the event to continue with once it is written."
  [{:keys [then state]}]
  (if (rig-autosave/writable? state)
    (rig-autosave/write-request state {:then then :now? true})
    {:state state}))

(defn- handle-startup-notice
  "What the startup read of the rig file has to say (a backup in use, an
   empty rig), once the window is up."
  [{:keys [text level state]}]
  (if (string? text)
    (h/show-notice state text (or level :warning))
    {:state state}))


;; A show's rig onto this one (09 §6, §7): the set-up question

(defn- opened-show
  "What the last Open kept of the show's rig ([:ui :dialogs :rig-report]):
   {:file :hardware :chains :pins (pin-map then) :mode :open?}."
  [state]
  (get-in state [:ui :dialogs :rig-report]))

(defn- ask-if-it-grows
  "{:state after}, unless after gives a laser that is outputting a larger
   safety zone and event isn't :confirmed?: then Z1's question first, and
   nothing changes (D-28)."
  [state after {:keys [confirmed?] :as event}]
  (let [grown (guard/growing state after)]
    (cond
      (or confirmed? (empty? grown)) {:state after}
      (guard/asking? state) {:state state}
      :else {:state (guard/ask state {:message (str (guard/names-text (map #(guard/projector-name state %) grown))
                                                    (if (= 1 (count grown)) " is" " are")
                                                    " on. The show's safety zone lets light reach places it couldn't before.")
                                      :event event})})))

(defn- handle-set-up-from-show
  "Set up rig: the lasers the show was made with become this rig
   (rig-file/set-up-from-show), in one undo step, and the question closes;
   from then on, what doesn't fit is the report's. A laser turned on since
   the question came up whose area this would grow asks first. Nothing
   without a show's lasers to set up."
  [{:keys [state] :as event}]
  (let [{:keys [hardware chains pins]} (opened-show state)]
    (if-not (rig-file/set-up-question state hardware)
      {:state state}
      (ask-if-it-grows state
                       (-> (rig-file/set-up-from-show state hardware chains pins)
                           (update-in [:ui :dialogs :rig-report] assoc
                                      :open? false :mode :report :pins (rig-file/pin-map state hardware)))
                       event))))


;; The report's buttons (09 §7)

(defn- handle-open-report
  "What doesn't fit (n), on the Projectors tab: the report again, or the
   set-up question while no laser here is on. Nothing before a show was
   opened."
  [{:keys [state]}]
  (if-let [{:keys [hardware]} (opened-show state)]
    {:state (update-in state [:ui :dialogs :rig-report] assoc
                       :open? true
                       :mode (if (and (not (rig-file/set-up? state)) (rig-file/set-up-question state hardware))
                               :set-up
                               :report))}
    {:state state}))

(defn- handle-choose-lasers
  "Choose lasers…: the report closes and the group opens in the Zone Groups
   tab (:zone-groups/show), to tick its lasers there."
  [{:keys [group-id state]}]
  (if (and (some? group-id) (opened-show state))
    (zone-groups/handle {:event/type :zone-groups/show
                         :group-id group-id
                         :state (assoc-in state [:ui :dialogs :rig-report :open?] false)})
    {:state state}))

(defn- handle-adopt-group
  "Use this rig's \"Graphics\": the show's group group-id on the lasers in
   this rig's same-named group from. The rig's: undoable, saved by itself."
  [{:keys [group-id from state]}]
  {:state (rig-file/adopt-group state group-id from)})

(defn- handle-repin
  "Pin them to… (a laser here) or Send them to All: the cues pinned to
   from, a laser that isn't here, go to to. The show's: undoable, unsaved."
  [{:keys [from to state]}]
  {:state (rig-file/repin state from to)})

(defn- handle-add-groups-to-show
  "Add them to this show: this rig's groups group-ids, as the rig's copy
   defines them. The show's: undoable, unsaved."
  [{:keys [group-ids state]}]
  {:state (rig-file/add-groups-to-show state group-ids)})

(defn- handle-import-new-lasers
  "Import n new lasers…: the show's lasers that aren't on this rig, with
   their setup (rig-file/import-new-lasers). One undo step; a laser already
   here never changes."
  [{:keys [state]}]
  (let [{:keys [hardware chains pins]} (opened-show state)]
    (if (seq (:projectors hardware))
      {:state (-> (rig-file/import-new-lasers state hardware chains pins)
                  (assoc-in [:ui :dialogs :rig-report :pins] (rig-file/pin-map state hardware)))}
      {:state state})))

(defn- what-text [what]
  (cond
    (= #{:safety} what) "safety zone"
    (= #{:calibration} what) "calibration"
    :else "safety zone and calibration"))

(defn- handle-request-use-show-setup
  "Use the show's for Laser L…: asks first (the shared confirm), Replace
   going on to :rig/use-show-setup. Not for a laser the show's setup doesn't
   differ for, nor one whose zone in the show can't be read."
  [{:keys [projector-id state]}]
  (let [{:keys [hardware chains] :as shown} (opened-show state)
        row (when shown
              (some #(when (= projector-id (:projector-id %)) %)
                    (:setup-differs (rig-file/match-report state hardware chains))))]
    (if (or (nil? row) (:unreadable? row))
      {:state state}
      (let [n (guard/projector-name state projector-id)]
        {:state (assoc-in state [:ui :dialogs :confirm]
                          {:open? true
                           :title (str "Replace " n "'s " (what-text (:what row)) " with the show's?")
                           :message (str "This changes where " n " can draw.")
                           :confirm-label "Replace"
                           :confirm-event {:event/type :rig/use-show-setup :projector-id projector-id}})}))))

(defn- handle-use-show-setup
  "Replace: the show's safety zone and calibration for projector-id
   (rig-file/use-show-setup, the zone through safety/normalize; one it can't
   read is refused). While the laser outputs and its area grows, Z1's
   \"Allow light in a larger area?\" first (D-28), which sends this again
   with :confirmed? true. One undo step."
  [{:keys [projector-id state] :as event}]
  (let [{:keys [hardware chains]} (opened-show state)]
    (if-let [after (when (seq (:projectors hardware))
                     (rig-file/use-show-setup state hardware chains projector-id))]
      (ask-if-it-grows state after event)
      {:state state})))


;; Forget this laser… (09 §7, Q-ZR-6): New Project no longer clears the
;; rig, so a laser that's gone for good is forgotten from its card

(defn- handle-request-forget
  "A disabled card's Forget this laser…: the shared confirm, in the rig's
   words, with Z2's choice of where pinned cues go (04 S-13). Forget goes on
   to :rig/forget-projector."
  [{:keys [projector-id state]}]
  (if-not (contains? (:projectors state) projector-id)
    {:state state}
    (let [pin (rig/laser-ref projector-id)
          cues (refs/cues-using (get-in state [:chains :cue-chains]) pin)]
      {:state (assoc-in state [:ui :dialogs :confirm]
                        (cond-> {:open? true
                                 :title (str "Forget " (rig/projector-name state projector-id) "?")
                                 :message (str "Its calibration and safety zone leave the rig. A scan that finds it again adds it as a new laser."
                                               (when (seq cues)
                                                 (str " " (zone-groups/plural (count cues) "cue" "cues")
                                                      (if (= 1 (count cues)) " is" " are") " pinned to it.")))
                                 :confirm-label "Forget"
                                 :danger? true
                                 :confirm-event {:event/type :rig/forget-projector :projector-id projector-id}}
                          (seq cues) (assoc :details (zone-groups/cue-list-text state cues)
                                            :choice {:key :retarget-to
                                                     :label "Send its cues to:"
                                                     :options (zone-groups/retarget-options state pin)}
                                            :choice-value :all)))})))

(defn- handle-forget-projector
  "Forget: Z0's :projectors/remove (the projector, its calibration, list and
   selection state, virtual projectors, place in the order, pins sent to
   :retarget-to), as one undo step named Forget Laser. The rig file follows
   as for any rig edit."
  [{:keys [projector-id state] :as event}]
  (if (contains? (:projectors state) projector-id)
    {:state state
     :dispatch (cond-> {:event/type :projectors/remove
                        :projector-id projector-id
                        :history/description "Forget Laser"}
                 (contains? event :retarget-to) (assoc :retarget-to (:retarget-to event)))}
    {:state state}))

(defn handle
  "Dispatch :rig/* events."
  [{:keys [event/type] :as event}]
  (case type
    :rig/move-projector (handle-move-projector event)
    :rig/nudge-projector (handle-nudge-projector event)
    :rig/set-middle (handle-set-middle event)
    :rig/set-split-width (handle-set-split-width event)
    :rig/drag-over (handle-drag-over event)
    :rig/drag-exit (handle-drag-exit event)
    :rig/set-divider (handle-set-divider event)
    ;; The rig file
    :rig/written (handle-written event)
    :rig/write-failed (handle-write-failed event)
    :rig/retry-write (handle-retry-write event)
    :rig/startup-notice (handle-startup-notice event)
    ;; A show's rig onto this one
    :rig/set-up-from-show (handle-set-up-from-show event)
    :rig/open-report (handle-open-report event)
    :rig/choose-lasers (handle-choose-lasers event)
    :rig/adopt-group (handle-adopt-group event)
    :rig/repin (handle-repin event)
    :rig/add-groups-to-show (handle-add-groups-to-show event)
    :rig/import-new-lasers (handle-import-new-lasers event)
    :rig/request-use-show-setup (handle-request-use-show-setup event)
    :rig/use-show-setup (handle-use-show-setup event)
    ;; Forget this laser…
    :rig/request-forget (handle-request-forget event)
    :rig/forget-projector (handle-forget-projector event)
    {}))
