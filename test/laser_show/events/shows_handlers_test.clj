(ns laser-show.events.shows-handlers-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [laser-show.events.core :as events]
            [laser-show.events.handlers :as handlers]
            [laser-show.events.handlers.shows :as shows-handlers]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
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
