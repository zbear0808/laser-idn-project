(ns laser-show.events.handlers.menu
  "Event handlers for menu commands (File/Edit/View/Help).
   
   Handles:
   - File menu: New, Open, Save, Save As, Exit
   - Edit menu: Undo, Redo, Copy, Paste, Clear
   - View menu: Show Preview, Fullscreen
   - Help menu: Documentation, About, IDN Stream Logging, Zone Routing Debug"
  (:require [clojure.tools.logging :as log]
            [laser-show.dev-config :as dev-config]
            [laser-show.routing.zone-effects :as ze]
            [laser-show.state.persistent :as persistent]
            [laser-show.state.rig-file :as rig-file]
            [laser-show.events.helpers :as h]
            [laser-show.events.handlers.grid :as grid]
            [laser-show.events.handlers.effects :as effects]
            [laser-show.events.handlers.history :as history]
            [laser-show.events.handlers.rig-autosave :as rig-autosave]))



;; File Menu Events


;; Unsaved-changes guard. New, Open and Exit throw the current document away, so
;; when [:project :dirty?] is true they ask first: the event is parked in
;; [:ui :dialogs :unsaved-confirm :pending] and the dialog is opened. The dialog's
;; buttons then drop it (:file/confirm-cancel), re-dispatch it with :force? true
;; (:file/confirm-discard), or save first and re-dispatch it once the write
;; succeeded (:file/confirm-save). Open's chooser comes back later, so its
;; result carries the permission it was opened with (discard-authorization),
;; and :file/project-loaded asks again when that no longer covers the show
;; open by then (may-discard?), parked as an Open of the file picked; while
;; the dialog asks something else, that Open waits behind the question
;; ([:ui :dialogs :unsaved-confirm :queued], ask-again) and comes back once
;; it is answered (then-queued). So does an Exit that comes back later: Try
;; again's, once the rig is written, and Exit's own, once it has waited for
;; the rig writer (wait-for-the-rig).

(def ^:private confirm-path [:ui :dialogs :unsaved-confirm])

(defn- guard-needed?
  "True when the event would discard unsaved work and wasn't already confirmed."
  [state {:keys [force?]}]
  (and (not force?)
       (boolean (get-in state [:project :dirty?]))))

(defn- rig-guard-needed?
  "True when Exit would quit with rig changes the rig file couldn't take
   (zones plan ZR), and that wasn't already confirmed."
  [state {:keys [rig-force?]}]
  (and (not rig-force?)
       (= :write-failed (get-in state [:rig-file :status]))))

(defn- rig-wait-needed?
  "True when Exit would quit while a rig write isn't answered yet (Saving
   rig…: [:rig-file :written] below :requested) in a window that writes the
   rig file, and it hasn't waited for the writer already (:rig-waited?) nor
   been told to go on regardless (:rig-force?)."
  [state {:keys [rig-force? rig-waited?]}]
  (and (not rig-force?)
       (not rig-waited?)
       (rig-autosave/writable? state)
       (< (get-in state [:rig-file :written] 0) (get-in state [:rig-file :requested] 0))))

(defn- parked-request
  "event as the dialog keeps it: what it is, and its :file-path when it has
   one."
  [event]
  (select-keys event [:event/type :file-path]))

(defn- ask-first
  "Open the unsaved-changes dialog for event (ignored if it is already open),
   parked with its :file-path when it has one."
  [state event]
  (if (get-in state (conj confirm-path :open?))
    {:state state}
    {:state (update-in state confirm-path assoc
                       :open? true
                       :pending (parked-request event))}))

