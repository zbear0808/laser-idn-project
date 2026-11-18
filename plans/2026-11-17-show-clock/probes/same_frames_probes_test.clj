;; EVIDENCE for plans/2026-11-17-show-clock/04 §7, invariant 1.
;; Not part of the test suite and not on any classpath.
;;
;; c9: the same scene renders the same frames before and after a change to
;; the render. It plays three cues for 4 s of ticks on a fake clock (every
;; 7th tick kept) and records each cue's frames by zone, one projector's
;; frame (with its projector effects), the preview, and each cue rendered
;; again directly through frame-service. The scene has what the render
;; threads time through:
;; - [0 0], a grid cue on :all: sine, triangle and MIDI (the input store)
;;   modulators, trace fade in beats, a group with its own effect, and a
;;   per-point hue;
;; - [1 0], a grid cue on :left: a time keyframe modulator and trace fade in
;;   seconds;
;; - [2 0], a timeline at a locked 90 BPM: a track effect and a once-mode
;;   clip modulator, so the global effects run at the timeline's tempo;
;; - the effects grid: a modulated rotation and a MIDI colour filter;
;; - projector :p on :all and :left: RGB curves, corner pin and a modulated
;;   translate.
;; It calls the frame service through whichever API the checkout has, so it
;; runs before Phase 4 (generate-frames-by-zone with elapsed-ms, bpm and
;; trigger-time) and after.
;;
;; Run it in a checkout of the commit before, then in this one (macOS Apple
;; Silicon; use :win, :linux or :mac elsewhere):
;;
;;   export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
;;   export PATH="$JAVA_HOME/bin:$PATH"
;;   P=$PWD/plans/2026-11-17-show-clock
;;   git worktree add --detach /tmp/before 936f49c
;;   (cd /tmp/before && FRAMES_OUT=/tmp/before.edn \
;;      clj -Sdeps "{:aliases {:probe {:extra-paths [\"$P\"]}}}" -M:mac-arm:test:probe \
;;          -d "$P/probes" -n probes.same-frames-probes-test 2>&1 | grep PROBE)
;;   FRAMES_OUT=/tmp/after.edn FRAMES_BEFORE=/tmp/before.edn \
;;      clj -Sdeps "{:aliases {:probe {:extra-paths [\"$P\"]}}}" -M:mac-arm:test:probe \
;;          -d "$P/probes" -n probes.same-frames-probes-test 2>&1 | grep PROBE
;;   git worktree remove /tmp/before

(ns probes.same-frames-probes-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest]]
            [laser-show.animation.cue-timing :as ct]
            [laser-show.animation.show-time :as show-time]
            [laser-show.app]
            [laser-show.backend.multi-engine :as me]
            [laser-show.events.core :as events]
            [laser-show.input.store :as input-store]
            [laser-show.services.frame-service :as fs]
            [laser-show.services.show-clock :as show-clock]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.timeline.playback :as playback]))

(defn- say [& xs] (apply println "PROBE" xs) (flush))

(defn- uuid [n] (java.util.UUID. 0 n))

(defn- fx [n effect-id params & {:as more}]
  (merge {:id (uuid n) :effect-id effect-id :enabled? true :params params} more))

(defn- sine [lo hi period] {:type :sine :min lo :max hi :period period})

(def ^:private track (uuid 900))

(def ^:private chains
  {[0 0] {:destination-zone {:zone-group-id :all}
          :items [{:id (uuid 1) :type :preset :preset-id :circle :enabled? true
                   :effects [(fx 2 :rotation {:angle (sine 0.0 360.0 1.0)})
                             (fx 3 :scale {:x-scale (sine 0.5 1.0 2.0) :y-scale {:type :triangle :min 0.4 :max 1.0 :period 3.0}})
                             (fx 4 :hue-shift {:degrees (sine 0.0 180.0 4.0)})
                             (fx 5 :color-filter {:r-mult {:type :midi :channel 0 :cc 7 :min 0.0 :max 1.0}})
                             (fx 6 :trace-fade {:period 2.0 :tail-length 0.4})]}
                  {:id (uuid 7) :type :group :enabled? true
                   :effects [(fx 8 :translate {:x (sine -0.2 0.2 1.5)})]
                   :items [{:id (uuid 9) :type :preset :preset-id :star :enabled? true :effects []}
                           {:id (uuid 10) :type :preset :preset-id :square :enabled? true
                            :effects [(fx 11 :set-hue {:hue {:type :pos-x :min 0.0 :max 360.0}})]}]}]}
   [1 0] {:destination-zone {:zone-group-id :left}
          :items [{:id (uuid 20) :type :preset :preset-id :square :enabled? true
                   :effects [(fx 21 :scale {:x-scale 1.0 :y-scale 1.0}
                                 :keyframe-modulator {:enabled? true :driver :time :period 2.0
                                                      :keyframes [{:position 0.0 :params {:x-scale 0.3 :y-scale 1.0}}
                                                                  {:position 1.0 :params {:x-scale 1.0 :y-scale 0.5}}]})
                             (fx 22 :trace-fade {:period 0.7 :tail-length 0.3 :time-unit :seconds})]}]}
   [2 0] {:editor-type :timeline
          :timeline/tempo-bpm 90.0
          :destination-zone {:zone-group-id :all}
          :tracks [{:id track :type :track
                    :effects [(fx 30 :translate {:y (sine -0.3 0.3 2.0)})]}]
          :items [{:id (uuid 31) :type :preset :preset-id :circle :track-id track :enabled? true
                   :timeline/start 0.0 :timeline/duration 32.0
                   :effects [(fx 32 :rotation {:angle {:type :sawtooth :min 0.0 :max 360.0 :period 4.0 :loop-mode :once}})]}]}})

