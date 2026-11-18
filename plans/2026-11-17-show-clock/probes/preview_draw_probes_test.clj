;; EVIDENCE for plans/2026-11-17-show-clock/03 (audit A12, review 01 M2).
;; Not part of the test suite and not on any classpath.
;;
;; c8: what one redraw of the preview grid costs on the FX thread. The app's
;; 3x2 layout (cells: master, :left, :right, :center, master, master) with
;; one busy cue on :all: three cells draw its 346 points, three draw "No
;; Preview". The canvases are the size a 3x2 cell gets in the default
;; 400x400 preview. Before Phase 3 the cells drew point maps that the show
;; clock's mirror converted every second tick (frame-service/frame->preview-data);
;; after it they draw the tick's point vectors.
;;
;; The canvases aren't in a scene: the GraphicsContext only queues commands
;; on the FX thread, and each redraw's full-canvas fill resets the queue, so
;; this measures the FX thread's share, which is what the redraw costs there.
;;
;; Needs the JavaFX toolkit, so it runs headless (macOS Apple Silicon; use
;; :win, :linux or :mac elsewhere):
;;
;;   export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
;;   export PATH="$JAVA_HOME/bin:$PATH"
;;   P=plans/2026-11-17-show-clock
;;   clj -Sdeps "{:aliases {:probe {:extra-paths [\"$P\"]}}}" \
;;       -M:mac-arm:test:test-ui-headless:probe -d "$P/probes" \
;;       -n probes.preview-draw-probes-test 2>&1 | grep PROBE

(ns probes.preview-draw-probes-test
  (:require [clojure.test :refer [deftest]]
            [laser-show.animation.cue-timing :as ct]
            [laser-show.animation.effects.color]
            [laser-show.animation.effects.shape]
            [laser-show.services.frame-service :as fs]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.views.components.preview :as preview])
  (:import [com.sun.management ThreadMXBean]
           [java.lang.management ManagementFactory]
           [javafx.application Platform]
           [javafx.scene.canvas Canvas]))

(defn- say [& xs] (apply println "PROBE" xs) (flush))

(defn- sine [lo hi period] {:type :sine :min lo :max hi :period period})

(defn- preset [preset-id & effects]
  {:id (random-uuid) :type :preset :preset-id preset-id :enabled? true
   :effects (mapv (fn [[effect-id params]]
                    {:id (random-uuid) :effect-id effect-id :enabled? true :params params})
                  effects)})

(def ^:private busy-cue
  "4 presets with 3 modulated effects each (show clock probe c3's)."
  {:destination-zone {:zone-group-id :all}
   :items (vec (for [p [:circle :square :star :spiral]]
                 (preset p
                         [:rotation {:angle (sine 0.0 360.0 1.0)}]
                         [:scale {:x-scale (sine 0.5 1.0 2.0) :y-scale (sine 0.5 1.0 3.0)}]
                         [:hue-shift {:degrees (sine 0.0 180.0 4.0)}])))})

(defn- start-toolkit! []
  (try
    (Platform/startup (fn []))
    (catch IllegalStateException _))
  (Platform/setImplicitExit false))

(defn- on-fx
  "(f) on the FX thread; its value."
  [f]
  (let [p (promise)]
    (Platform/runLater #(deliver p (try {:v (f)} (catch Throwable t {:e t}))))
    (let [{:keys [v e]} (deref p 60000 {:e (ex-info "FX thread timed out" {})})]
      (if e (throw e) v))))

(defn- allocated ^long []
  (.getCurrentThreadAllocatedBytes ^ThreadMXBean (ManagementFactory/getThreadMXBean)))

(defn- measure
  "{:us :bytes} per call of f, over n calls after warm-up, on this thread."
  [n f]
  (dotimes [_ (quot n 4)] (f))
  (let [b0 (allocated)
        t0 (System/nanoTime)]
    (dotimes [_ n] (f))
    {:us (/ (- (System/nanoTime) t0) 1000.0 n)
     :bytes (/ (- (allocated) b0) (double n))}))

(defn- busy-frame []
  (state/init-state! (domains/build-initial-state))
  (let [ctx (ct/get-cue-timing-context (ct/start-timing 0 0.0 {}) 120.0)]
    (fs/generate-frames-by-zone busy-cue 1000 120.0 0 (assoc ctx :accumulated-beats 1.3 :effective-beats 1.3))))

(deftest c8-preview-redraw-cost
  (start-toolkit!)
  (let [zone-frames (busy-frame)
        points (:all zone-frames)
        to-maps (some-> (ns-resolve 'laser-show.services.frame-service 'frame->preview-data) deref)
        ;; what each of the six cells is handed
        cells (if to-maps
                (let [frame {:points (to-maps points)}] [frame nil nil nil frame frame])
                [points nil nil nil points points])
        canvases (on-fx #(vec (repeatedly 6 (fn [] (Canvas. 128.0 175.0)))))
        redraw! (fn [] (dotimes [i 6] (preview/draw-preview (nth canvases i) (nth cells i))))
        {:keys [us bytes]} (on-fx #(measure 4000 redraw!))
        rate (if to-maps 30 60)]
    (say "c8" (if to-maps "before (point maps)" "after (point vectors)") "-"
         (count points) "points, 3 cells with them and 3 empty:"
         (format "%.0f µs and %.1f KB per redraw of the 6 cells on the FX thread" us (/ bytes 1024.0))
         (format "| at %d redraws a second: %.1f ms and %.1f MB a second" rate (/ (* us rate) 1000.0)
                 (/ (* bytes rate) 1048576.0)))
    (when to-maps
      (let [{:keys [us bytes]} (measure 4000 #(do (to-maps points) (to-maps points)))]
        (say "c8 before: the mirror's conversion to maps (points and the :all zone), every second tick on the show clock:"
             (format "%.0f µs and %.1f KB" us (/ bytes 1024.0)))))
    (let [{:keys [us bytes]} (measure 4000 #(fs/preview-data [{:cell [0 0] :zone-frames zone-frames}]))]
      (say "c8 preview-data (the tick's join of cues and zones), once per tick:"
           (format "%.0f µs and %.1f KB" us (/ bytes 1024.0))))))