(defn- ask-again
  "ask-first for an Open asked about again once its chooser came back
   (handle-file-project-loaded). While the dialog asks something else, it
   isn't dropped: it waits behind that question, once, in :queued, carrying
   a permission that throws away no unsaved changes, so when it comes back
   after the answer (then-queued) it opens only a show with nothing
   unsaved, and asks again otherwise."
  [state event]
  (let [{:keys [open? pending queued]} (get-in state confirm-path)
        parked (parked-request event)]
    (cond
      (not open?) (ask-first state event)
      (or (= parked (parked-request pending)) (some #(= parked (parked-request %)) queued)) {:state state}
      :else (do (log/info "Asking about" (:event/type parked) (:file-path parked) "once the question open now is answered")
                {:state (update-in state (conj confirm-path :queued) (fnil conj [])
                                   (assoc parked :authorized {:document-id (h/document-id state) :dirty? false}))}))))

(defn- then-queued
  "effects of an answer to the dialog's question, and after them the
   requests that waited in state's dialog for it (ask-again), in the order
   they came: each is sent again as it was, so it asks its own question, if
   it still has one, once the answer has done what it does."
  [effects state]
  (if-let [queued (seq (get-in state (conj confirm-path :queued)))]
    (let [{answer :dispatch} effects]
      (-> effects
          (dissoc :dispatch)
          (assoc :dispatch-n (into (if answer [answer] []) queued))))
    effects))

(defn- discard-authorization
  "The permission to throw the open show away, as it was when given (Open's
   chooser opening, Exit going on to the rig: the guard passed, or its dialog
   confirmed): which show (h/document-id), and whether it had unsaved
   changes then, which the unsaved-changes dialog had let go. Plain data,
   checked later by may-discard?."
  [state]
  {:document-id (h/document-id state)
   :dirty? (boolean (get-in state [:project :dirty?]))})

(defn- may-discard?
  "May the show open in state still be thrown away under authorized
   (discard-authorization)? Yes when it has no unsaved changes (nothing
   would be lost), or when its unsaved changes were let go for this same
   show. No when the show was replaced meanwhile by one with unsaved
   changes, or when unsaved changes came after the permission was given
   for a show without any."
  [state {:keys [document-id dirty?]}]
  (or (not (get-in state [:project :dirty?]))
      (and dirty? (= document-id (h/document-id state)))))

(defn- ask-about-the-rig
  "Open the same dialog with the rig's question (reason :rig): its last
   changes couldn't be written. Quit anyway continues with Exit, past both
   questions; Try again writes the rig, then quits. That Exit carries the
   permission the show's question gave (:authorized, the event's or now's),
   which it is checked against when it comes back."
  [state {:keys [authorized]}]
  (if (get-in state (conj confirm-path :open?))
    {:state state}
    {:state (update-in state confirm-path assoc
                       :open? true
                       :reason :rig
                       :error (get-in state [:rig-file :error])
                       :pending {:event/type :file/exit :force? true :rig-force? true
                                 :authorized (or authorized (discard-authorization state))})}))

(defn- close-confirm [state]
  (update-in state confirm-path #(-> % (assoc :open? false :pending nil) (dissoc :reason :error :queued))))

(defn- handle-file-new-project
  "Replace the document with a fresh starter project.
   Backend/runtime state (streaming, Link, MIDI/OSC) is left untouched.
   Asks first when the project has unsaved changes."
  [{:keys [state] :as event}]
  (if (guard-needed? state event)
    (ask-first state event)
    (do
      (log/info "File > New Project")
      {:state (persistent/new-project-state state)})))

(defn- handle-file-open
  "Open a project from disk using file chooser dialog.
   Asks first (before the chooser) when the project has unsaved changes.
   The chooser's result carries the permission this gave (:authorized,
   discard-authorization), for :file/project-loaded to check."
  [{:keys [state] :as event}]
  (if (guard-needed? state event)
    (ask-first state event)
    (do
      (log/info "File > Open")
      {:state state
       :fx/show-file-chooser {:title "Open Project"
                              :initial-directory (or persistent/default-projects-dir
                                                    (System/getProperty "user.home"))
                              :extension-filters [{:description "Laser Show Projects"
                                                  :extensions ["*.zip"]}]
                              :on-result {:event/type :file/open-result
                                          :authorized (discard-authorization state)}}})))

(defn- handle-file-open-result
  "Handle result from open file dialog by requesting a project read. The
   chooser's :authorized goes on to :file/project-loaded with the contents.
   An Open without one replaces the show unchecked: that is the Open the
   unsaved-changes dialog goes on with once it has just allowed it (asked
   about again: handle-file-project-loaded)."
  [{:keys [file-path authorized]}]
  (if file-path
    (do
      (log/info "Opening project:" file-path)
      {:project/load {:file-path file-path
                      :on-loaded (cond-> {:event/type :file/project-loaded}
                                   authorized (assoc :authorized authorized))
                      :on-failed {:event/type :file/project-load-failed}}})
    {}))

(defn- file-name
  "The file's name without its folders (\"show.zip\"), for a notice."
  [file-path]
  (or (some-> file-path java.io.File. .getName) "the project"))

(defn prepare-open
  "The state File > Open of contents (read-project's {filename data}) from
   file-path gives: the show replaced by the loaded project contents
   (persistent/apply-loaded-project, which checks them first). This
   machine's rig, its safety zones and calibration with it, stays as it is
   (D-30): the show's copy of a rig is only read, to match the show onto
   this one. It is kept in [:ui :dialogs :rig-report] while the show is open
   (rig-file): on a rig with no laser on, and a show made with lasers, it
   opens the set-up question (Q-ZR-3); on a rig that is set up, the report,
   when something doesn't fit (a ⚠ row: rig-file/warning-count).
   Pure, but for the opened show's new identity (a random UUID,
   persistent/begin-document). Throws when contents can't be opened: no show
   in them, or data this build can't convert or report on. Save runs it on
   what it is about to write, and discards the result."
  [state contents file-path]
  (let [loaded (persistent/apply-loaded-project state contents file-path)
        hardware (get contents (:hardware persistent/zip-filenames))
        chains (get-in contents [(:content persistent/zip-filenames) :chains :projector-effects])
        set-up? (boolean (and (not (rig-file/set-up? loaded)) (rig-file/set-up-question loaded hardware)))
        warnings? (and (not set-up?) (pos? (rig-file/warning-count (rig-file/match-report loaded hardware chains))))]
    (assoc-in loaded [:ui :dialogs :rig-report]
              {:open? (or set-up? warnings?)
               :mode (if set-up? :set-up :report)
               :file (file-name file-path)
               :hardware hardware
               :chains chains
               :pins (rig-file/pin-map state hardware)})))

(defn- handle-file-project-loaded
  "Replace the show with the loaded project contents (prepare-open). When
   they can't be opened, nothing is replaced: :file/project-load-failed
   says so, and the show, its selection and its history stay. When the
   chooser's :authorized no longer covers the show open now (may-discard?:
   it was replaced, or has unsaved changes it didn't have when the chooser
   opened), nothing is replaced either: the unsaved-changes dialog asks
   again, parked as an Open of file-path, so Don't Save and Save go on to
   that file; while it asks something else, after that question
   (ask-again)."
  [{:keys [state file-path contents authorized]}]
  (if (and authorized (not (may-discard? state authorized)))
    (do (log/info "The show changed while" (file-name file-path) "was being chosen: asking before opening it")
        (ask-again state {:event/type :file/open-result :file-path file-path}))
    (if-let [opened (try
                      (prepare-open state contents file-path)
                      (catch Exception e
                        (log/error "Project can't be opened:" file-path "-" (ex-message e))
                        nil))]
      (do (log/info "Project loaded:" file-path)
          {:state opened})
      {:dispatch {:event/type :file/project-load-failed :file-path file-path}})))

