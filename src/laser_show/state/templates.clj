(ns laser-show.state.templates
  "Project templates for initializing state with starter content.
   
   These functions generate fresh UUIDs each time they're called,
   avoiding the hard-coded UUID anti-pattern.
   
   Use these when:
   - Creating a new project
   - Resetting to a 'starter' state
   - Providing demo content for new users"
  (:require [laser-show.animation.cue-chains :as cue-chains]))


;; Starter Cue Chains


(defn create-starter-cue-chains
  "Create starter cue chains for a new project.
   
   Generates a row of basic shape presets with fresh UUIDs.
   Returns a map of [col row] -> {:items [...]} suitable for
   merging into [:chains :cue-chains].
   
   Example:
   {[0 0] {:items [{:type :preset :id (random-uuid) :preset-id :circle ...}]}
    [1 0] {:items [{:type :preset :id (random-uuid) :preset-id :square ...}]}
    ...}"
  []
  {;; Row 0: Basic shapes
   [0 0] {:items [(cue-chains/create-preset-instance :circle)]}
   [1 0] {:items [(cue-chains/create-preset-instance :square)]}
   [2 0] {:items [(cue-chains/create-preset-instance :triangle)]}
   [3 0] {:items [(cue-chains/create-preset-instance :star)]}
   [4 0] {:items [(cue-chains/create-preset-instance :spiral)]}
   [5 0] {:items [(cue-chains/create-preset-instance :wave)]}
   [6 0] {:items [(cue-chains/create-preset-instance :beam-fan)]}
   [7 0] {:items [(cue-chains/create-preset-instance :rainbow-circle)]}})


(defn apply-starter-cue-chains
  "Apply starter cue chains to a state map if cue-chains is empty.
   
   Use this during app initialization to populate a fresh project
   with demo content. Does nothing if cue-chains already has content
   (e.g., when loading an existing project).
   
   Parameters:
   - state: The current state map
   
   Returns: Updated state map with starter cue chains (or unchanged if not empty)"
  [state]
  (let [current-cue-chains (get-in state [:chains :cue-chains] {})]
    (if (empty? current-cue-chains)
      (assoc-in state [:chains :cue-chains] (create-starter-cue-chains))
      state)))
