(ns laser-show.views.components.vibe-pads-test
  "A Perform pad says what its role plays by the cue's label, and renders
   again only when that label changes (follow-up R3): not when the cue is
   selected on the grid or one of its presets' parameters changes, which
   the grid cell's display data it read also held. Mounted with
   views.harness."
  (:require [clojure.test :refer [deftest is]]
            [laser-show.state.domains :as domains]
            [laser-show.state.templates :as templates]
            [laser-show.views.components.vibe-pads :as vibe-pads]
            [laser-show.views.harness :as h])
  (:import [javafx.scene.control Label]))

(def ^:private run-id #uuid "00000000-0000-0000-0000-0000000003a1")

(deftest a-pad-renders-when-its-cues-label-changes-test
  (let [state (-> (domains/build-initial-state)
                  (templates/apply-starter-cue-chains)
                  (templates/apply-starter-vibes)
                  ;; HIT plays the circle at A1, which the grid has selected
                  (assoc-in [:playback :active-cues [0 0]] {:run run-id})
                  (assoc-in [:vibe-runtime :voices :hit] {:cell [0 0] :run run-id})
                  (assoc-in [:grid :selected-cell] [0 0]))
        steps (h/each-render state h/lru-512 {:fx/type vibe-pads/pad :role :hit}
                             [#(assoc-in % [:grid :selected-cell] [1 0])
                              #(assoc-in % [:chains :cue-chains [0 0] :items 0 :params :radius] 0.8)
                              #(assoc-in % [:chains :cue-chains [0 0] :name] "Opener")]
                             (fn [{:keys [root] :as step}]
                               {:runs (count (h/ran-of step vibe-pads/pad))
                                ;; The pad shows its lines as its graphic, which a skin
                                ;; would put under it: mounted without a scene, it has none
                                :now (.getText ^Label (h/node-by-id (.getGraphic ^Label root) "vibe-pad-hit-now"))}))]
    (is (= ["circle" "circle" "circle" "Opener"] (map :now steps)))
    (is (= [1 0 0 1] (map :runs steps))
        "another cell selected, then a preset's radius: no render; the cue renamed: one")))