(defn- handle-file-project-load-failed
  "The project couldn't be read: the document stays, and a notice says so."
  [{:keys [file-path state]}]
  (log/error "Failed to open project:" file-path)
  (h/show-notice state (str "Could not open " (file-name file-path)) :error))

(defn- save-to
  "Request the zip write of the project to file-path. The project status
   (its file, the time, the saved baseline, the unsaved flag) changes only
   once the file is written and reads back: the :project/save effect then
   runs :project/mark-clean. The write is first opened as a dry run
   (prepare-open on the files and this rig, discarded): a show this build
   couldn't open again isn't written, and :file/save-failed says so, as it
   does when the write fails. An optional :then event on the event is
   dispatched only after the write succeeded (the unsaved-changes dialog
   uses it to continue New/Open/Exit)."
  [{:keys [state then]} file-path]
  (log/info "Saving project to:" file-path)
  (if-let [files (try
                   (let [files (persistent/project-files state)]
                     (prepare-open state files file-path)
                     files)
                   (catch Exception e
                     (log/error "Not saving" file-path "- this build couldn't open it again:" (ex-message e))
                     nil))]
    {:state state
     :project/save (cond-> {:file-path file-path
                            :files files
                            :on-failed {:event/type :file/save-failed}}
                     then (assoc :on-saved then))}
    {:state state
     :dispatch {:event/type :file/save-failed :file-path file-path}}))

(defn- handle-file-save-failed
  "The project couldn't be saved: a notice says so. Nothing else changes,
   as nothing changed before the write: the file there, the project's file,
   its saved baseline and unsaved flag are as they were."
  [{:keys [state file-path]}]
  (log/error "Failed to save project:" file-path)
  (h/show-notice state (str "Could not save " (file-name file-path)) :error))

