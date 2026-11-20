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
   ;; A show's rig onto this one (zones plan ZR), from the show s0 opened
   {:event/type :rig/set-up-from-show}
   {:event/type :rig/import-new-lasers}
   {:event/type :rig/use-show-setup :projector-id :p1}
   {:event/type :rig/adopt-group :group-id :crowd :from :graphics}
   {:event/type :rig/repin :from :auto.laser/p9 :to :all}
   {:event/type :rig/forget-projector :projector-id :p2}])

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
               ;; a laser in Graphics, and a cue pinned to a laser that isn't here
               (assoc-in [:projectors :p1 :zone-groups] [:graphics])
               (assoc-in [:chains :cue-chains [7 3]] {:items [] :destination-zone {:zone-group-id :auto.laser/p9}})
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
                   {:event/type :ui/set-active-tab :tab-id :grid}]]
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

