(ns laser-show.events.show-lock-test
  "The show lock (plans/2026-11-18-vibe-mode/07 §7): while it is on, the
   events that would move or remove what the show is built from change
   nothing but the notice; the performer's events go on as before. Every
   event runs through the real pipeline (events/handle-event-pipeline), as
   the agent runs it."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [laser-show.events.core :as events]
            [laser-show.events.handlers :as handlers]
            [laser-show.events.handlers.history :as history-handlers]
            [laser-show.events.show-lock :as show-lock]
            [laser-show.output.driver :as driver]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.persistent :as persistent]
            [laser-show.state.templates :as templates]))

(defn- starter
  "A new project, saved (clean), streaming off."
  []
  (-> (domains/build-initial-state)
      (templates/apply-starter-cue-chains)
      (templates/apply-starter-vibes)
      (templates/apply-starter-effects)
      (assoc-in [:timing :bpm] 120.0)
      (assoc-in [:config :vibes :settings :hit-taps-tempo?] false)
      (assoc :projectors {:p1 {:name "P1" :host "10.0.0.1" :port 7255 :enabled? true :zone-groups []}
                          :p2 {:name "P2" :host "10.0.0.2" :port 7255 :enabled? true :zone-groups []}})
      (history/mark-clean)))

(defn- lock [state] (assoc-in state [:ui :show-lock?] true))

(defn- handle
  "The pipeline's effects for event on state at t."
  ([state event] (handle state event 1000))
  ([state event t] (events/handle-event-pipeline (assoc event :state state :time t))))

(defn- run
  "state after event and what it dispatches inline, as the agent runs them."
  ([state event] (run state event 1000))
  ([state event t]
   (let [effects (handle state event t)
         s (or (:state effects) state)]
     (if-let [next-event (:dispatch effects)]
       (run s next-event t)
       s))))

(defn- without-notices
  "state with no notice, as the starter state has none."
  [state]
  (update state :ui assoc :notice nil :show-lock-notice nil))

(def ^:private structure-edits
  "One real edit of each kind the lock refuses, on starter."
  [{:event/type :grid/move-cell :from-col 0 :from-row 0 :to-col 5 :to-row 2}
   {:event/type :grid/clear-cell :col 0 :row 0}
   {:event/type :effects/clear-cell :col 0 :row 3}
   {:event/type :effects/move-cell :from-col 0 :from-row 3 :to-col 4 :to-row 3}
   {:event/type :vibe/remove :vibe :vibe-1}
   {:event/type :vibe/toggle-cell :vibe :vibe-1 :slot :hit :cell [4 0]}
   {:event/type :vibe/set-option :vibe :vibe-1 :key :allow-audience? :value true}
   ;; FLASH's Solo starts on in Shapes (state/templates.clj): off is refused too
   {:event/type :vibe/set-option :vibe :vibe-1 :role :flash :key :solo? :value false}
   {:event/type :vibe/add}
   {:event/type :vibe/clear-control :role :hit}
   {:event/type :projectors/toggle-enabled :projector-id :p1}
   {:event/type :projectors/remove :projector-id :p1}
   {:event/type :zone-groups/remove :group-id :graphics}
   ;; The rig's order, its middle laser and split width, and what each laser
   ;; is in, re-route the engines (zones plan Z2)
   {:event/type :rig/move-projector :projector-id :p2 :target :p1 :side :before}
   {:event/type :rig/nudge-projector :projector-id :p1 :delta 1}
   {:event/type :rig/set-middle :middle :both}
   {:event/type :rig/set-split-width :split-width 0.6}
   {:event/type :projectors/set-enabled :projector-id :p1 :enabled? false}
   {:event/type :projectors/set-exclude-from-all :projector-id :p1 :exclude? true}
   {:event/type :projectors/set-tag :projector-id :p1 :tag :crowd-scanning :on? true}
   ;; and taking away a tag it has (locked-blocks-structure-edits-test's P1 is
   ;; tagged Graphics)
   {:event/type :projectors/set-tag :projector-id :p1 :tag :graphics :on? false}
   ;; A show's rig onto this one (zones plan ZR), from the show s0 opened
   {:event/type :rig/set-up-from-show}
   {:event/type :rig/import-new-lasers}
   {:event/type :rig/use-show-setup :projector-id :p1}
   {:event/type :rig/adopt-group :group-id :crowd :from :graphics}
   {:event/type :rig/repin :from :auto.laser/p9 :to :all}
   {:event/type :rig/forget-projector :projector-id :p2}
   ;; A column's default target and the show's re-route every cue that
   ;; inherits them, and can change which reach the audience (zones plan Z4)
   {:event/type :grid/set-column-target :col 2 :target :graphics}
   {:event/type :zones/set-default-target :target :graphics}
   ;; A zone map pad's setting and its keys, as a vibe's
   {:event/type :zone-map/set-pad-bar :map :map/odd-even :bar? true}
   {:event/type :zone-map/clear-control :map :map/odd-even}
   ;; The zones and the maps: they re-route every cue that draws into a
   ;; zone, and a lane can reach the audience (zones plan Z4, 06 §7.5)
   {:event/type :zones/set-label :zone :zone/a :label "Beams"}
   {:event/type :zone-maps/create}
   {:event/type :zone-maps/duplicate :map :map/odd-even}
   {:event/type :zone-maps/rename :map :map/custom-1 :name "Wide"}
   {:event/type :zone-maps/set-lane :map :map/custom-1 :index 1 :target :auto/right}
   {:event/type :zone-maps/add-lane :map :map/custom-1}
   {:event/type :zone-maps/remove-lane :map :map/custom-1 :index 1}
   {:event/type :zone-maps/set-override :map :map/custom-1 :zone :zone/c :target nil}
   {:event/type :zone-maps/delete :map :map/custom-1}
   ;; Where a cue, an item, a track or a clip goes (zones plan Z4): as a
   ;; column's default, they re-route it, and can make it reach the audience
   {:event/type :cue-chain/set-target :col 0 :row 0 :target :zone/a}
   {:event/type :cue-chain/set-item-target :col 0 :row 0 :item-path [0] :target :zone/b}
   {:event/type :timeline/set-track-target :col 6 :row 3 :track-id :t1 :target :zone/a}
   {:event/type :timeline/set-clip-target :col 6 :row 3 :item-id :c1 :target :graphics}
   ;; A cue's zone pattern and a new pattern cell move pictures between
   ;; places, as a target does; a pattern pad's setting and its key, as a
   ;; map pad's (zones plan Z5)
   {:event/type :zone-patterns/set-cue-pattern :col 0 :row 0 :style :chase}
   {:event/type :zone-patterns/new-cell :col 5 :row 2 :style :alternate}
   {:event/type :pattern-pads/set-pad-bar :pad :chase :bar? true}
   {:event/type :pattern-pads/clear-control :pad :chase}
   ;; A step grid's edits are a zone pattern's (zones plan Z6)
   {:event/type :step-grid/toggle :target {:domain :cue-zone-patterns :entity-key [1 0] :effect-path [0]} :row :zone/a :step 1}])