(defn- handle-file-save-as
  "Save the project to a new location using file chooser dialog.
   An optional :then event is carried through the chooser to the write. The
   chooser's result is stamped for the show open now (h/for-document)."
  [{:keys [state then]}]
  (log/info "File > Save As")
  (let [current-file (get-in state [:project :current-file])
        initial-dir (if current-file
                      (.getParent (java.io.File. current-file))
                      (or persistent/default-projects-dir
                          (System/getProperty "user.home")))]
    {:state state
     :fx/show-file-chooser {:title "Save Project As"
                            :initial-directory initial-dir
                            :initial-file-name (or (when current-file
                                                    (.getName (java.io.File. current-file)))
                                                  (persistent/get-default-project-filename))
                            :extension-filters [{:description "Laser Show Projects"
                                                :extensions ["*.zip"]}]
                            :mode :save
                            :on-result (h/for-document (cond-> {:event/type :file/save-as-result}
                                                         then (assoc :then then))
                                                       state)}}))

(defn- handle-file-save
  "Save the current project."
  [{:keys [state] :as event}]
  (if-let [current-file (get-in state [:project :current-file])]
    (save-to event current-file)
    ;; No current file, trigger Save As
    (handle-file-save-as event)))

(defn- handle-file-save-as-result
  "Handle result from save-as file dialog. A result for a show that New or
   Open replaced while the chooser was open does nothing ({}): that show is
   gone, so nothing is written and its :then doesn't run."
  [{:keys [state file-path] :as event}]
  (cond
    (not file-path) {:state state}
    (not (h/same-document? state event)) (do (log/info "Not saving" file-path "- the show it was chosen for is no longer open")
                                             {})
    :else (save-to event file-path)))

(defn- wait-for-the-rig
  "Exit's wait for the rig writer (:rig/flush, off the event thread): the
   pending rig is written at once and the write running finishes, at most
   rig-store/flush-ms, then Exit comes back (:rig-waited?) with the
   permission the show's question gave (:authorized, the event's or now's).
   A write that failed meanwhile is asked about then (rig-guard-needed?),
   and so is an unsaved edit made meanwhile (may-discard?)."
  [state {:keys [authorized]}]
  {:rig/flush {:then {:event/type :file/exit
                      :force? true
                      :rig-waited? true
                      :authorized (or authorized (discard-authorization state))}}})

(defn- handle-file-exit
  "Exit the application. Asks first when the project has unsaved changes,
   then when the rig file couldn't take the last rig changes (zones plan
   ZR), and waits for a rig write not done yet (wait-for-the-rig). An Exit
   that comes back later (Try again's once the rig is written, or after
   that wait) carries the permission it was given (:authorized): when that
   no longer covers the show open now (may-discard?), the show's question
   is asked again, so an edit made meanwhile isn't thrown away unasked.
   Also the handler for closing the main window."
  [{:keys [state authorized] :as event}]
  (log/debug "File > Exit")
  (cond
    (and authorized (not (may-discard? state authorized))) (ask-first state {:event/type :file/exit})
    (guard-needed? state event) (ask-first state event)
    (rig-guard-needed? state event) (ask-about-the-rig state event)
    (rig-wait-needed? state event) (wait-for-the-rig state event)
    :else {:system/exit true}))


;; Unsaved-changes dialog buttons


(defn- pending-action [state]
  (get-in state (conj confirm-path :pending)))

(defn- handle-file-confirm-cancel
  "Cancel: drop the parked action; the document stays as it is. A request
   that waited for the answer comes back (then-queued)."
  [{:keys [state]}]
  (then-queued {:state (close-confirm state)} state))

(defn- handle-file-confirm-discard
  "Don't Save: carry on with the parked action, unsaved changes and all,
   then with the requests that waited for the answer (then-queued)."
  [{:keys [state]}]
  (let [pending (pending-action state)]
    (then-queued (cond-> {:state (close-confirm state)}
                   pending (assoc :dispatch (assoc pending :force? true)))
                 state)))

(defn- handle-file-confirm-save
  "Save: write the project (asking for a file name first if it has none), then
   carry on with the parked action once the write has succeeded. Cancelling the
   file chooser or a failed write ends the chain with nothing lost. The
   requests that waited for the answer come back after the save's own
   effects (then-queued)."
  [{:keys [state] :as event}]
  (let [pending (pending-action state)
        closed (close-confirm state)]
    (then-queued (if pending
                   (handle-file-save (assoc event
                                            :state closed
                                            :then (assoc pending :force? true)))
                   {:state closed})
                 state)))

