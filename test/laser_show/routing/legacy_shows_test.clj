(ns laser-show.routing.legacy-shows-test
  "Old shows route as before (zones plan Z4, 06 §9, 00 R-2, spec 01
   invariant 3). Six shows saved before Z4, each opened on one rig through
   apply-loaded-project, the way File > Open does; each cue played alone and
   looked at at beats 0, 1.5 and 7.25. What every laser draws of it
   (routing.compose/projector-frame, the frame its engine composes before
   its calibration) and what every preview cell shows must be what
   golden.edn holds: record-golden! wrote it before Z4 changed anything
   (at c067030, and again at 64fab99 when digest moved to steps of 1/65536),
   so Z4's conversion has to route every one the same.

   The rig: Laser 1, Laser 2 (in Graphics, a smaller safety zone), Laser 3
   (in the manual Left), Laser 4 (off, in Graphics) and Crowd (excluded from
   All, in Crowd Scanning, scanning the audience). Lasers 1-3 make All and
   the automatic groups, Laser 2 the split middle."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.test :refer [deftest is testing]]
            [laser-show.animation.cue-timing :as cue-timing]
            [laser-show.animation.show-time :as show-time]
            [laser-show.events.core :as events]
            [laser-show.routing.compose :as compose]
            [laser-show.services.show-clock :as show-clock]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.state.persistent :as persistent]
            [laser-show.timeline.shows :as shows]
            [laser-show.views.components.preview :as preview]))

(def show-names
  "The fixture shows, test/laser_show/routing/legacy_shows/<name>.edn, each
   a project zip's files ({filename data})."
  ["destination" "item-zones" "selectors" "timeline" "preview" "starter"])