(defn- init! []
  (state/init-state! (-> (domains/build-initial-state)
                         (assoc-in [:chains :cue-chains] chains)
                         (assoc-in [:chains :effect-chains [0 0]]
                                   {:active? true
                                    :items [(fx 40 :rotation {:angle (sine 0.0 45.0 4.0)})
                                            (fx 41 :color-filter {:g-mult {:type :midi :channel 0 :cc 8 :min 0.2 :max 1.0}})]})
                         (assoc-in [:chains :projector-effects :p :items]
                                   [(fx 50 :rgb-curves {:r-curve-points [[0.0 0.1] [1.0 1.0]]
                                                        :g-curve-points [[0.0 0.0] [0.5 0.3] [1.0 1.0]]
                                                        :b-curve-points [[0.0 0.0] [1.0 0.9]]})
                                    (fx 51 :corner-pin {:tl-x -0.9 :tl-y 0.95 :tr-x 0.85 :tr-y 1.0
                                                        :bl-x -1.0 :bl-y -0.9 :br-x 0.95 :br-y -0.95})
                                    (fx 52 :translate {:x (sine -0.05 0.05 8.0)})])
                         (assoc-in [:projectors :p] {:zone-groups [:all :left]})
                         (assoc-in [:timing :bpm] 120.0)))
  (show-time/reset-runtime!)
  (input-store/clear-continuous-values!)
  (input-store/set-continuous-value! [:midi 0 7] 0.42)
  (input-store/set-continuous-value! [:midi 0 8] 0.8))

(def ^:private before-phase-4?
  "generate-frames-by-zone still takes [cue-chain elapsed-ms bpm trigger-time timing-ctx]."
  (contains? (set (map count (:arglists (meta #'fs/generate-frames-by-zone)))) 5))

(defn- direct-renders
  "Each playing cue rendered directly, as tests and tools call the frame service."
  []
  (let [s (state/get-raw-state)]
    (into {}
          (map (fn [{:keys [cell cue-chain cue-timing]}]
                 (let [bpm (playback/cue-bpm cue-chain (get-in s [:timing :bpm]))
                       ctx (assoc (ct/get-cue-timing-context cue-timing bpm)
                                  :input-values (input-store/get-all-continuous-values))]
                   [cell (if before-phase-4?
                           #_{:clj-kondo/ignore [:invalid-arity]}
                           (fs/generate-frames-by-zone cue-chain (long (:accumulated-ms cue-timing 0.0)) bpm 0 ctx)
                           (fs/generate-frames-by-zone cue-chain ctx))])))
          (show-time/active-runs s))))

(defn- scene []
  (init!)
  (with-redefs [events/dispatch! events/event-handler]
    (events/event-handler {:event/type :grid/trigger-cell :col 0 :row 0})
    (events/event-handler {:event/type :grid/trigger-cell :col 1 :row 0})
    (events/event-handler {:event/type :timeline/play :col 2 :row 0 :from 0.0})
    (vec (for [i (range 0 240)
               :let [snapshot (show-clock/tick! (* i 16666667))]
               :when (zero? (mod i 7))]
           {:tick i
            :cues (mapv (juxt :cell :zone-frames) (:cues snapshot))
            :projector (#'me/projector-frame :p (assoc snapshot :at-ns (System/nanoTime)))
            :preview (some-> (:preview snapshot) deref)
            :direct (direct-renders)}))))

(deftest c9-same-frames-before-and-after
  (let [out (scene)
        before (System/getenv "FRAMES_BEFORE")]
    (say "c9" (if before-phase-4? "before Phase 4's API:" "after Phase 4's API:") (count out) "ticks,"
         (reduce + (for [{:keys [cues]} out [_ zf] cues f (vals zf)] (count f))) "points in cue frames,"
         (count (filter :projector out)) "projector frames | distinct frames per cue"
         (into (sorted-map) (for [cell (keys chains)]
                              [(str cell) (count (distinct (keep #(get (into {} (:cues %)) cell) out)))])))
    (when-let [path (System/getenv "FRAMES_OUT")]
      (spit path (pr-str out)))
    (when before
      (let [b (edn/read-string (slurp before))]
        (say "c9 the same as" before "->" (= b out))
        (doseq [[x y] (map vector b out)
                k [:cues :projector :preview :direct]
                :when (not= (k x) (k y))]
          (say "c9 differs at tick" (:tick x) "in" k))))))