(defn- handle-file-confirm-retry
  "The rig's question, Try again: write the rig now, and carry on with the
   parked Exit once it is written; that Exit asks about an edit made
   meanwhile (its :authorized). Another failure ends the chain, with the
   rig's status line saying why. The requests that waited for the answer
   come back (then-queued)."
  [{:keys [state]}]
  (let [pending (pending-action state)]
    (then-queued (cond-> {:state (close-confirm state)}
                   pending (assoc :dispatch {:event/type :rig/retry-write :then (assoc pending :force? true)}))
                 state)))


;; Edit Menu Events
;; Note: Copy/Paste/Clear need to be delegated to grid-handlers or effects-handlers
;; depending on active tab. These will be imported when handlers.clj is refactored.


(defn- handle-edit-undo
  "Undo the last action via history handler."
  [event]
  (log/debug "Edit > Undo")
  (history/handle-undo event))

(defn- handle-edit-redo
  "Redo the last undone action via history handler."
  [event]
  (log/debug "Edit > Redo")
  (history/handle-redo event))

(defn- handle-edit-copy
  "Copy selected cell to clipboard.
   Delegates to grid or effects handler depending on active tab.
   Falls back to active playing cue on grid tab if no cell is explicitly selected."
  [{:keys [state time]}]
  (let [active-tab (get-in state [:ui :active-tab])
        selected-cell (case active-tab
                        :grid (or (get-in state [:grid :selected-cell])
                                  (first (keys (get-in state [:playback :active-cues]))))
                        :effects (get-in state [:effects :selected-cell])
                        nil)]
    (if selected-cell
      (let [[col row] selected-cell]
        (log/debug "Edit > Copy: delegating to" active-tab "handler" {:col col :row row})
        (case active-tab
          :grid (grid/handle {:event/type :grid/copy-cell :col col :row row :state state :time time})
          :effects (effects/handle {:event/type :effects/copy-cell :col col :row row :state state :time time})
          {:state state}))
      (do
        (log/debug "Edit > Copy: No cell selected")
        {:state state}))))

(defn- handle-edit-paste
  "Paste clipboard to selected cell.
   Delegates to grid or effects handler depending on active tab; with
   nothing copied in this app, that cell's paste reads the OS clipboard
   first and then continues as the cell's own paste."
  [{:keys [state time]}]
  (let [active-tab (get-in state [:ui :active-tab])
        selected-cell (case active-tab
                        :grid (get-in state [:grid :selected-cell])
                        :effects (get-in state [:effects :selected-cell])
                        nil)]
    (if selected-cell
      (let [[col row] selected-cell]
        (log/debug "Edit > Paste: delegating to" active-tab "handler" {:col col :row row})
        (case active-tab
          :grid (grid/handle {:event/type :grid/paste-cell :col col :row row :state state :time time})
          :effects (effects/handle {:event/type :effects/paste-cell :col col :row row :state state :time time})
          {:state state}))
      (do
        (log/debug "Edit > Paste: No cell selected")
        {:state state}))))

(defn- handle-edit-clear-cell
  "Clear the selected cell.
   Delegates to grid or effects handler depending on active tab."
  [{:keys [state]}]
  (let [active-tab (get-in state [:ui :active-tab])
        selected-cell (case active-tab
                        :grid (get-in state [:grid :selected-cell])
                        :effects (get-in state [:effects :selected-cell])
                        nil)]
    (if selected-cell
      (let [[col row] selected-cell]
        (log/debug "Edit > Clear Cell: delegating to" active-tab "handler" {:col col :row row})
        (case active-tab
          :grid (grid/handle {:event/type :grid/clear-cell :col col :row row :state state})
          :effects (effects/handle {:event/type :effects/clear-cell :col col :row row :state state})
          {:state state}))
      (do
        (log/debug "Edit > Clear Cell: No cell selected")
        {:state state}))))


;; View Menu Events


(defn- handle-view-toggle-preview
  "Hide or show the preview panel ([:ui :preview-visible?], unset = shown).
   The tabs that never show it (root/main-content) still don't."
  [{:keys [state]}]
  (log/debug "View > Show Preview")
  {:state (update-in state [:ui :preview-visible?] (fnil not true))})

(defn- handle-view-fullscreen
  "Fullscreen on or off: the main window follows through
   :window/set-fullscreen, and :view/fullscreen-changed then stores what the
   window did."
  [{:keys [state]}]
  (log/debug "View > Fullscreen")
  (let [on? (not (true? (get-in state [:ui :fullscreen?])))]
    {:state (assoc-in state [:ui :fullscreen?] on?)
     :window/set-fullscreen on?}))