(def ^:private opened-show
  "What Open kept of friday.zip's rig (zones plan ZR): P1 with its own
   safety zone, and P9, which isn't on this rig."
  {:open? false :mode :report :file "friday.zip" :chains {} :pins nil
   :hardware {:projectors {:p1 {:name "P1 (friday)" :host "10.0.0.1" :port 7255 :enabled? true :zone-groups []
                                :safety {:limit [-0.9 -0.9 0.9 0.9] :blocked []}}
                           :p9 {:name "P9" :host "10.0.0.9" :port 7255 :enabled? true :zone-groups []}}
              :rig {:order [:p9 :p1] :middle :split :split-width 0.75}}})

(deftest locked-blocks-structure-edits-test
  (let [s0 (-> (starter)
               (assoc-in [:config :input :trigger-map {:source :midi :channel 0 :note 36}] {:type :vibe :role :hit})
               (assoc-in [:ui :dialogs :rig-report] opened-show)
               ;; a laser in Graphics, tagged Graphics, and a cue pinned to a
               ;; laser that isn't here
               (assoc-in [:projectors :p1 :zone-groups] [:graphics])
               (assoc-in [:projectors :p1 :tags] #{:graphics})
               (assoc-in [:chains :cue-chains [7 3]] {:items [] :target :auto.laser/p9})
               (assoc-in [:config :input :trigger-map {:source :midi :channel 0 :note 50}] {:type :zone-map :target :map/odd-even})
               ;; a pattern pad's key (zones plan Z5)
               (assoc-in [:config :input :trigger-map {:source :midi :channel 0 :note 51}] {:type :pattern-pad :target :chase})
               ;; a step grid on the cue at B1 (zones plan Z6)
               (assoc-in [:chains :cue-chains [1 0] :zone-patterns] [(assoc (zone-patterns/preset-instance :steps :cue) :id :g1)])
               ;; a map of yours, and a timeline cue (zones plan Z4)
               (assoc-in [:zone-maps :map/custom-1] {:name "Sweep" :lanes [:auto/left :graphics] :overrides {:zone/c :auto/center}})
               (assoc-in [:chains :cue-chains [6 3]] {:editor-type :timeline
                                                      :tracks [{:id :t1 :type :track :name "T"}]
                                                      :items [{:type :preset :id :c1 :preset-id :circle :track-id :t1
                                                               :timeline/start 0.0 :timeline/duration 4.0}]})
               (history/mark-clean))]
    (doseq [event structure-edits]
      (testing (str (:event/type event))
        (is (not= (history/extract-document-state s0) (history/extract-document-state (run s0 event)))
            "unlocked, it is a real edit")
        (let [effects (handle (lock s0) event)
              s (:state effects)]
          (is (= (lock s0) (without-notices s)) "invariant 1: locked, the only change is the notice")
          (is (= {:text (show-lock/refused-text (:event/type event)) :event (:event/type event)}
                 (get-in s [:ui :show-lock-notice])))
          (is (= (show-lock/notice-text (:event/type event)) (get-in s [:ui :notice :text])) "the status bar says it too")
          (is (= #{:state :dispatch-later} (set (keys effects))) "no other effect: the notice's timer only"))))
    (testing "asking to delete a zone map opens nothing (zones plan Z4)"
      (let [s (run (lock s0) {:event/type :zone-maps/request-delete :map :map/custom-1})]
        (is (true? (get-in (run s0 {:event/type :zone-maps/request-delete :map :map/custom-1}) [:ui :dialogs :confirm :open?]))
            "unlocked, it asks")
        (is (not (get-in s [:ui :dialogs :confirm :open?])))
        (is (= :zone-maps/request-delete (get-in s [:ui :show-lock-notice :event])))))
    (testing "undo and redo"
      (let [edited (run s0 {:event/type :grid/clear-cell :col 0 :row 0})
            locked (lock edited)]
        (is (seq (get-in edited [:history :undo-stack])))
        (doseq [type [:edit/undo :history/undo]]
          (is (= locked (without-notices (run locked {:event/type type}))) (str type)))
        (let [undone (lock (run edited {:event/type :edit/undo}))]
          (doseq [type [:edit/redo :history/redo]]
            (is (= undone (without-notices (run undone {:event/type type}))) (str type))))))
    (testing "the file: no chooser, no read, no write"
      (doseq [type [:file/new-project :file/open :file/save :file/save-as]]
        (let [effects (handle (lock (assoc-in s0 [:project :current-file] "/tmp/show.zip")) {:event/type type})]
          (is (= #{:state :dispatch-later} (set (keys effects))) (str type))))
      (is (= (lock s0) (without-notices (run (lock s0) {:event/type :file/project-loaded :file-path "/tmp/other.zip"
                                                         :contents (persistent/project-files (starter))})))
          "a read that finishes while locked replaces nothing"))))

(deftest locked-paste-asks-nothing-of-the-system-clipboard-test
  ;; Keyboard plan Phase 4 (Step 4a): with nothing copied in this app, a
  ;; paste reads the OS clipboard first (:clipboard/read). The lock refuses
  ;; the paste before its handler runs, so a locked paste reads nothing
  (let [s0 (-> (starter)
               (assoc-in [:ui :clipboard] nil)
               (assoc-in [:chains :cue-chains [1 0] :zone-patterns] [(assoc (zone-patterns/preset-instance :steps :cue) :id :g1)])
               (assoc-in [:ui :active-tab] :grid)
               (assoc-in [:grid :selected-cell] [5 2])
               (history/mark-clean))]
    (doseq [event [{:event/type :grid/paste-cell :col 5 :row 2}
                   {:event/type :effects/paste-cell :col 5 :row 2}
                   {:event/type :edit/paste}
                   {:event/type :step-grid/paste :target {:domain :cue-zone-patterns :entity-key [1 0] :effect-path [0]}}]]
      (testing (str (:event/type event))
        (is (contains? (handle s0 event) :clipboard/read) "unlocked, it asks the OS clipboard")
        (let [effects (handle (lock s0) event)]
          (is (= #{:state :dispatch-later} (set (keys effects))) "locked: the lock's notice only, no read")
          (is (= (lock s0) (without-notices (:state effects)))))))
    (testing "a paste that resumes after the read, when the lock came on meanwhile, is refused too"
      (let [effects (handle (lock s0) {:event/type :grid/paste-cell :col 5 :row 2
                                       :clipboard/system {:type :cue-chain :data {:items []} :copied-at 1}})]
        (is (= #{:state :dispatch-later} (set (keys effects))))
        (is (nil? (get-in effects [:state :chains :cue-chains [5 2]])))))))

(deftest locked-allows-performance-events-test
  (let [s0 (starter)
        ;; Stop's restart request has a random id
        norm #(update-in % [:playback :requests] (fn [r] (update-vals (or r {}) (fn [q] (dissoc q :id)))))
        same? (fn [event]
                (let [locked (run (lock s0) event)
                      unlocked (run s0 event)]
                  (= (norm locked) (norm (lock unlocked)))))]
    (doseq [event [{:event/type :vibe/press :role :hit}
                   {:event/type :vibe/release :role :hit}
                   {:event/type :vibe/press :role :blackout}
                   {:event/type :vibe/select :vibe :vibe-2}
                   {:event/type :vibe/release-all}
                   {:event/type :vibe/set-auto :auto? true}
                   {:event/type :transport/stop}
                   {:event/type :timing/set-bpm :bpm 128.0}
                   {:event/type :timing/tap-tempo}
                   {:event/type :effects/toggle-cell :col 0 :row 3}
                   {:event/type :vibe/select-for-edit :vibe :vibe-2}
                   {:event/type :ui/set-active-tab :tab-id :grid}
                   ;; A zone map switch is a performer's (zones plan Z4)
                   {:event/type :zone-map/press :map :map/odd-even}
                   {:event/type :zone-map/schedule :map :map/unison :clock {:beats 3.0 :running? false :now-ms 0}}
                   {:event/type :zone-maps/use-now :map :map/unison}
                   ;; The pattern pads, STEP and CYCLE are a performer's (zones plan Z5)
                   {:event/type :pattern-pads/press :pad :chase}
                   {:event/type :pattern-pads/schedule :pad :off :clock {:beats 3.0 :running? false :now-ms 0}}
                   {:event/type :pattern-pads/step :src :pad}
                   {:event/type :show-clock/pattern-due :id :x :style :off}
                   {:event/type :zone-map/press :map :cycle}]]
      (is (same? event) (str (:event/type event) " is the same locked or not")))
    (testing "a grid click starts its cue (its run id is random)"
      (is (some? (get-in (run (lock s0) {:event/type :grid/trigger-cell :col 0 :row 0}) [:playback :active-cues [0 0]]))))
    (testing "Stream, Play, and learning a key"
      (is (contains? (handle (lock s0) {:event/type :idn/start-multi-streaming}) :multi-engine/start))
      (is (true? (get-in (run (lock (run s0 {:event/type :transport/stop})) {:event/type :transport/play}) [:playback :playing?])))
      (is (contains? (handle (lock s0) {:event/type :input/start-learn :target-action {:type :vibe :role :hit}})
                     :input/await-learn)))
    (testing "nothing a performer does leaves a notice"
      (let [s (reduce run (lock s0) [{:event/type :vibe/press :role :hit} {:event/type :vibe/release :role :hit}
                                     {:event/type :vibe/press :role :blackout} {:event/type :vibe/press :role :blackout}])]
        (is (nil? (get-in s [:ui :show-lock-notice])))
        (is (nil? (get-in s [:ui :notice])))))))

(deftest unlock-restores-everything-test
  (let [s0 (starter)
        locked (run s0 {:event/type :vibe/set-show-lock :locked? true})]
    (is (true? (get-in locked [:ui :show-lock?])))
    (is (= s0 (assoc-in locked [:ui :show-lock?] false)) "locking changes the flag only")
    (is (empty? (get-in locked [:history :undo-stack])) "no undo step")
    (is (false? (get-in locked [:project :dirty?])) "nothing unsaved")
    (let [refused (run locked {:event/type :grid/clear-cell :col 0 :row 0})
          unlocked (run refused {:event/type :vibe/set-show-lock :locked? false})]
      (is (some? (get-in refused [:ui :show-lock-notice])))
      (is (= (assoc-in refused [:ui :show-lock-notice] nil) (assoc-in unlocked [:ui :show-lock?] true))
          "invariant 2: unlocking changes the flag and the notice, nothing else")
      (is (nil? (get-in unlocked [:ui :show-lock-notice])))
      (is (nil? (get-in (run unlocked {:event/type :grid/clear-cell :col 0 :row 0}) [:chains :cue-chains [0 0]]))
          "and the edit works again"))))

(deftest blocked-events-name-real-events-test
  (doseq [type show-lock/blocked-events]
    ;; A chooser's result without a file is the one that does nothing (cancelled)
    (let [result (try (handlers/handle-event {:event/type type :state (starter) :file-path "x.zip"})
                      (catch Throwable _ ::threw))]
      (is (not= {} result) (str type " is not routed to a handler (unknown events return {})"))))
  (testing "every vibe edit with an undo label is refused: the vibes are fixed for the show"
    (doseq [type (keys history-handlers/event-descriptions)
            :when (= "vibe" (namespace type))]
      (is (contains? show-lock/blocked-events type) (str type)))))

(deftest lock-is-not-saved-test
  (let [locked (run (starter) {:event/type :vibe/set-show-lock :locked? true})
        files (persistent/project-files locked)
        found (atom #{})]
    (walk/postwalk #(do (when (#{:show-lock? :show-lock-notice :ui} %) (swap! found conj %)) %) files)
    (is (empty? @found) "invariant 3: no [:ui] in the project's files")
    (is (false? (get-in (persistent/apply-loaded-project (starter) files "/tmp/show.zip") [:ui :show-lock?]))
        "a project opened (while unlocked: Open is refused while locked) starts unlocked")
    (is (false? (get-in (domains/build-initial-state) [:ui :show-lock?])) "and the app starts unlocked")))

(deftest exit-still-asks-test
  (let [dirty (lock (run (starter) {:event/type :vibe/rename :vibe :vibe-1 :name "Warm-up"}))]
    (is (true? (get-in dirty [:project :dirty?])))
    (let [asked (run dirty {:event/type :file/exit})]
      (is (= {:open? true :pending {:event/type :file/exit}} (get-in asked [:ui :dialogs :unsaved-confirm]))
          "Exit isn't refused: the unsaved-changes question asks")
      (is (= {:system/exit true} (handle asked {:event/type :file/exit :force? true})) "Don't Save quits")
      (testing "Save writes the project, then quits"
        (let [effects (handle (assoc-in asked [:project :current-file] "/tmp/show.zip") {:event/type :file/confirm-save})]
          (is (= "/tmp/show.zip" (get-in effects [:project/save :file-path])))
          (is (= {:event/type :file/exit :force? true} (get-in effects [:project/save :on-saved]))))))))

(deftest held-key-releases-after-the-lock-test
  (testing "invariant 8: a key and a pad held when the lock goes on still let go"
    (let [s0 (starter)
          held (-> s0
                   (run {:event/type :vibe/press :role :hit :input-time 1000} 1000)
                   (run {:event/type :vibe/press :role :accent :src :pad} 1100))
          cells (map #(get-in held [:vibe-runtime :voices % :cell]) [:hit :accent])
          locked (run held {:event/type :vibe/set-show-lock :locked? true})
          released (-> locked
                       (run {:event/type :vibe/release :role :hit :input-time 2000} 2000)
                       (run {:event/type :vibe/release :role :accent :src :pad} 2100))]
      (is (= {:hit :input :accent :pad} (update-vals (get-in locked [:vibe-runtime :held]) :src)))
      (is (every? #(some? (get-in locked [:playback :active-cues %])) cells))
      (is (empty? (get-in released [:vibe-runtime :held])))
      (is (every? #(nil? (get-in released [:playback :active-cues %])) cells) "held a second: cut on release")
      (is (nil? (get-in released [:ui :show-lock-notice]))))))

(deftest locked-safety-zones-only-get-smaller-test
  ;; Q-Z1-5 A: the lock never stands between you and a safer show, and no
  ;; area gets larger while it is on
  (let [small {:limit [-0.5 -0.5 0.5 0.5] :blocked [[-0.1 -0.1 0.1 0.1]]}
        s0 (-> (starter)
               (assoc-in [:projectors :p1 :safety] small)
               (assoc-in [:projectors :p2 :safety] {:limit [-0.9 -0.9 0.9 0.9] :blocked []})
               (history/mark-clean))
        zone #(get-in % [:projectors :p1 :safety])]
    (testing "smaller goes through"
      (doseq [event [{:event/type :projectors/set-safety :projector-id :p1
                      :safety (assoc small :limit [-0.4 -0.5 0.5 0.5])}
                     {:event/type :projectors/add-blocked-area :projector-id :p1}]]
        (let [s (run (lock s0) event)]
          (is (not= small (zone s)) (str (:event/type event) " applied"))
          (is (nil? (get-in s [:ui :show-lock-notice])) (str (:event/type event) ": no notice")))))
    (testing "larger is refused, and says why"
      (doseq [event [{:event/type :projectors/set-safety :projector-id :p1 :safety {:limit [-1 -1 1 1]}}
                     {:event/type :projectors/set-safety :projector-id :p1 :safety {:limit [-1 -1 1 1]} :confirmed? true}
                     {:event/type :projectors/remove-blocked-area :projector-id :p1 :index 0}
                     {:event/type :projectors/copy-safety :from :p2 :to [:p1]}]]
        (let [effects (handle (lock s0) event)
              s (:state effects)]
          (is (= (lock s0) (without-notices s)) (str (:event/type event) ": only the notice"))
          (is (= {:text "Larger safety zone refused" :event (:event/type event)} (get-in s [:ui :show-lock-notice])))
          (is (= "Show lock is on: Larger safety zone refused. Unlock it on the Perform tab"
                 (get-in s [:ui :notice :text]))))))
    (testing "a copy that only makes the others smaller goes through"
      (let [s (run (lock s0) {:event/type :projectors/copy-safety :from :p1 :to [:p2]})]
        (is (= small (get-in s [:projectors :p2 :safety])))))
    (testing "Allow on a question asked before the lock went on is refused too"
      (let [live (-> s0 (assoc-in [:backend :streaming :running?] true))
            asked (run live {:event/type :projectors/set-safety :projector-id :p1 :safety {:limit [-1 -1 1 1]}})
            _ (is (get-in asked [:ui :dialogs :confirm :open?]) "unlocked and live, it asks")
            allowed (run (lock asked) {:event/type :ui/confirm})]
        (is (= small (zone allowed)))
        (is (= "Larger safety zone refused" (get-in allowed [:ui :show-lock-notice :text])))))
    (testing "unlocked, a larger area on a laser that isn't streaming applies at once"
      (is (= [-1.0 -1.0 1.0 1.0] (:limit (zone (run s0 {:event/type :projectors/set-safety :projector-id :p1
                                                         :safety {:limit [-1 -1 1 1]}}))))))))

(deftest coordinate-and-reset-respect-current-review-test
  ;; plans/2026-11-23-cljfx-reactivity/08 §6, §7 examples 3-5: a coordinate
  ;; field's edit or Reset is judged by what it does to the zone stored when
  ;; it is handled; its question is its own, and its Allow applies only to
  ;; the zone it asked about
  (let [z {:limit [-0.5 -0.5 0.5 0.5] :blocked []}
        s0 (-> (starter) (assoc-in [:projectors :p1 :safety] z) (history/mark-clean))
        live (assoc-in s0 [:backend :streaming :running?] true)
        coord (fn [path value & {:as more}]
                (merge {:event/type :projectors/set-safety-coordinate :projector-id :p1 :path path :value value} more))
        reset {:event/type :projectors/reset-safety :projector-id :p1}
        zone #(get-in % [:projectors :p1 :safety])
        question #(get-in % [:ui :dialogs :confirm])
        review-id #(get-in % [:ui :dialogs :confirm :safety-review :id])
        allow (fn [id] {:event/type :ui/confirm :review-id id})
        stacks #(select-keys (:history %) [:undo-stack :redo-stack])
        changed "Safety zone changed; review the edit again"]
    (testing "smaller applies at once, live or not; larger at once on a laser that isn't on"
      (let [s (run live (coord [:limit 0] -0.4))]
        (is (= [-0.4 -0.5 0.5 0.5] (:limit (zone s))))
        (is (false? (:open? (question s)))))
      (is (= [-0.5 -0.5 0.75 0.5] (:limit (zone (run s0 (coord [:limit 2] 0.75)))))))
    (testing "example 3: a tighter zone while it asks, then the old question's Allow"
      (let [asked (run live (coord [:limit 2] 0.75))
            id (review-id asked)]
        (is (= z (zone asked)) "asking leaves the zone in force")
        (is (= {:open? true
                :title "Allow light in a larger area?"
                :message "P1 is on. Light can reach places it couldn't before."
                :confirm-label "Allow"
                :confirm-event {:event/type :projectors/set-safety-coordinate :review-id id :confirmed? true}
                :safety-review {:id id :geometry 0
                                :intent {:event/type :projectors/set-safety-coordinate :projector-id :p1
                                         :path [:limit 2] :value 0.75}}}
               (question asked)))
        (is (= (stacks s0) (stacks asked)))
        (is (= asked (run asked (coord [:limit 3] 0.8))) "a second widening while it asks is dropped")
        (let [tightened (run asked (coord [:limit 0] -0.4))
              stale (run tightened (allow id))]
          (is (= [-0.4 -0.5 0.5 0.5] (:limit (zone tightened))) "the tighter zone applies while it asks")
          (is (= (question asked) (question tightened)) "and the question stays as it was")
          (is (= [-0.4 -0.5 0.5 0.5] (:limit (zone stale))) "the old Allow writes nothing")
          (is (= {:open? false} (question stale)))
          (is (= changed (get-in stale [:ui :notice :text])))
          (is (= (stacks tightened) (stacks stale)) "and adds no undo step")
          (let [asked-again (run stale (coord [:limit 2] 0.75))
                id2 (review-id asked-again)]
            (is (not= id id2) "the edit again asks again, with a question of its own")
            (testing "the old question's buttons, and none, answer nothing"
              (doseq [event [(allow id)
                             {:event/type :ui/close-dialog :dialog-id :confirm :review-id id}
                             {:event/type :ui/confirm}
                             {:event/type :ui/close-dialog :dialog-id :confirm}
                             {:event/type :projectors/set-safety-coordinate :review-id id :confirmed? true}]]
                (is (= asked-again (run asked-again event)) (pr-str event))))
            (let [allowed (run asked-again (allow id2))]
              (is (= [-0.4 -0.5 0.75 0.5] (:limit (zone allowed))))
              (is (= {:open? false} (question allowed)))
              (is (= (inc (count (:undo-stack (stacks stale)))) (count (:undo-stack (stacks allowed)))))
              (testing "a second click on it, or its answer again, is no permission"
                (doseq [event [(allow id2) {:event/type :projectors/set-safety-coordinate :review-id id2 :confirmed? true}]]
                  (is (= allowed (run allowed event)) (pr-str event)))))))))
    (testing "Allow applies while the zone is the one it asked about, whatever else changed"
      (let [asked (run live (coord [:limit 2] 0.75))
            meanwhile (-> asked
                          (run {:event/type :ui/set-active-tab :tab-id :projectors})
                          (run {:event/type :projectors/set-safety-coordinate :projector-id :p2 :path [:limit 0] :value -0.5}))
            allowed (run meanwhile (allow (review-id asked)))]
        (is (= -0.5 (get-in meanwhile [:projectors :p2 :safety :limit 0])) "another laser's zone changed")
        (is (= [-0.5 -0.5 0.75 0.5] (:limit (zone allowed))))
        (is (= {:open? false} (question allowed)))
        (is (= "Change Safety Zone" (history/undo-label (:history allowed))))))
    (testing "Cancel discards its question: its answer is no permission after"
      (let [asked (run live (coord [:limit 2] 0.75))
            id (review-id asked)
            cancelled (run asked {:event/type :ui/close-dialog :dialog-id :confirm :review-id id})]
        (is (= {:open? false} (question cancelled)))
        (is (= z (zone cancelled)))
        (is (= (stacks s0) (stacks cancelled)))
        (doseq [event [(allow id) {:event/type :projectors/set-safety-coordinate :review-id id :confirmed? true}]]
          (is (= cancelled (run cancelled event)) (pr-str event)))
        (testing "another id, or none, closes nothing"
          (doseq [event [{:event/type :ui/close-dialog :dialog-id :confirm :review-id (inc id)}
                         {:event/type :ui/close-dialog :dialog-id :confirm}]]
            (is (= asked (run asked event)) (pr-str event))))))
    (testing "a safety question's id answers no other question"
      (let [asked (run s0 {:event/type :projectors/request-remove :projector-id :p2})]
        (is (true? (:open? (question asked))))
        (doseq [event [(allow 1) {:event/type :ui/close-dialog :dialog-id :confirm :review-id 1}]]
          (is (= asked (run asked event)) (pr-str event)))
        (is (not (contains? (:projectors (run asked {:event/type :ui/confirm})) :p2)) "its own button still does")))
    (testing ":confirmed? true on an edit is no permission: it asks"
      (let [asked (run live (coord [:limit 2] 0.75 :confirmed? true))]
        (is (= z (zone asked)))
        (is (true? (:open? (question asked)))))
      (is (= z (zone (run live (assoc reset :confirmed? true)))))
      (is (= live (run live {:event/type :projectors/set-safety-coordinate :review-id 1 :confirmed? true}))
          "and an answer to no question does nothing"))
    (testing "example 4: locked, larger is refused, outputting or not; smaller applies at once"
      (doseq [[label s] [["off" s0] ["outputting" live]]]
        (testing label
          (doseq [event [(coord [:limit 2] 0.75) (coord [:limit 2] 0.75 :confirmed? true) reset]]
            (let [effects (handle (lock s) event)
                  refused (:state effects)]
              (is (= (lock s) (without-notices refused)) (str (pr-str event) ": no question, no edit"))
              (is (= {:text "Larger safety zone refused" :event (:event/type event)} (get-in refused [:ui :show-lock-notice])))
              (is (= "Show lock is on: Larger safety zone refused. Unlock it on the Perform tab"
                     (get-in refused [:ui :notice :text])))
              (is (= #{:state :dispatch-later} (set (keys effects))) "the notice's timer only")))
          (let [smaller (run (lock s) (coord [:limit 2] 0.4))]
            (is (= [-0.5 -0.5 0.4 0.5] (:limit (zone smaller))))
            (is (nil? (get-in smaller [:ui :show-lock-notice])))
            (is (false? (:open? (question smaller))))))))
    (testing "the lock on between the question and Allow: refused, and the question closed"
      (let [asked (run live (coord [:limit 2] 0.75))
            allowed (run (lock asked) (allow (review-id asked)))]
        (is (= z (zone allowed)))
        (is (= {:open? false} (question allowed)))
        (is (= {:text "Larger safety zone refused" :event :projectors/set-safety-coordinate}
               (get-in allowed [:ui :show-lock-notice])))
        (is (= (stacks s0) (stacks allowed)))))
    (testing "example 5: a zone that can't be read"
      (let [bad {:limit [0 0]}
            s (assoc-in s0 [:projectors :p1 :safety] bad)
            asked (run (assoc-in s [:backend :streaming :running?] true) reset)]
        (is (= bad (zone (run s (coord [:limit 3] 0.3)))) "an edit leaves it as it is")
        (is (= {:limit [-1.0 -1.0 1.0 1.0] :blocked []} (zone (run s reset))) "unlocked and off, Reset puts the whole range")
        (is (= bad (zone asked)) "outputting, Reset asks first")
        (is (= {:event/type :projectors/reset-safety :review-id (review-id asked) :confirmed? true}
               (:confirm-event (question asked))))
        (is (= {:limit [-1.0 -1.0 1.0 1.0] :blocked []} (zone (run asked (allow (review-id asked))))))
        (let [refused (run (lock s) reset)]
          (is (= bad (zone refused)) "locked, Reset is refused")
          (is (= "Larger safety zone refused" (get-in refused [:ui :show-lock-notice :text]))))
        (is (= (:projectors s) (:projectors (run s {:event/type :projectors/reset-safety :projector-id :p9})))
            "a projector that isn't there stays so")))
    (testing "an Allow when the zone can't be read any more writes nothing"
      (let [asked (run live (coord [:limit 2] 0.75))
            ;; as a rig file written by hand might have it
            broken (assoc-in asked [:projectors :p1 :safety] {:limit [0 0]})
            allowed (run broken (allow (review-id asked)))]
        (is (= {:limit [0 0]} (zone allowed)))
        (is (= {:open? false} (question allowed)))))
    (testing "a blocked area's edit asked about, then Blocked 1 removed: Allow writes nothing"
      (let [two {:limit [-0.5 -0.5 0.5 0.5] :blocked [[-0.2 -0.2 0.2 0.2] [0.3 0.3 0.4 0.4]]}
            asked (run (assoc-in live [:projectors :p1 :safety] two) (coord [:blocked 1 0] 0.35 :row-version 0))
            _ (is (true? (:open? (question asked))) "a smaller blocked area lets light in: it asks")
            removed (run (assoc-in asked [:backend :streaming :running?] false)
                         {:event/type :projectors/remove-blocked-area :projector-id :p1 :index 0})
            allowed (run removed (allow (review-id asked)))]
        (is (= [[0.3 0.3 0.4 0.4]] (:blocked (zone removed))) "with the stream off the removal applies")
        (is (= [[0.3 0.3 0.4 0.4]] (:blocked (zone allowed))) "the row in its place is left alone")
        (is (= {:open? false} (question allowed)))
        (is (= changed (get-in allowed [:ui :notice :text])))))
    (testing "the projector removed, or removed and put back, while it asks: Allow writes nothing"
      (let [asked (run live (coord [:limit 2] 0.75))
            id (review-id asked)
            gone (run asked {:event/type :projectors/remove :projector-id :p1})
            back (run gone {:event/type :edit/undo})
            allowed-gone (run gone (allow id))
            allowed-back (run back (allow id))]
        (is (not (contains? (:projectors allowed-gone) :p1)) "nothing makes it again")
        (is (= {:open? false} (question allowed-gone)))
        (is (= z (zone back)) "Undo puts it back")
        (is (= z (zone allowed-back)))
        (is (= {:open? false} (question allowed-back)))
        (is (= changed (get-in allowed-back [:ui :notice :text])))))))


(deftest driver-config-edit-is-refused-while-locked-test
  (let [s0 (-> (starter)
               (assoc-in [:projectors :b] {:name "Bridge" :driver :lsps :enabled? true :zone-groups []
                                           :driver-config {:host "127.0.0.1" :port 7266 :max-packet-bytes 1400}
                                           :stable-id [:manual :lsps :b]})
               (assoc-in [:backend :output :drivers] (driver/descriptors))
               (history/mark-clean))
        event {:event/type :projectors/update-driver-config :projector-id :b :key :port :value "7300"}]
    (is (= 7300 (get-in (run s0 event) [:projectors :b :driver-config :port])) "unlocked, it applies")
    (let [s (:state (handle (lock s0) event))]
      (is (= (lock s0) (without-notices s)) "locked, the only change is the notice")
      (is (= {:text "Change Output Settings refused" :event :projectors/update-driver-config}
             (get-in s [:ui :show-lock-notice]))))))

(deftest add-projectors-submit-is-refused-while-locked-test
  ;; Reactivity plan 09 (Add): the dialog's Add, as :projectors/add-manual
  (let [s0 (-> (starter)
               (assoc-in [:ui :dialogs :add-projector-manual] {:open? true :name "Late" :host "10.0.0.9"})
               (history/mark-clean))
        event {:event/type :projectors/submit-manual-form}
        late #(some (fn [p] (= "10.0.0.9" (:host p))) (vals (:projectors %)))]
    (is (contains? show-lock/blocked-events :projectors/submit-manual-form) "next to :projectors/add-manual")
    (is (late (run s0 event)) "unlocked, it adds")
    (let [s (:state (handle (lock s0) event))]
      (is (= (lock s0) (without-notices s)) "locked, only the notice: the form stays open as typed")
      (is (= {:text "Add Projector refused" :event :projectors/submit-manual-form}
             (get-in s [:ui :show-lock-notice]))))))

(deftest refused-rig-setter-is-not-a-no-op-test
  ;; Reactivity plan 13: a setter the lock refuses changes nothing, not even
  ;; where the same edit unlocked writes the rig file and refreshes the
  ;; engines. The effects are only looked at, never run
  (let [rig-path (.getPath (java.io.File. (System/getProperty "java.io.tmpdir") "refused-rig-setter-test/rig.edn"))
        s0 (-> (starter)
               (assoc-in [:backend :streaming :running?] true)
               (assoc-in [:rig-file :path] rig-path)
               (history/mark-clean))
        off {:event/type :projectors/set-enabled :projector-id :p1 :enabled? false}
        same {:event/type :projectors/set-enabled :projector-id :p1 :enabled? true}]
    (testing "unlocked, On → Off writes the rig file and refreshes the engines"
      (let [effects (handle s0 off)]
        (is (false? (get-in effects [:state :projectors :p1 :enabled?])))
        (is (= rig-path (get-in effects [:rig/write :path])))
        (is (false? (get-in effects [:multi-engine/refresh :projectors :p1 :enabled?])))))
    (testing "unlocked, the value it has: the same state, and neither"
      (let [effects (handle s0 same)]
        (is (= s0 (:state effects)))
        (is (not-any? #(contains? effects %) [:rig/write :multi-engine/refresh]))))
    (testing "locked, both are refused: the notices and the notice's timer, nothing else"
      (doseq [event [off same]]
        (testing (pr-str (:enabled? event))
          (let [effects (handle (lock s0) event)
                s (:state effects)]
            (is (= #{:state :dispatch-later} (set (keys effects))))
            (is (= :projectors/set-enabled (get-in s [:ui :show-lock-notice :event])))
            (is (= (lock s0) (without-notices s))
                "the whole state as it was: undo and redo, the unsaved flag, the rig file's requests")
            (is (= (get-in s0 [:rig-file :requested]) (get-in s [:rig-file :requested])))
            (is (empty? (get-in s [:history :undo-stack])))
            (is (false? (get-in s [:project :dirty?])))))))))

(deftest enable-device-is-refused-while-locked-test
  (let [s0 (-> (starter)
               (assoc-in [:projectors :n1] {:name "Nova 1" :driver :acme/nova :enabled? false :zone-groups []
                                            :driver-config {} :stable-id [:device :acme/nova "NV-1" 0]})
               (history/mark-clean))
        event {:event/type :projectors/enable-device :driver :acme/nova :device-key "NV-1" :enabled? true}]
    (is (contains? show-lock/blocked-events :projectors/enable-device) "next to :projectors/enable-all-by-ip")
    (is (true? (get-in (run s0 event) [:projectors :n1 :enabled?])) "unlocked, it applies")
    (let [s (:state (handle (lock s0) event))]
      (is (= (lock s0) (without-notices s)) "locked, the only change is the notice")
      (is (= {:text "Turn Device On or Off refused" :event :projectors/enable-device}
             (get-in s [:ui :show-lock-notice]))))))

(deftest locked-zone-pattern-edits-are-refused-test
  ;; Zones plan Z5: a pattern's or a Map cycle's edits are structure, as a
  ;; target's are (Z4-Q4 A): where the pictures go. The pattern window edits
  ;; a cue's through the chain events on :cue-zone-patterns, the effects
  ;; grid's editor a cell's on :effect-chains
  (let [pattern (assoc (zone-patterns/preset-instance :chase :cue) :id :p1)
        s0 (-> (starter)
               (assoc-in [:chains :cue-chains [0 0] :zone-patterns] [pattern])
               (assoc-in [:chains :effect-chains [4 2]] {:active? false :items [(assoc (zone-patterns/preset-instance :swap :cell) :id :p2)]})
               (assoc-in [:chains :effect-chains [5 2]] {:active? false :items [(assoc (zone-patterns/cycle-instance) :id :c1)]})
               (history/mark-clean))
        refused [{:event/type :chain/update-param :domain :cue-zone-patterns :entity-key [0 0] :effect-path [0] :param-key :rate :value 2.0}
                 {:event/type :chain/set-items :domain :cue-zone-patterns :entity-key [0 0] :items []}
                 {:event/type :chain/update-param :domain :effect-chains :entity-key [4 2] :effect-path [0] :param-key :zones :value [:zone/a]}
                 {:event/type :chain/update-param :domain :effect-chains :entity-key [5 2] :effect-path [0] :param-key :rate :value 1.0}
                 {:event/type :modulator/toggle :domain :cue-zone-patterns :entity-key [0 0] :effect-path [0] :param-key :offset
                  :param-spec {:min -64.0 :max 64.0} :fx/event true}
                 ;; a step grid's steps, edited as any parameter (zones plan Z6)
                 {:event/type :chain/update-param :domain :cue-zone-patterns :entity-key [0 0] :effect-path [0] :param-key :steps
                  :value [[:zone/a] []]}]]
    (doseq [event refused]
      (testing (pr-str (select-keys event [:event/type :domain :entity-key]))
        (is (not= (history/extract-document-state s0) (history/extract-document-state (run s0 event))) "unlocked, a real edit")
        (let [s (run (lock s0) event)]
          (is (= (lock s0) (without-notices s)) "locked, only the notice")
          (is (= {:text "Zone pattern edit refused" :event (:event/type event)} (get-in s [:ui :show-lock-notice]))))))
    (testing "a point effect's edit in a cell goes through, as before"
      (let [s (run (lock s0) {:event/type :chain/update-param :domain :effect-chains :entity-key [0 3] :effect-path [0]
                              :param-key :amount :value 0.5})]
        (is (nil? (get-in s [:ui :show-lock-notice])))
        (is (= 0.5 (get-in s [:chains :effect-chains [0 3] :items 0 :params :amount])))))
    (testing "switching a pattern cell on is a performer's"
      (is (true? (get-in (run (lock s0) {:event/type :effects/toggle-cell :col 4 :row 2}) [:chains :effect-chains [4 2] :active?]))))
    (testing "a step grid's own edits, on a cell's grid too, are refused with their label (zones plan Z6)"
      (let [s1 (-> s0
                   (assoc-in [:chains :effect-chains [6 2]] {:active? true :items [(assoc (zone-patterns/preset-instance :steps :cell) :id :g2)]})
                   (history/mark-clean))
            target {:domain :effect-chains :entity-key [6 2] :effect-path [0]}]
        (doseq [event [{:event/type :step-grid/set-length :target target :n 16}
                       {:event/type :step-grid/paint-start :target target :row 0 :step 1}
                       {:event/type :step-grid/start-from :target target :style :all-on}
                       {:event/type :step-grid/paste :target target}]]
          (testing (str (:event/type event))
            (let [s1 (assoc-in s1 [:ui :clipboard] {:type :step-grid :grid {:zones [:zone/c] :steps [[:zone/c]]} :copied-at 1})]
              (is (not= (history/extract-document-state s1) (history/extract-document-state (run s1 event))) "unlocked, a real edit")
              (let [s (run (lock s1) event)]
                (is (= (lock s1) (without-notices s)) "locked, only the notice")
                (is (= {:text (show-lock/refused-text (:event/type event)) :event (:event/type event)}
                       (get-in s [:ui :show-lock-notice])))))))))))

(deftest locked-step-grid-opens-and-a-drag-lets-go-test
  ;; Zones plan Z6: the grid's window, its cursor and Copy are no edits, and
  ;; a drag held when the lock goes on still lets go, as a held key does
  (let [chase-target {:domain :cue-zone-patterns :entity-key [0 0] :effect-path [0]}
        grid-target {:domain :cue-zone-patterns :entity-key [1 0] :effect-path [0]}
        s0 (-> (starter)
               (assoc-in [:chains :cue-chains [0 0] :zone-patterns] [(assoc (zone-patterns/preset-instance :chase :cue) :id :p1)])
               (assoc-in [:chains :cue-chains [1 0] :zone-patterns] [(assoc (zone-patterns/preset-instance :steps :cue) :id :g1)])
               (history/mark-clean))]
    (testing "opening a pattern's grid while locked shows it and makes nothing: no grid, no notice"
      (let [s (run (lock s0) {:event/type :step-grid/open :target chase-target})]
        (is (true? (get-in s [:ui :dialogs :step-grid-editor :open?])))
        (is (= :chase (get-in s [:chains :cue-chains [0 0] :zone-patterns 0 :params :style])))
        (is (= (history/extract-document-state s0) (history/extract-document-state s)))
        (is (nil? (get-in s [:ui :show-lock-notice])))))
    (testing "the cursor and Copy go through"
      (let [s (-> (lock s0)
                  (run {:event/type :step-grid/open :target grid-target})
                  (run {:event/type :step-grid/cursor :target grid-target :move [0 2]})
                  (run {:event/type :step-grid/copy :target grid-target}))]
        (is (= [0 2] (get-in s [:ui :dialogs :step-grid-editor :cursor])))
        (is (= 8 (count (get-in s [:ui :clipboard :grid :steps]))))
        (is (nil? (get-in s [:ui :show-lock-notice])))))
    (testing "a drag held when the lock goes on: its paint is refused, its release still commits it"
      (let [held (run s0 {:event/type :step-grid/paint-start :target grid-target :row 1 :step 0})
            locked (run held {:event/type :vibe/set-show-lock :locked? true})
            painted (run locked {:event/type :step-grid/paint :target grid-target :row 1 :step 2})
            released (run painted {:event/type :step-grid/paint-end :target grid-target})]
        (is (some? (get-in locked [:history :transaction])))
        (is (= "Edit Step Grid refused" (get-in painted [:ui :show-lock-notice :text])))
        (is (nil? (get-in released [:history :transaction])) "it let go")
        (is (nil? (get-in released [:ui :step-grid :drag])))
        (is (= 1 (count (get-in released [:history :undo-stack]))) "what was painted before the lock is one undo step")))))