(def ^:private rig-projectors
  {:p1 {:name "Laser 1" :host "10.0.0.1" :service-id 0 :enabled? true :zone-groups [] :exclude-from-all? false
        :stable-id [:host "10.0.0.1" 0]}
   :p2 {:name "Laser 2" :host "10.0.0.2" :service-id 0 :enabled? true :zone-groups [:graphics] :exclude-from-all? false
        :stable-id [:host "10.0.0.2" 0] :safety {:limit [-0.8 -0.6 0.8 0.9] :blocked []}}
   :p3 {:name "Laser 3" :host "10.0.0.3" :service-id 0 :enabled? true :zone-groups [:left] :exclude-from-all? false
        :stable-id [:host "10.0.0.3" 0]}
   :p4 {:name "Laser 4" :host "10.0.0.4" :service-id 0 :enabled? false :zone-groups [:graphics] :exclude-from-all? false
        :stable-id [:host "10.0.0.4" 0]}
   :p5 {:name "Crowd" :host "10.0.0.5" :service-id 0 :enabled? true :zone-groups [:crowd] :exclude-from-all? true
        :tags #{:crowd-scanning} :stable-id [:host "10.0.0.5" 0]}})

(def ^:private rig {:order [:p1 :p2 :p3 :p4 :p5] :middle :split :split-width 0.75})

(defn rig-state
  "This machine's state before a show is opened: a new one, on the rig."
  []
  (assoc (domains/build-initial-state) :projectors rig-projectors :virtual-projectors {} :rig rig))

(defn show-file
  "The fixture show's files; a hardware.edn without projectors gets the
   rig's, as a show saved on this rig has."
  [show-name]
  (-> (str "laser_show/routing/legacy_shows/" show-name ".edn")
      io/resource
      slurp
      edn/read-string
      (update "hardware.edn" #(merge {:projectors rig-projectors :virtual-projectors {}} %))))

(defn open-show
  "The state after File > Open of the fixture show on the rig."
  [show-name]
  (persistent/apply-loaded-project (rig-state) (show-file show-name) (str show-name ".zip")))

(def beats
  "Where each cue is looked at: at 120 BPM, 0, 750 and 3,625 ms after it
   starts. The clock is ticked every quarter beat (125 ms) to get there: it
   skips a gap longer than a second (cue-timing)."
  [0.0 1.5 7.25])

(defn- tick-ns [beat]
  (long (* (double beat) 500.0 1e6)))

(def ^:private ticks
  "Every quarter beat up to the last of beats."
  (mapv #(* 0.25 %) (range (inc (long (* 4 (peek beats)))))))

(def ^:private steps-per-unit
  "A frame's numbers are hashed in steps of 1/65536: finer than a 16-bit
   coordinate (2/65535 from -1 to 1) and a 16-bit colour (1/65535), so any
   change a laser could show changes the hash, but the last bits of a double
   don't. Those differ from machine to machine: Math/sin and Math/cos are
   only good to an ulp, and the JVM computes them differently on aarch64
   and x86-64. A power of two keeps whole fractions such as 0.5 and 0.25 off
   the rounding edges."
  65536.0)

(defn- step [v]
  (Math/round (* steps-per-unit (double v))))

(defn- digest
  "A frame as {:points :hash}: its point count and the hash of its points'
   numbers in steps of 1/65536 (two frames hash alike only when every number
   rounds to the same step). nil for no frame."
  [frame]
  (when frame
    {:points (count frame)
     :hash (hash (mapv #(mapv step %) frame))}))

(defn- lasers
  "{projector-id digest} of what each laser of the tick's routing draws,
   with :regions (how many halves), or nil when nothing reaches it."
  [snapshot]
  (into (sorted-map)
        (map (fn [pid]
               [pid (when-let [{:keys [frame regions]} (compose/projector-frame (:cues snapshot) (:routing snapshot) pid)]
                      (assoc (digest frame) :regions regions))]))
        (sort (keys (get-in snapshot [:routing :outputs])))))

(defn- preview-cells
  "What each of the preview's cells shows of the tick, as digests."
  [state snapshot]
  (let [p (some-> (:preview snapshot) deref)]
    (mapv #(digest (not-empty (preview/cell-points p %))) (get-in state [:config :preview :grid-cells]))))

(defn- start!
  "Start the cue at playback key k as a press of its cell did: a grid cell
   by :grid/trigger-cell; a show (a timeline cue Open moved out of the grid,
   timeline shows plan 04 §4.3) from beat 0 as a new run, with the press's
   :cue pulse (grid/start-cue)."
  [k]
  (if (shows/play-key? k)
    (state/swap-state! (fn [s]
                         (-> s
                             (update :playback cue-timing/activate-cue k (random-uuid) {:timeline? true :start-beat 0.0})
                             (update-in [:playback :pulses :cue :id] (fnil inc 0)))))
    (let [[col row] k]
      (events/event-handler {:event/type :grid/trigger-cell :col col :row row}))))

(defn play
  "The cue at playback key k (a grid cell, or a show's [:show id]) of the
   opened state played alone, ticked every quarter beat: {beat {:lasers
   :preview}} at each of beats."
  [opened k]
  (state/init-state! opened)
  (show-time/reset-runtime!)
  (try
    (with-redefs [events/dispatch! (fn [_])]
      (start! k)
      (into (sorted-map)
            (keep (fn [b]
                    (let [s (show-clock/tick! (tick-ns b))]
                      (when (some #(== b %) beats)
                        [b {:lasers (lasers s)
                            :preview (preview-cells (state/get-raw-state) s)}]))))
            ticks))
    (finally
      (show-time/reset-runtime!))))

(defn- cue-keys
  "{cell playback-key} of every cue the fixture show held, by the cell the
   file put it in (golden.edn's keys): a grid cue at its cell, a timeline
   cue that Open moved into the show library at its show's [:show id],
   found by its name (each timeline fixture cue has its own)."
  [show-name opened]
  (let [file-cues (get-in (show-file show-name) ["content.edn" :chains :cue-chains])
        by-name (into {} (map (fn [[id s]] [(:name s) (shows/play-key id)])) (get-in opened [:chains :shows]))]
    (into (sorted-map)
          (map (fn [[cell cue]]
                 [cell (if (contains? (get-in opened [:chains :cue-chains]) cell)
                         cell
                         (get by-name (:name cue)))]))
          file-cues)))

(defn- cue-name [opened k]
  (:name (shows/cue-at opened k)))

(defn routes
  "{show {cell (play …)}} for every fixture show and every one of its cues."
  []
  (into (sorted-map)
        (for [show show-names
              :let [opened (open-show show)]]
          [show (into (sorted-map) (for [[cell k] (cue-keys show opened)] [cell (play opened k)]))])))

(def ^:private golden-file "test/laser_show/routing/legacy_shows/golden.edn")

(defn record-golden!
  "Write golden.edn from today's routing. Run once, before Z4 changed
   anything; never again unless an old show is meant to route otherwise."
  []
  (with-open [w (io/writer golden-file)]
    (binding [*out* w
              *print-namespace-maps* false]
      (println ";; What every laser drew and every preview cell showed of each fixture show's")
      (println ";; cues, before zones Z4 (laser-show.routing.legacy-shows-test/record-golden!),")
      (println ";; each frame's numbers hashed in steps of 1/65536 (digest).")
      (pprint/pprint (routes)))))

(defn- golden []
  (edn/read-string (slurp (io/resource "laser_show/routing/legacy_shows/golden.edn"))))

(deftest old-shows-route-as-before-test
  (let [expected (golden)]
    (is (= (set show-names) (set (keys expected))) "every show was recorded")
    (doseq [show show-names
            :let [opened (open-show show)]]
      (is (= (sort (keys (get expected show))) (keys (cue-keys show opened))) (str show ": the same cues"))
      (is (every? some? (vals (cue-keys show opened))) (str show ": each found, in the grid or the show library"))
      (doseq [[cell k] (cue-keys show opened)]
        (testing (str show " " cell " " (cue-name opened k))
          (let [got (play opened k)]
            (doseq [b beats]
              (is (= (get-in expected [show cell b :lasers]) (get-in got [b :lasers]))
                  (str "every laser at beat " b))
              (is (= (get-in expected [show cell b :preview]) (get-in got [b :preview]))
                  (str "every preview cell at beat " b)))))))))

(deftest digest-is-the-same-on-every-platform-test
  (let [digest #'digest
        ;; destination [0 0]'s sixth point at beat 1.5, as macOS (aarch64)
        ;; and Linux (x86-64) computed it: their Math/sin and Math/cos differ
        ;; in the last bits.
        mac [[0.1 0.2 1.0 1.0 1.0] [-0.49899692895349707 -0.0316554086212551 1.0 1.0 1.0]]
        linux [[0.1 0.2 1.0 1.0 1.0] [-0.498996928953497 -0.03165540862125513 1.0 1.0 1.0]]]
    (is (= (digest mac) (digest linux)) "a last-bit difference isn't a different frame")
    (is (= (digest [[0.0 0.0 0.5 0.5 0.5]]) (digest [[-0.0 -1e-17 0.5 0.5 0.5]])) "nor is -0.0")
    (testing "a different frame is"
      (is (not= (digest mac) (digest (assoc-in mac [1 0] (+ -0.49899692895349707 (/ 1.0 32767))))) "one 16-bit step of x")
      (is (not= (digest mac) (digest (assoc-in mac [1 1] (- -0.0316554086212551 (/ 1.0 32767))))) "of y")
      (is (not= (digest mac) (digest (assoc-in mac [0 3] (- 1.0 (/ 1.0 255))))) "one 8-bit step of a colour")
      (is (not= (digest mac) (digest (rseq mac))) "the same points in another order")
      (is (not= (digest mac) (digest (pop mac))) "a point fewer"))))

(deftest legacy-loads-are-clean-test
  (doseq [show show-names]
    (testing show
      (let [opened (open-show show)]
        (is (false? (get-in opened [:project :dirty?])) "not unsaved")
        (is (empty? (get-in opened [:history :undo-stack])) "no undo step")
        (is (nil? (get-in opened [:ui :notice])) "and no notice")
        (testing "and what it saves opens the same"
          (let [again (persistent/apply-loaded-project (rig-state) (persistent/project-files opened) "again.zip")]
            (is (= (get-in opened [:chains :cue-chains]) (get-in again [:chains :cue-chains])))
            (is (= (get-in opened [:chains :shows]) (get-in again [:chains :shows])))
            (is (= (get-in opened [:chains :effect-chains]) (get-in again [:chains :effect-chains])))
            (is (= (:zone-groups opened) (:zone-groups again)))
            (is (= (get-in opened [:config :preview]) (get-in again [:config :preview])))
            (is (false? (get-in again [:project :dirty?])))))))))