(defn- handle-view-fullscreen-changed
  "The main window went in or out of fullscreen: through View > Fullscreen
   or the OS's own control. [:ui :fullscreen?] follows the window."
  [{:keys [state fullscreen?]}]
  {:state (assoc-in state [:ui :fullscreen?] (boolean fullscreen?))})


;; Help Menu Events


(def ^:private documentation-url
  "https://github.com/zbear0808/laser-idn-project/tree/master/docs")

(defn- handle-help-documentation
  "Open documentation in browser."
  [_event]
  (log/debug "Help > Documentation")
  {:system/open-url documentation-url})

(defn- handle-help-about
  "Show About dialog."
  [{:keys [state]}]
  (log/debug "Help > About")
  {:state (assoc-in state [:ui :dialogs :about :open?] true)})

(defn- handle-help-toggle-idn-stream-logging
  "Toggle IDN stream debug logging on/off."
  [{:keys [state]}]
  (let [current (dev-config/idn-stream-logging?)
        new-value (not current)]
    (dev-config/set-idn-stream-logging! new-value)
    (log/info "IDN Stream Logging:" (if new-value "enabled" "disabled"))
    {:state (assoc-in state [:debug :idn-stream-logging?] new-value)}))

(defn- handle-help-toggle-zone-routing-debug
  "Toggle zone routing debug logging on/off.
   Logs zone routing information once per playback session when enabled."
  [{:keys [state]}]
  (let [current (get-in state [:debug :zone-routing-debug?] false)
        new-value (not current)]
    (if new-value
      (ze/enable-routing-debug!)
      (ze/disable-routing-debug!))
    (log/info "Zone Routing Debug:" (if new-value "enabled" "disabled"))
    {:state (assoc-in state [:debug :zone-routing-debug?] new-value)}))

(defn- handle-help-reload-styles
  "Reload all CSS stylesheets (dev only). The reload dispatches
   :dev/reloaded, which bumps [:styles :reload-trigger] in its own event."
  [_event]
  (log/info "Help > Reload Styles")
  {:dev/reload :styles})

(defn- handle-help-reload-app
  "Reload all app code without restarting the JVM (dev only)."
  [_event]
  (log/info "Help > Reload App Code")
  {:dev/reload :app})

(defn- handle-help-save-state-debug
  "Save current application state to an EDN file for debugging."
  [{:keys [state]}]
  (log/info "Help > Save State Debug")
  (require 'laser-show.state.core)
  (let [path ((resolve 'laser-show.state.core/save-state-debug!))]
    (log/info "State debug saved to:" path))
  {:state state})


;; Public API


(defn handle
  "Dispatch menu events to their handlers.
   
   Accepts events with :event/type in the :file/*, :edit/*, :view/*, or :help/* namespace."
  [{:keys [event/type] :as event}]
  (case type
    ;; File menu
    :file/new-project (handle-file-new-project event)
    :file/open (handle-file-open event)
    :file/open-result (handle-file-open-result event)
    :file/project-loaded (handle-file-project-loaded event)
    :file/project-load-failed (handle-file-project-load-failed event)
    :file/save (handle-file-save event)
    :file/save-as (handle-file-save-as event)
    :file/save-as-result (handle-file-save-as-result event)
    :file/save-failed (handle-file-save-failed event)
    :file/exit (handle-file-exit event)
    :file/confirm-save (handle-file-confirm-save event)
    :file/confirm-retry (handle-file-confirm-retry event)
    :file/confirm-discard (handle-file-confirm-discard event)
    :file/confirm-cancel (handle-file-confirm-cancel event)
    
    ;; Edit menu
    :edit/undo (handle-edit-undo event)
    :edit/redo (handle-edit-redo event)
    :edit/copy (handle-edit-copy event)
    :edit/paste (handle-edit-paste event)
    :edit/clear-cell (handle-edit-clear-cell event)
    
    ;; View menu
    :view/toggle-preview (handle-view-toggle-preview event)
    :view/fullscreen (handle-view-fullscreen event)
    :view/fullscreen-changed (handle-view-fullscreen-changed event)
    
    ;; Help menu
    :help/documentation (handle-help-documentation event)
    :help/about (handle-help-about event)
    :help/toggle-idn-stream-logging (handle-help-toggle-idn-stream-logging event)
    :help/toggle-zone-routing-debug (handle-help-toggle-zone-routing-debug event)
    :help/reload-styles (handle-help-reload-styles event)
    :help/reload-app (handle-help-reload-app event)
    :help/save-state-debug (handle-help-save-state-debug event)
    
    ;; Unknown event in this domain
    {}))
