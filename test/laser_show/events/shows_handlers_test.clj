(ns laser-show.events.shows-handlers-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [laser-show.events.core :as events]
            [laser-show.events.handlers :as handlers]
            [laser-show.events.handlers.shows :as shows-handlers]
            [laser-show.events.handlers.timeline :as timeline-handlers]
            [laser-show.events.helpers :as h]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.persistent :as persistent]
            [laser-show.state.templates :as templates]
            [laser-show.timeline.model :as model]
            [laser-show.timeline.shows :as shows]))

(def u #uuid "00000000-0000-0000-0000-0000000000a1")
(def v #uuid "00000000-0000-0000-0000-0000000000a2")
(def t1 #uuid "00000000-0000-0000-0000-0000000000b1")
(def c1 #uuid "00000000-0000-0000-0000-0000000000c1")

(def a-show
  "A normalized show with one clip."
  {:name "One More Time"
   :editor-type :timeline
   :timeline/tempo-bpm 120.0
   :tracks [{:id t1 :type :track :name "Track 1" :collapsed? false}]
   :items [{:id c1 :type :preset :preset-id :circle :enabled? true :effects []
            :track-id t1 :timeline/start 0.0 :timeline/duration 8.0}]})

(def follower (assoc a-show :timeline/chase :mtc))

(defn- base []
  (-> (domains/build-initial-state)
      (templates/apply-starter-cue-chains)
      (assoc-in [:timing :bpm] 120.0)))

(defn- with-shows [state m]
  (assoc-in state [:chains :shows] m))

(defn- handle [state event]
  (handlers/handle-event (assoc event :state state)))

(def pipeline events/handle-event-pipeline)

(defn- run
  "Run an event through the pipeline, then any :dispatch it returns (like the agent would)."
  [state event]
  (let [effects (pipeline (assoc event :state state))
        s (or (:state effects) state)]
    (if-let [next-event (:dispatch effects)]
      (run s next-event)
      s)))

(defn- undo-count [s] (count (get-in s [:history :undo-stack])))

(defn- intent [state k] (get-in state [:playback :active-cues k]))

(defn- uuids [x]
  (let [found (volatile! #{})]
    (walk/postwalk #(do (when (uuid? %) (vswap! found conj %)) %) x)
    @found))


;; Library

(deftest create-test
  (let [result (handle (base) {:event/type :shows/create :title "  One More Time "})
        library (get-in result [:state :chains :shows])
        [id show] (first library)]
    (is (= 1 (count library)))
    (is (uuid? id))
    (is (= "One More Time" (:name show)) "the title, trimmed")
    (is (= :timeline (:editor-type show)))
    (is (not (contains? show :id)) "a show's id is only its key")
    (is (not (contains? show :timeline/chase)) "timecode off")
    (is (seq (:tracks show)) "a track to add clips to")
    (is (empty? (:items show)))
    (is (identical? show (model/normalize-timeline-cue show)) "normalized")
    (is (= #{id} (get-in result [:state :ui :shows :selected])) "selected"))
  (testing "no title: Untitled"
    (is (= ["Untitled" "Untitled"]
           (map #(-> (handle (base) %) :state :chains :shows vals first :name)
                [{:event/type :shows/create} {:event/type :shows/create :title " "}]))))
  (testing "through the pipeline: one undo step, New Show, unsaved"
    (let [s0 (history/mark-clean (base))
          s1 (run s0 {:event/type :shows/create})]
      (is (= 1 (undo-count s1)))
      (is (= "New Show" (history/undo-label (:history s1))))
      (is (true? (get-in s1 [:project :dirty?])))
      (is (= {} (get-in (run s1 {:event/type :edit/undo}) [:chains :shows]))))))

(deftest open-editor-test
  (let [s (with-shows (base) {u a-show})
        result (handle s {:event/type :shows/open-editor :show-id u})]
    (testing "a normalized show: the document isn't touched"
      (is (identical? (get-in s [:chains :shows]) (get-in result [:state :chains :shows])))
      (is (not (contains? result :history/backfill)))
      (is (not (contains? result :history/description))))
    (testing "the dialog data names the show"
      (is (= {:event/type :ui/open-dialog
              :dialog-id :timeline-cue-editor
              :data {:show-id u :editing-name? false}}
             (:dispatch result)))
      (let [opened (run s {:event/type :shows/open-editor :show-id u})]
        (is (= {:open? true :show-id u :editing-name? false}
               (select-keys (get-in opened [:ui :dialogs :timeline-cue-editor]) [:open? :show-id :editing-name?])))))
    (testing "a show with clips leaves the browser as it was"
      (is (not (true? (get-in result [:state :ui :timeline-prefs :add-panel :open?]))))))
  (testing "an empty show opens with the browser"
    (let [s (-> (with-shows (base) {u (shows/new-show {:name "Empty"})})
                (assoc-in [:ui :timeline-prefs :add-panel :open?] false))]
      (is (true? (get-in (handle s {:event/type :shows/open-editor :show-id u})
                         [:state :ui :timeline-prefs :add-panel :open?])))))
  (testing "the effect UI goes when it opens on another show, and stays for the same one"
    (let [ui {:items {:ui-modes {[0] :numeric}}}
          s (-> (with-shows (base) {u a-show v a-show})
                (assoc-in [:ui :dialogs :timeline-cue-editor] {:open? true :show-id u :effect-ui ui}))]
      (is (= ui (get-in (run s {:event/type :shows/open-editor :show-id u})
                        [:ui :dialogs :timeline-cue-editor :effect-ui])))
      (is (nil? (get-in (run s {:event/type :shows/open-editor :show-id v})
                        [:ui :dialogs :timeline-cue-editor :effect-ui])))))
  (testing "a show that skipped normalization: normalized as a backfill, no undo step, not unsaved"
    (let [raw (update a-show :items (fn [items] (mapv #(dissoc % :track-id) items)))
          s0 (history/mark-clean (with-shows (base) {u raw}))
          result (handle s0 {:event/type :shows/open-editor :show-id u})
          s1 (run s0 {:event/type :shows/open-editor :show-id u})]
      (is (fn? (:history/backfill result)))
      (is (= t1 (get-in s1 [:chains :shows u :items 0 :track-id])))
      (is (= 0 (undo-count s1)))
      (is (false? (get-in s1 [:project :dirty?])))))
  (testing "a missing show: nothing"
    (let [s (base)]
      (is (= {:state s} (handle s {:event/type :shows/open-editor :show-id u}))))))

(deftest new-from-cue-copies-and-keeps-cell-test
  (let [s0 (history/mark-clean (base))
        cell (get-in s0 [:chains :cue-chains [0 0]])
        s1 (run s0 {:event/type :shows/new-from-cue :col 0 :row 0})
        [id show] (first (get-in s1 [:chains :shows]))]
    (is (some? cell) "the starter cue at A1")
    (is (= 1 (count (get-in s1 [:chains :shows]))))
    (testing "the cell is untouched"
      (is (identical? (get-in s0 [:chains :cue-chains]) (get-in s1 [:chains :cue-chains]))))
    (testing "the show is a converted copy"
      (is (= :timeline (:editor-type show)))
      (is (not-any? #(contains? show %) [:id :trigger-mode :timeline/chase]))
      (is (= (count (:items cell)) (count (:items show))))
      (is (every? :track-id (:items show)))
      (is (identical? show (model/normalize-timeline-cue show)))
      (is (empty? (set/intersection (uuids cell) (uuids show))) "new ids throughout"))
    (testing "one undo step, New Show from Cue"
      (is (= 1 (undo-count s1)))
      (is (= "New Show from Cue" (history/undo-label (:history s1))))
      (is (true? (get-in s1 [:project :dirty?])))
      (let [undone (run s1 {:event/type :edit/undo})]
        (is (= {} (get-in undone [:chains :shows])))
        (is (= cell (get-in undone [:chains :cue-chains [0 0]])))))
    (testing "the Shows tab, the show selected, its editor open"
      (is (= :shows (get-in s1 [:ui :active-tab])))
      (is (= #{id} (get-in s1 [:ui :shows :selected])))
      (is (= [true id] ((juxt :open? :show-id) (get-in s1 [:ui :dialogs :timeline-cue-editor])))))))

(deftest new-from-cue-names-and-targets-test
  (let [s (-> (base)
              (assoc-in [:chains :cue-chains [3 1]] {:items [{:id c1 :type :preset :preset-id :circle}]})
              (assoc-in [:grid :column-targets 3] :zone/a))
        show-of #(-> (handle %1 {:event/type :shows/new-from-cue :col %2 :row %3}) :state :chains :shows vals first)]
    (testing "a cue with no target takes its column's default, so it draws where it did"
      (is (= :zone/a (:target (show-of s 3 1)))))
    (testing "its own target stays"
      (is (= :zone/b (:target (show-of (assoc-in s [:chains :cue-chains [3 1] :target] :zone/b) 3 1)))))
    (testing "no name: Untitled; a name: kept"
      (is (= "Untitled" (:name (show-of s 3 1))))
      (is (= "Bounce" (:name (show-of (assoc-in s [:chains :cue-chains [3 1] :name] "Bounce") 3 1)))))
    (testing "a plain cue loops over its clips, as the conversion makes it"
      (is (= {:enabled? true :start 0.0 :duration 16.0} (:loop (show-of s 3 1)))))))


;; Playing

(defn- grid-state
  "A timeline at grid cell [0 0] toggled as a cue, beside the same show u."
  [cue]
  (-> (base)
      (assoc-in [:config :cue :trigger-mode] :default)
      (assoc-in [:chains :cue-chains [0 0]] (assoc cue :trigger-mode :toggle))
      (with-shows {u cue})
      (assoc-in [:playback :active-cues] {})
      (assoc-in [:playback :playing?] false)))

(defn- outcome
  "The playback a key k shows: its intent (the run as :run), its chase stop
   and the pulse."
  [state k]
  {:intent (some-> (intent state k) (update :run #(when (uuid? %) :run)))
   :chase-stopped (get-in state [:playback :chase-stopped k] ::none)
   :playing? (get-in state [:playback :playing?])
   :pulse (get-in state [:playback :pulses :cue])})

(defn- press [state] (:state (handle state {:event/type :grid/trigger-cell :col 0 :row 0})))
(defn- toggle [state] (:state (handle state {:event/type :shows/toggle-run :show-id u})))

(deftest toggle-run-mirrors-a-cell-press-test
  (let [k (shows/play-key u)
        same (fn [what setup cue]
               (testing what
                 (let [s (grid-state cue)
                       grid (setup s [0 0])
                       show (setup s k)]
                   (is (= (outcome (press grid) [0 0]) (outcome (toggle show) k))))))]
    (same "idle: starts at beat 0" (fn [s _] s) a-show)
    (same "playing: stops" (fn [s k] (assoc-in s [:playback :active-cues k] {:run (random-uuid) :timeline? true :start-beat 0.0})) a-show)
    (same "chased: stopped for the episode"
          (fn [s k] (-> (assoc-in s [:playback :active-cues k] {:chasing? true :timeline? true :chase-id [:d :ep 0 nil 1]})
                        (assoc-in [:playback :playing?] true)))
          follower)
    (same "stopped for an episode: follows again"
          (fn [s k] (assoc-in s [:playback :chase-stopped k] [:d :ep 0 nil 1]))
          follower)
    (same "a follower timecode doesn't drive: left alone" (fn [s _] s) follower))
  (testing "the outcomes themselves"
    (let [k (shows/play-key u)
          s (grid-state a-show)
          started (toggle s)
          stopped (toggle started)
          episode [:d k 0 nil 1]
          chased (-> (grid-state follower)
                     (assoc-in [:playback :active-cues k] {:chasing? true :timeline? true :chase-id episode}))
          held (toggle chased)
          again (toggle held)]
      (is (= {:intent {:run :run :timeline? true :start-beat 0.0} :chase-stopped ::none :playing? true :pulse {:id 1}}
             (outcome started k)))
      (is (nil? (intent stopped k)))
      (is (nil? (intent held k)))
      (is (= episode (get-in held [:playback :chase-stopped k])))
      (is (not (contains? (get-in again [:playback :chase-stopped]) k)))
      (is (nil? (intent again k)) "the clock takes it over at its next tick")
      (is (nil? (get-in started [:ui :shows :manual])) "a pad plays its own show: :manual is left alone")
      (is (nil? (intent started [0 0])) "the grid cell isn't touched")))
  (testing "a missing show or none: nothing"
    (let [s (base)]
      (is (= {:state s} (handle s {:event/type :shows/toggle-run :show-id v})))
      (is (= {:state s} (handle s {:event/type :shows/toggle-run}))))))

(defn- play-state []
  (-> (base)
      (with-shows {u a-show v a-show})
      (assoc-in [:playback :active-cues] {})
      (assoc-in [:playback :playing?] false)))

(defn- play [state id] (handle state {:event/type :shows/play :show-id id}))

(deftest play-rules-test
  (let [ku (shows/play-key u)
        kv (shows/play-key v)
        grid-intent {:run (random-uuid)}]
    (testing ":manual nil, grid [0 0] playing: both play, :manual u"
      (let [s (-> (play-state)
                  (assoc-in [:playback :active-cues [0 0]] grid-intent)
                  (assoc-in [:playback :playing?] true))
            s' (:state (play s u))]
        (is (= grid-intent (intent s' [0 0])))
        (is (= {:timeline? true :start-beat 0.0} (dissoc (intent s' ku) :run)))
        (is (= u (get-in s' [:ui :shows :manual])))))
    (testing ":manual u playing: play v stops u, starts v from beat 0, :manual v"
      (let [s (:state (play (play-state) u))
            s' (:state (play s v))]
        (is (nil? (intent s' ku)))
        (is (= {:timeline? true :start-beat 0.0} (dissoc (intent s' kv) :run)))
        (is (= v (get-in s' [:ui :shows :manual])))))
    (testing "the show playing, played again: a new run from beat 0"
      (let [s (:state (play (play-state) u))
            s' (:state (play s u))]
        (is (not= (:run (intent s ku)) (:run (intent s' ku))))
        (is (= 0.0 (:start-beat (intent s' ku))))))
    (testing "v enabled: refused with a notice, nothing else changes"
      (let [s (with-shows (play-state) {u a-show v follower})
            result (play s v)]
        (is (= "Follows timecode. Untick TC to play it by hand." (get-in result [:state :ui :notice :text])))
        (is (some? (:dispatch-later result)))
        (is (= (:playback s) (get-in result [:state :playback])))
        (is (= (get-in s [:ui :shows]) (get-in result [:state :ui :shows])))))
    (testing ":manual driven by timecode now: its chase is left alone"
      (let [marker {:chasing? true :timeline? true :chase-id [:d ku 0 nil 1]}
            s (-> (with-shows (play-state) {u follower v a-show})
                  (assoc-in [:ui :shows :manual] u)
                  (assoc-in [:playback :active-cues ku] marker))
            s' (:state (play s v))]
        (is (= marker (intent s' ku)))
        (is (some? (intent s' kv)))))
    (testing "a held transport resumes, its cues with it"
      (let [s (-> (play-state)
                  (assoc-in [:playback :active-cues [0 0]] grid-intent)
                  (assoc-in [:playback :playing?] false))
            s' (:state (play s u))]
        (is (true? (get-in s' [:playback :playing?])))
        (is (= #{[0 0] ku} (set (keys (get-in s' [:playback :active-cues])))))))
    (testing "a missing show: nothing"
      (let [s (play-state)]
        (is (= {:state s} (play s #uuid "00000000-0000-0000-0000-0000000000ff")))))))

(deftest stop-test
  (let [ku (shows/play-key u)]
    (testing "stops the show played by hand; :manual stays"
      (let [s (-> (play-state)
                  (assoc-in [:playback :active-cues [0 0]] {:run (random-uuid)})
                  (play u) :state)
            s' (:state (handle s {:event/type :shows/stop}))]
        (is (nil? (intent s' ku)))
        (is (some? (intent s' [0 0])) "grid cues play on")
        (is (= u (get-in s' [:ui :shows :manual])))))
    (testing ":manual driven by timecode: left alone"
      (let [s (-> (with-shows (play-state) {u follower})
                  (assoc-in [:ui :shows :manual] u)
                  (assoc-in [:playback :active-cues ku] {:chasing? true :timeline? true}))]
        (is (= {:state s} (handle s {:event/type :shows/stop})))))
    (testing "nothing played by hand: nothing"
      (let [s (play-state)]
        (is (= {:state s} (handle s {:event/type :shows/stop})))))))

(deftest bare-events-return-state-test
  (doseq [type [:shows/create :shows/open-editor :shows/new-from-cue :shows/toggle-run
                :shows/play :shows/stop]]
    (let [s (base)
          result (shows-handlers/handle {:event/type type :state s})]
      (is (contains? result :state) (str type))
      (is (not= {} result) (str type))
      (is (contains? (handlers/handle-event {:event/type type :state s}) :state)
          (str type " is routed to the shows handler"))))
  (testing "an unknown shows event does nothing"
    (is (= {} (shows-handlers/handle {:event/type :shows/nope :state (base)})))))


;; Timecode (plans/2026-11-25-timeline-shows 01 §4, 07 §4.2, §4.5, §8)

(def sa #uuid "00000000-0000-0000-0000-0000000000d1")
(def sb #uuid "00000000-0000-0000-0000-0000000000d2")
(def sc #uuid "00000000-0000-0000-0000-0000000000d3")
(def ca #uuid "00000000-0000-0000-0000-0000000000e1")
(def cb #uuid "00000000-0000-0000-0000-0000000000e2")
(def cc #uuid "00000000-0000-0000-0000-0000000000e3")

(defn- tc-show
  "Show title: one clip clip-id of beats beats, locked at 120 BPM (2 beats
   a second), 25 fps, beat 0 at tc-start seconds."
  [title clip-id tc-start beats & {:as extra}]
  (merge {:name title
          :editor-type :timeline
          :timeline/tempo-bpm 120.0
          :timeline/tc-fps 25
          :timeline/tc-start tc-start
          :tracks [{:id t1 :type :track :name "Track 1" :collapsed? false}]
          :items [{:id clip-id :type :preset :preset-id :circle :enabled? true :effects []
                   :track-id t1 :timeline/start 0.0 :timeline/duration beats}]}
         extra))

(defn- on [show] (assoc show :timeline/chase :mtc))

(defn- tc-state
  "base with library, saved, nothing playing."
  [library]
  (-> (base)
      (with-shows library)
      (assoc-in [:playback :active-cues] {})
      (assoc-in [:playback :playing?] false)
      (history/mark-clean)))

(defn- set-tc [state ids enabled?]
  (pipeline {:event/type :shows/set-timecode :show-ids ids :enabled? enabled? :state state}))

(defn- chase-of [state id] (get-in state [:chains :shows id :timeline/chase]))

(deftest set-timecode-examples-test
  (testing "A on [0, 300): B [300, 600) goes on, the ends touch; one step, Enable Timecode"
    (let [s0 (tc-state {sa (on (tc-show "A" ca 0.0 600.0)) sb (tc-show "B" cb 300.0 600.0)})
          result (set-tc s0 #{sb} true)
          s1 (:state result)]
      (is (= :mtc (chase-of s1 sb)))
      (is (not (contains? result :dispatch-later)) "no notice")
      (is (= 1 (undo-count s1)))
      (is (= "Enable Timecode" (history/undo-label (:history s1))))
      (is (true? (get-in s1 [:project :dirty?])))))
  (testing "Trace 3: A on [0, 300), B [299, 600): refused, the document identical, no step"
    (let [s0 (tc-state {sa (on (tc-show "A" ca 0.0 600.0)) sb (tc-show "B" cb 299.0 602.0)})
          result (set-tc s0 #{sb} true)
          s1 (:state result)]
      (is (identical? (get-in s0 [:chains :shows]) (get-in s1 [:chains :shows])))
      (is (= {:text "'B' overlaps 'A' (00:00:00:00–00:05:00:00)" :level :warning}
             (select-keys (get-in s1 [:ui :notice]) [:text :level])))
      (is (= {:event {:event/type :ui/clear-notice :id (get-in s1 [:ui :notice :id])} :delay-ms 6000}
             (:dispatch-later result)))
      (is (= 0 (undo-count s1)))
      (is (false? (get-in s1 [:project :dirty?])))))
  (testing "none on: A [0, 300) and B [100, 400) asked together: A on, B refused, overlaps A"
    (let [s0 (tc-state {sa (tc-show "A" ca 0.0 600.0) sb (tc-show "B" cb 100.0 600.0)})
          result (set-tc s0 #{sa sb} true)
          s1 (:state result)]
      (is (= [:mtc nil] [(chase-of s1 sa) (chase-of s1 sb)]))
      (is (= "'B' overlaps 'A' (00:00:00:00–00:05:00:00)" (get-in s1 [:ui :notice :text])))
      (is (= "Enable Timecode" (history/undo-label (:history s1))))
      (is (= 1 (undo-count s1)))))
  (testing "the table's order decides which of two goes on: B first when sorted by title, descending"
    (let [s0 (-> (tc-state {sa (tc-show "A" ca 0.0 600.0) sb (tc-show "B" cb 100.0 600.0)})
                 (assoc-in [:ui :shows :sort] {:column :title :dir :desc}))
          s1 (:state (set-tc s0 #{sa sb} true))]
      (is (= [nil :mtc] [(chase-of s1 sa) (chase-of s1 sb)]))
      (is (= "'A' overlaps 'B' (00:01:40:00–00:06:40:00)" (get-in s1 [:ui :notice :text])))))
  (testing "a show the table filters out comes after the shown ones"
    (let [s0 (-> (tc-state {sa (tc-show "A" ca 0.0 600.0) sb (tc-show "B" cb 100.0 600.0)})
                 (assoc-in [:ui :shows :query] "B"))
          s1 (:state (set-tc s0 #{sa sb} true))]
      (is (= [nil :mtc] [(chase-of s1 sa) (chase-of s1 sb)]))))
  (testing "C with its tempo unlocked: refused, needs a BPM"
    (let [s0 (tc-state {sc (dissoc (tc-show "C" cc 0.0 8.0) :timeline/tempo-bpm)})
          s1 (:state (set-tc s0 #{sc} true))]
      (is (nil? (chase-of s1 sc)))
      (is (= "'C' needs a BPM to follow timecode" (get-in s1 [:ui :notice :text])))))
  (testing "an empty show: refused"
    (let [s0 (tc-state {sc (shows/new-show {:name "C"})})
          s1 (:state (set-tc s0 #{sc} true))]
      (is (= "'C' needs a BPM to follow timecode" (get-in s1 [:ui :notice :text])) "the BPM is checked first")
      (let [s1 (:state (set-tc (assoc-in s0 [:chains :shows sc :timeline/tempo-bpm] 120.0) #{sc} true))]
        (is (nil? (chase-of s1 sc)))
        (is (= "'C' is empty" (get-in s1 [:ui :notice :text]))))))
  (testing "several refused: one notice naming each"
    (let [s0 (tc-state {sa (on (tc-show "A" ca 0.0 600.0))
                        sb (tc-show "B" cb 100.0 600.0)
                        sc (dissoc (tc-show "C" cc 0.0 8.0) :timeline/tempo-bpm)})
          result (set-tc s0 #{sb sc} true)]
      (is (= "2 shows not enabled: 'B' (overlaps 'A'), 'C' (needs a BPM)"
             (get-in result [:state :ui :notice :text])))
      (is (not (contains? result :history/description)))))
  (testing "A on: disable A; never refused, Disable Timecode"
    (let [s0 (tc-state {sa (on (tc-show "A" ca 0.0 600.0)) sb (on (tc-show "B" cb 300.0 600.0))})
          s1 (:state (set-tc s0 #{sa} false))]
      (is (= [nil :mtc] [(chase-of s1 sa) (chase-of s1 sb)]))
      (is (= "Disable Timecode" (history/undo-label (:history s1))))
      (is (nil? (get-in s1 [:ui :notice])))))
  (testing "already so, or a missing show: nothing"
    (let [s0 (tc-state {sa (on (tc-show "A" ca 0.0 600.0)) sb (tc-show "B" cb 300.0 600.0)})]
      (is (= {:state s0} (handle s0 {:event/type :shows/set-timecode :show-ids #{sa} :enabled? true})))
      (is (= {:state s0} (handle s0 {:event/type :shows/set-timecode :show-ids #{sb} :enabled? false})))
      (is (= {:state s0} (handle s0 {:event/type :shows/set-timecode :show-ids #{sc} :enabled? true})))
      (is (= {:state s0} (handle s0 {:event/type :shows/set-timecode})))))
  (testing "one :show-id with :on? reads as :show-ids #{id} :enabled?"
    (let [s0 (tc-state {sb (tc-show "B" cb 300.0 600.0)})]
      (is (= :mtc (chase-of (:state (handle s0 {:event/type :shows/set-timecode :show-id sb :on? true})) sb)))))
  (testing "a show going on is versioned for the chase in the same event"
    (let [s0 (tc-state {sb (tc-show "B" cb 300.0 600.0)})
          s1 (:state (set-tc s0 #{sb} true))]
      (is (number? (get-in s1 [:playback :chase-sources :cells (shows/play-key sb)]))))))

(defn- trace-1-state
  "07 §7.1 Trace 1: A on [0, 300) and chased, B on [300, 600), saved."
  []
  (-> (tc-state {sa (on (tc-show "A" ca 0.0 600.0)) sb (on (tc-show "B" cb 300.0 600.0))})
      (assoc-in [:playback :playing?] true)
      (assoc-in [:playback :active-cues] {(shows/play-key sa) {:chasing? true :timeline? true
                                                               :chase-id [:d (shows/play-key sa) 3 nil 812]}})
      (assoc-in [:playback :chase-sources] {:next 5 :cells {(shows/play-key sa) 3 (shows/play-key sb) 4}})
      (history/mark-clean)))

(def ^:private move-a-clip
  {:event/type :timeline/apply-op :show-id sa :op {:op :move-clips :clip-ids #{ca} :delta 4.0}})

(deftest edit-that-overlaps-turns-timecode-off-test
  (let [s0 (trace-1-state)
        result (pipeline (assoc move-a-clip :state s0))
        s1 (:state result)
        notice (get-in s1 [:ui :notice])]
    (testing "A ends at 604 beats, [0, 302): its timecode goes off, B's stays"
      (is (= 4.0 (get-in s1 [:chains :shows sa :items 0 :timeline/start])))
      (is (= [nil :mtc] [(chase-of s1 sa) (chase-of s1 sb)]))
      (is (empty? (shows/invariant-violations (get-in s1 [:chains :shows])))))
    (testing "A's version and chase marker go in the same event; the last stops the transport"
      (is (= {:next 5 :cells {(shows/play-key sb) 4}} (get-in s1 [:playback :chase-sources])))
      (is (= {} (get-in s1 [:playback :active-cues])))
      (is (false? (get-in s1 [:playback :playing?]))))
    (testing "the notice, and its one timer"
      (is (= {:text "Timecode off for 'A': it now overlaps 'B' (00:05:00:00–00:10:00:00)" :level :warning}
             (select-keys notice [:text :level])))
      (is (= {:event {:event/type :ui/clear-notice :id (:id notice)} :delay-ms 6000} (:dispatch-later result))))
    (testing "one undo step, the edit's own label; unsaved"
      (is (= 1 (undo-count s1)))
      (is (= "Move Clip" (history/undo-label (:history s1))))
      (is (true? (get-in s1 [:project :dirty?]))))
    (testing "Trace 2: undo brings back both the clip and the timecode, and versions A again"
      (let [s2 (run s1 {:event/type :edit/undo})]
        (is (= 0.0 (get-in s2 [:chains :shows sa :items 0 :timeline/start])))
        (is (= [:mtc :mtc] [(chase-of s2 sa) (chase-of s2 sb)]))
        (is (= 5 (get-in s2 [:playback :chase-sources :cells (shows/play-key sa)])))
        (is (= 6 (get-in s2 [:playback :chase-sources :next])))
        (is (empty? (shows/invariant-violations (get-in s2 [:chains :shows]))))))))

(deftest unlocking-tempo-turns-timecode-off-test
  (let [s0 (trace-1-state)
        s1 (:state (pipeline {:event/type :timeline/apply-op :show-id sa :op {:op :set-tempo :bpm nil} :state s0}))]
    (is (nil? (chase-of s1 sa)))
    (is (= "Timecode off for 'A': it needs a BPM" (get-in s1 [:ui :notice :text])))
    (is (= "Set Timeline Tempo" (history/undo-label (:history s1))))))

(deftest turn-off-names-every-show-it-turns-off-test
  (let [s0 (tc-state {sa (on (tc-show "A" ca 0.0 600.0))
                      sb (on (tc-show "B" cb 300.0 600.0))
                      sc (on (tc-show "C" cc 600.0 8.0))})
        both (fn [{:keys [state]}]
               {:state (-> state
                           (assoc-in [:chains :shows sb :timeline/tc-start] 200.0)
                           (update-in [:chains :shows sc] dissoc :timeline/tempo-bpm))})
        s1 (:state ((timeline-handlers/wrap-normalize-timelines both) {:event/type :test/edit :state s0}))]
    (is (= [:mtc nil nil] (map #(chase-of s1 %) [sa sb sc])))
    (is (= "Timecode off for 2 shows: 'B' (overlaps 'A'), 'C' (needs a BPM)" (get-in s1 [:ui :notice :text])))))

(deftest turn-off-merges-with-handler-notice-test
  (let [s0 (trace-1-state)
        grow-a #(assoc-in % [:chains :shows sa :timeline/length] 604.0)
        wrapped (fn [handler] ((timeline-handlers/wrap-normalize-timelines handler) {:event/type :test/edit :state s0}))]
    (testing "the handler's fresh notice and its timer: one notice, one timer"
      (let [result (wrapped #(h/show-notice (grow-a (:state %)) "Saved BPM." :info))
            notice (get-in result [:state :ui :notice])]
        (is (nil? (chase-of (:state result) sa)))
        (is (= {:text "Saved BPM. Timecode off for 'A': it now overlaps 'B' (00:05:00:00–00:10:00:00)" :level :warning}
               (select-keys notice [:text :level])))
        (is (= {:event {:event/type :ui/clear-notice :id (:id notice)} :delay-ms 6000} (:dispatch-later result)))))
    (testing "the handler's own timer and dispatch stay"
      (let [identify {:event {:event/type :projectors/identify-ended :projector-id :p1} :delay-ms 3000}
            open {:event/type :ui/open-dialog :dialog-id :x}
            result (wrapped (fn [e] {:state (grow-a (:state e)) :dispatch-later identify :dispatch open}))
            notice (get-in result [:state :ui :notice])]
        (is (= [identify {:event {:event/type :ui/clear-notice :id (:id notice)} :delay-ms 6000}]
               (:dispatch-later result)))
        (is (= open (:dispatch result)))))
    (testing "an edit that keeps TS-6: the effects as the handler left them"
      (let [effects {:state (assoc-in s0 [:chains :shows sa :name] "A2") :dispatch-later {:event {:event/type :x} :delay-ms 1}}
            result (wrapped (constantly effects))]
        (is (= (:dispatch-later effects) (:dispatch-later result)))
        (is (nil? (get-in result [:state :ui :notice])))))))

(deftest open-never-turns-timecode-off-test
  ;; A file whose enabled shows overlap (written by hand, or by an older
  ;; build): Open leaves them as they are, and the file saved
  (let [loaded (-> (domains/build-initial-state)
                   (with-shows {sa (on (tc-show "A" ca 0.0 604.0)) sb (on (tc-show "B" cb 300.0 600.0))}))
        s1 (run (base) {:event/type :file/project-loaded
                        :file-path "test-output/open-never-turns-timecode-off.zip"
                        :contents (persistent/project-files loaded)})]
    (is (= [:mtc :mtc] [(chase-of s1 sa) (chase-of s1 sb)]))
    (is (seq (shows/invariant-violations (get-in s1 [:chains :shows]))) "the file's shows, as they were")
    (is (nil? (get-in s1 [:ui :notice])))
    (is (false? (get-in s1 [:project :dirty?])))
    (testing "the next edit of A turns it off"
      (let [s2 (run s1 {:event/type :timeline/set-name :show-id sa :name "A2"})]
        (is (nil? (chase-of s2 sa)))))))

(deftest undo-never-turns-timecode-off-test
  ;; Undo and redo restore a step as it was. Here the step before the edit
  ;; broke TS-6 (as an opened file can, open-never-turns-timecode-off-test):
  ;; undo brings it back as it was, timecode on, with no notice
  (let [s0 (tc-state {sa (on (tc-show "A" ca 0.0 604.0)) sb (on (tc-show "B" cb 300.0 600.0))})
        s1 (run s0 {:event/type :timeline/set-name :show-id sa :name "A2"})
        s2 (run (assoc-in s1 [:ui :notice] nil) {:event/type :edit/undo})
        s3 (run s2 {:event/type :edit/redo})]
    (is (nil? (chase-of s1 sa)) "the edit turns it off")
    (is (= ["A" :mtc] [(get-in s2 [:chains :shows sa :name]) (chase-of s2 sa)]) "undo: as it was")
    (is (nil? (get-in s2 [:ui :notice])))
    (is (= ["A2" nil] [(get-in s3 [:chains :shows sa :name]) (chase-of s3 sa)]) "redo: the step as it was made")))

(deftest invariant-holds-after-random-edits-test
  ;; 07 §8: no test.check, so seeds 0-199 on java.util.Random, 30 events each
  ;; on 5 shows; TS-6 holds after every one
  (let [ids [sa sb sc u v]
        clip-of (zipmap ids [ca cb cc c1 #uuid "00000000-0000-0000-0000-0000000000e5"])
        s0 (tc-state (into {} (map-indexed (fn [i id] [id (tc-show (str "S" i) (clip-of id) (* 60.0 i) 64.0)])) ids))
        event (fn [^java.util.Random r]
                (let [id (ids (.nextInt r (count ids)))
                      op #(hash-map :event/type :timeline/apply-op :show-id id :op %)]
                  (case (.nextInt r 8)
                    0 {:event/type :shows/set-timecode :show-ids #{id} :enabled? true}
                    1 {:event/type :shows/set-timecode
                       :show-ids (set (repeatedly (inc (.nextInt r 3)) #(ids (.nextInt r (count ids)))))
                       :enabled? (.nextBoolean r)}
                    2 (op {:op :set-tempo :bpm ([nil 90.0 120.0 150.0] (.nextInt r 4))})
                    3 (op {:op :set-tc-start :seconds (* 10.0 (.nextInt r 40))})
                    4 (op {:op :move-clips :clip-ids #{(clip-of id)} :delta (- (.nextInt r 65) 32.0)})
                    5 (op {:op :set-length :beats (when (.nextBoolean r) (+ 4.0 (.nextInt r 200)))})
                    6 {:event/type :edit/undo}
                    7 {:event/type :edit/redo})))
        failure (some (fn [seed]
                        (let [r (java.util.Random. seed)]
                          (loop [s s0 n 0 events []]
                            (when (< n 30)
                              (let [e (event r)
                                    s' (run s e)
                                    events (conj events e)
                                    bad (shows/invariant-violations (get-in s' [:chains :shows]))]
                                (if (seq bad)
                                  {:seed seed :events events :violations bad}
                                  (recur s' (inc n) events)))))))
                      (range 200))]
    (is (nil? failure) (pr-str failure))))



;; Transport (S5): plans/2026-11-25-timeline-shows 01 §5, 07 §4.5, §8

(def show-a #uuid "00000000-0000-0000-0000-0000000005a3")
(def show-b #uuid "00000000-0000-0000-0000-0000000005a1")
(def show-c #uuid "00000000-0000-0000-0000-0000000005a2")

(defn- abc-state
  "Shows A, B and C (ids not in title order), the table sorted by title:
   visible [show-a show-b show-c]. Nothing plays."
  []
  (-> (play-state)
      (with-shows {show-a (assoc a-show :name "A") show-b (assoc a-show :name "B") show-c (assoc a-show :name "C")})))

(defn- send-event [state event] (:state (handle state event)))
(defn- manual [state] (get-in state [:ui :shows :manual]))
(defn- playing-shows-of [state]
  (set (keep shows/key-show-id (keys (get-in state [:playback :active-cues])))))

(deftest play-replaces-previous-manual-show-test
  (let [ku (shows/play-key u)
        kv (shows/play-key v)
        grid-intent {:run (random-uuid)}]
    (testing ":manual nil, grid [0 0] playing: :shows/play u → [0 0] and [:show u] play; :manual u"
      (let [s (-> (play-state)
                  (assoc-in [:playback :active-cues [0 0]] grid-intent)
                  (assoc-in [:playback :playing?] true))
            s' (send-event s {:event/type :shows/play :show-id u})]
        (is (= #{[0 0] ku} (set (keys (get-in s' [:playback :active-cues])))))
        (is (= grid-intent (intent s' [0 0])))
        (is (= u (manual s')))))
    (testing ":manual u playing: :shows/play v → [:show u] gone, [:show v] from beat 0; :manual v"
      (let [s (-> (play-state)
                  (assoc-in [:playback :active-cues [0 0]] grid-intent)
                  (send-event {:event/type :shows/play :show-id u})
                  (send-event {:event/type :shows/play :show-id v}))]
        (is (nil? (intent s ku)))
        (is (= {:timeline? true :start-beat 0.0} (dissoc (intent s kv) :run)))
        (is (= grid-intent (intent s [0 0])) "grid cues keep playing")
        (is (= v (manual s)))))
    (testing "a show a pad started stays when another is played by hand"
      (let [s (-> (play-state)
                  (send-event {:event/type :shows/toggle-run :show-id u})
                  (send-event {:event/type :shows/play :show-id v}))]
        (is (= #{u v} (playing-shows-of s)))))))

(deftest play-refused-while-following-timecode-test
  (let [ku (shows/play-key u)
        kv (shows/play-key v)]
    (testing "v enabled: :shows/play v → unchanged, with the notice"
      (let [s (-> (with-shows (play-state) {u a-show v follower})
                  (send-event {:event/type :shows/play :show-id u}))
            result (handle s {:event/type :shows/play :show-id v})]
        (is (= "Follows timecode. Untick TC to play it by hand." (get-in result [:state :ui :notice :text])))
        (is (= (:playback s) (get-in result [:state :playback])))
        (is (= u (manual (:state result))))))
    (testing ":manual follows timecode now: ⏯, ⏹, seek, ⏭ and ⏮ do nothing"
      (let [marker {:chasing? true :timeline? true :chase-id [:d ku 0 nil 1]}
            s (-> (with-shows (play-state) {u follower v a-show})
                  (assoc-in [:ui :shows :manual] u)
                  (assoc-in [:playback :active-cues ku] marker)
                  (assoc-in [:playback :playing?] true))]
        (doseq [event [{:event/type :shows/toggle-play}
                       {:event/type :shows/stop}
                       {:event/type :shows/seek :show-id u :beat 4.0}
                       {:event/type :shows/next}
                       {:event/type :shows/previous}]]
          (is (= {:state s} (handle s event)) (str (:event/type event))))))
    (testing "and without a marker too"
      (let [s (-> (with-shows (play-state) {u follower v a-show})
                  (assoc-in [:ui :shows :manual] u))]
        (is (= {:state s} (handle s {:event/type :shows/toggle-play})))
        (is (nil? (intent (send-event s {:event/type :shows/next}) kv)))))))

(deftest next-previous-follow-visible-order-test
  (let [s (abc-state)
        with-manual #(assoc-in s [:ui :shows :manual] %)
        step (fn [state type] (send-event state {:event/type type}))]
    (testing "visible [A B C], :manual B: next plays C, previous plays A"
      (let [n (step (with-manual show-b) :shows/next)
            p (step (with-manual show-b) :shows/previous)]
        (is (= show-c (manual n)))
        (is (= #{show-c} (playing-shows-of n)))
        (is (= show-a (manual p)))
        (is (= #{show-a} (playing-shows-of p)))))
    (testing "B playing: next stops it and plays C from beat 0"
      (let [s' (-> s (send-event {:event/type :shows/play :show-id show-b}) (step :shows/next))]
        (is (= #{show-c} (playing-shows-of s')))
        (is (= 0.0 (:start-beat (intent s' (shows/play-key show-c)))))))
    (testing "at either end: unchanged"
      (is (= {:state (with-manual show-c)} (handle (with-manual show-c) {:event/type :shows/next})))
      (is (= {:state (with-manual show-a)} (handle (with-manual show-a) {:event/type :shows/previous}))))
    (testing "the table's order, not the library's: sorted by title descending"
      (let [desc (assoc-in (with-manual show-b) [:ui :shows :sort] {:column :title :dir :desc})]
        (is (= show-a (manual (step desc :shows/next))))
        (is (= show-c (manual (step desc :shows/previous))))))
    (testing ":manual filtered out, or none: next plays the first visible row, previous the last"
      (let [filtered (-> (with-manual show-b) (assoc-in [:ui :shows :query] "a"))]
        (is (= [show-a] (shows/visible-ids filtered)))
        (is (= show-a (manual (step filtered :shows/next))))
        (is (= show-a (manual (step filtered :shows/previous)))))
      (is (= show-a (manual (step s :shows/next))))
      (is (= show-c (manual (step s :shows/previous)))))
    (testing "a show that follows timecode is passed over"
      (let [s' (-> (with-manual show-a) (assoc-in (conj (shows/show-path show-b) :timeline/chase) :mtc))]
        (is (= show-c (manual (step s' :shows/next))))))
    (testing "no shows: nothing"
      (let [empty (with-shows (play-state) {})]
        (is (= {:state empty} (handle empty {:event/type :shows/next})))
        (is (= {:state empty} (handle empty {:event/type :shows/previous})))))))

(deftest play-resumes-held-transport-test
  (let [ku (shows/play-key u)
        s (-> (play-state)
              (assoc-in [:chains :cue-chains [0 0]] {:items [{:type :preset :preset-id :circle :id (random-uuid)}]})
              (run {:event/type :grid/trigger-cell :col 0 :row 0}))
        _ (is (some? (intent s [0 0])) "[0 0] plays")
        held (run s {:event/type :transport/stop})
        _ (is (false? (get-in held [:playback :playing?])) "Stop holds it")
        _ (is (some? (intent held [0 0])))
        played (run held {:event/type :shows/play :show-id u})]
    (is (true? (get-in played [:playback :playing?])))
    (is (= #{[0 0] ku} (set (keys (get-in played [:playback :active-cues])))) "both intents")))

(deftest toggle-play-test
  (let [ku (shows/play-key u)
        toggle #(send-event % {:event/type :shows/toggle-play})]
    (testing "playing: pauses; paused: resumes, the same run"
      (let [s (send-event (play-state) {:event/type :shows/play :show-id u})
            paused (toggle s)
            resumed (toggle paused)]
        (is (true? (:paused? (intent paused ku))))
        (is (false? (:paused? (intent resumed ku))))
        (is (= (:run (intent s ku)) (:run (intent resumed ku))))))
    (testing "stopped or ended: plays it again from beat 0"
      (let [s (-> (play-state)
                  (send-event {:event/type :shows/play :show-id u})
                  (send-event {:event/type :shows/stop}))
            s' (toggle s)]
        (is (nil? (intent s ku)))
        (is (= {:timeline? true :start-beat 0.0} (dissoc (intent s' ku) :run)))
        (is (= u (manual s')))))
    (testing ":manual nil: the first selected visible show, else the first visible"
      (let [s (abc-state)]
        (is (= show-a (manual (toggle s))))
        (is (= show-b (manual (toggle (assoc-in s [:ui :shows :selected] #{show-b show-c})))) "in the table's order")
        (is (= show-a (manual (toggle (-> s
                                      (assoc-in [:ui :shows :selected] #{show-b})
                                      (assoc-in [:ui :shows :query] "a")))))
            "a selected show filtered out doesn't count")))
    (testing "no shows: nothing"
      (let [s (with-shows (play-state) {})]
        (is (= {:state s} (handle s {:event/type :shows/toggle-play})))))))

(deftest seek-test
  (let [ku (shows/play-key u)
        s (send-event (play-state) {:event/type :shows/play :show-id u})]
    (testing "the show played by hand gets a seek request"
      (let [s' (send-event s {:event/type :shows/seek :show-id u :beat 4.0})]
        (is (= 4.0 (get-in (intent s' ku) [:seek :beat])))
        (is (uuid? (get-in (intent s' ku) [:seek :id])))
        (is (= (:run (intent s ku)) (:run (intent s' ku))) "the same run")))
    (testing "paused: it seeks too"
      (let [s' (-> s (send-event {:event/type :shows/toggle-play}) (send-event {:event/type :shows/seek :beat 2.0}))]
        (is (= 2.0 (get-in (intent s' ku) [:seek :beat])))))
    (testing "a release for a show no longer played by hand, stopped, or no beat: nothing"
      (is (= {:state s} (handle s {:event/type :shows/seek :show-id v :beat 4.0})))
      (let [stopped (send-event s {:event/type :shows/stop})]
        (is (= {:state stopped} (handle stopped {:event/type :shows/seek :show-id u :beat 4.0}))))
      (is (= {:state s} (handle s {:event/type :shows/seek :show-id u}))))))

(deftest transport-events-are-routed-test
  (doseq [type [:shows/toggle-play :shows/seek :shows/next :shows/previous]]
    (let [s (base)]
      (is (contains? (shows-handlers/handle {:event/type type :state s}) :state) (str type))
      (is (contains? (handlers/handle-event {:event/type type :state s}) :state)
          (str type " is routed to the shows handler")))))
