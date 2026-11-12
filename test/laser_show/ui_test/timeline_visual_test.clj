(ns laser-show.ui-test.timeline-visual-test
  "Opt-in screenshot baseline for the timeline editor (plans/2026-11-08-
   timeline-rework/05-visual-design.md §4). Skipped unless
   LASER_SHOW_VISUAL=1 is set:
   
     LASER_SHOW_VISUAL=1 clj -M:test-ui:<platform>:test-ui-headless timeline-screenshot
   
   It writes PNGs to test-output/timeline/ for manual (or LLM) review against
   the acceptance checklist; it asserts nothing about pixels."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [laser-show.animation.effects :as effects]
            [laser-show.events.core :as events]
            [laser-show.input.mtc :as mtc]
            [laser-show.services.frame-service :as frame-service]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.application Platform]
           [javafx.stage Stage Window]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(def ^:private out-dir "test-output/timeline/")

(defn- enabled? [] (= "1" (System/getenv "LASER_SHOW_VISUAL")))

(defn- resize-editor! [w h]
  (let [done (promise)]
    (Platform/runLater
     (fn []
       (doseq [^Window win (Window/getWindows)]
         (when (and (instance? Stage win) (some-> (.getTitle ^Stage win) (.startsWith "Timeline")))
           (.setWidth win (double w))
           (.setHeight win (double h))))
       (deliver done true)))
    @done
    (ui/sync-fx!)))

(defn- cue [] (get-in (state/get-raw-state) [:chains :cue-chains [2 2]]))

(defn- leaf-ids []
  (->> (tree-seq #(= :group (:type %)) :items {:type :group :items (:tracks (cue))})
       (filter #(= :track (:type %)))
       (map :id)))

(defn- shot! [name]
  (Thread/sleep 300)
  (ui/sync-fx!)
  (ui/screenshot! ".dialog-content" (str out-dir name ".png")))

(deftest ^:visual timeline-screenshot-baseline
  (if-not (enabled?)
    (is true "skipped: set LASER_SHOW_VISUAL=1 to take the screenshots")
    (do
      (events/dispatch! {:event/type :timeline-cue-editor/open :col 2 :row 2})
      (ui/wait-for! #(some (fn [t] (and t (.startsWith ^String t "Timeline Editor"))) (ui/window-titles)))
      (resize-editor! 1400 820)
      (shot! "01-empty")
      (dotimes [_ 2] (events/dispatch! {:event/type :timeline/add-track :col 2 :row 2}))
      (ui/wait-for! #(= 3 (count (leaf-ids))))
      (let [[t1 t2 t3] (leaf-ids)]
        (doseq [[track preset start] [[t1 :circle 0.0] [t1 :star 2.0] [t1 :square 8.0]
                                      [t2 :line 4.0] [t2 :spiral 12.0]
                                      [t3 :triangle 0.0] [t3 :wave 6.0]]]
          (events/dispatch! {:event/type :timeline/add-preset-to-track :col 2 :row 2
                             :item-id preset :track-id track :start start}))
        (ui/wait-for! #(= 7 (count (:items (cue)))))
        (events/dispatch! {:event/type :timeline/set-track-muted :col 2 :row 2 :track-id t3 :muted? true})
        (events/dispatch! {:event/type :timeline/set-cursor-beat :col 2 :row 2 :beat 10.0})
        (events/dispatch! {:event/type :timeline/toggle-loop :col 2 :row 2})
        (events/dispatch! {:event/type :timeline/select-track :col 2 :row 2 :track-id t2})
        (ui/wait-for! #(true? (get-in (cue) [:loop :enabled?])))
        (shot! "02-content")
        (state/swap-state! assoc-in [:playback :active-cues [2 2]] {:accumulated-beats 6.0})
        (shot! "03-playing")
        (state/swap-state! update-in [:playback :active-cues] dissoc [2 2])
        (ui/click! (ui/robot) "#timeline-add-content")
        (shot! "04-drawer")
        ;; No loop: the timeline stops at its end, shown by the end marker
        (events/dispatch! {:event/type :timeline/toggle-loop :col 2 :row 2})
        (events/dispatch! {:event/type :timeline/apply-op :col 2 :row 2 :op {:op :set-tempo :bpm 120.0}})
        (ui/wait-for! #(false? (get-in (cue) [:loop :enabled?])))
        (shot! "05-end-marker")
        (events/dispatch! {:event/type :timeline/set-ruler-mode :mode :time})
        (shot! "06-time-ruler")
        ;; Following MIDI timecode: 00:00:01:00 at 25 fps
        (events/dispatch! {:event/type :timeline/clear-selection :col 2 :row 2})
        (events/dispatch! {:event/type :timeline/select-track :col 2 :row 2 :track-id nil})
        (events/dispatch! {:event/type :timeline/apply-op :col 2 :row 2 :op {:op :set-chase :source :mtc}})
        (ui/wait-for! #(= :mtc (:timeline/chase (cue))))
        (mtc/reset-decoder!)
        (doseq [b [0x00 0x10 0x21 0x30 0x40 0x50 0x60 0x72]]
          (mtc/on-quarter-frame! b))
        (frame-service/update-timing-accumulators! (System/currentTimeMillis))
        (ui/wait-for! #(ui/visible? "#timeline-mtc-rate-warning"))
        (shot! "07-mtc-chase")
        (mtc/reset-decoder!)
        (events/dispatch! {:event/type :timeline/apply-op :col 2 :row 2 :op {:op :set-chase :source nil}})
        ;; Effects: two on a clip, one on a track, shown in the bottom panel
        (let [circle (:id (first (:items (cue))))
              fx-def effects/get-effect]
          (events/dispatch! {:event/type :timeline/select-items :col 2 :row 2 :ids #{circle}})
          (doseq [e [:scale :rotation]]
            (events/dispatch! {:event/type :timeline/add-effect-to-item :col 2 :row 2
                               :item-id e :item (fx-def e) :target-item-id circle}))
          (ui/wait-for! #(= 2 (count (:effects (first (:items (cue)))))))
          (shot! "08-clip-effects")
          (events/dispatch! {:event/type :timeline/clear-selection :col 2 :row 2})
          (events/dispatch! {:event/type :timeline/select-track :col 2 :row 2 :track-id t1})
          (events/dispatch! {:event/type :timeline/add-effect-to-item :col 2 :row 2
                             :item-id :scale :item (fx-def :scale) :target-track-id t1})
          (ui/wait-for! #(ui/visible? ".tl-device"))
          (shot! "09-track-effects")))
      (is true))))
