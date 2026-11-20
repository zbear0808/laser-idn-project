(ns laser-show.state.templates
  "Project templates for initializing state with starter content.
   
   These functions generate fresh UUIDs each time they're called,
   avoiding the hard-coded UUID anti-pattern.
   
   Use these when:
   - Creating a new project
   - Resetting to a 'starter' state
   - Providing demo content for new users"
  (:require [laser-show.animation.cue-chains :as cue-chains]
            [laser-show.vibes.faders :as faders]
            [laser-show.vibes.library :as library]))


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
  {;; Row 0: Basic shapes - all default to "All" zone group
   [0 0] {:items [(cue-chains/create-preset-instance :circle)]
          :destination-zone {:zone-group-id :all}}
   [1 0] {:items [(cue-chains/create-preset-instance :square)]
          :destination-zone {:zone-group-id :all}}
   [2 0] {:items [(cue-chains/create-preset-instance :triangle)]
          :destination-zone {:zone-group-id :all}}
   [3 0] {:items [(cue-chains/create-preset-instance :star)]
          :destination-zone {:zone-group-id :all}}
   [4 0] {:items [(cue-chains/create-preset-instance :spiral)]
          :destination-zone {:zone-group-id :all}}
   [5 0] {:items [(cue-chains/create-preset-instance :wave)]
          :destination-zone {:zone-group-id :all}}
   [6 0] {:items [(cue-chains/create-preset-instance :beam-fan)]
          :destination-zone {:zone-group-id :all}}
   [7 0] {:items [(cue-chains/create-preset-instance :horizontal-line)]
          :destination-zone {:zone-group-id :all}}})


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


;; Starter Vibes


(defn create-starter-vibes
  "Two vibes over the starter cue chains (create-starter-cue-chains), for
   [:config :vibes]: \"Shapes\" hits circle, square, triangle and star,
   accents with the spiral and flashes the beam fan; \"Lines\" hits the wave
   and the line, accents with the beam fan and flashes the spiral."
  []
  {:order [:vibe-1 :vibe-2]
   :next-id 3
   :library
   {:vibe-1 {:id :vibe-1 :name "Shapes" :color "#4A90D9" :energy 2 :allow-audience? false
             :hit {:cells [[0 0] [1 0] [2 0] [3 0]] :select :shuffle :mode :decay :beats 1.0}
             :accent {:cells [[4 0]] :select :shuffle :mode :decay :beats 2.0}
             :flash {:cue [6 0] :fx [] :solo? true}
             :bed nil :fx []}
    :vibe-2 {:id :vibe-2 :name "Lines" :color "#9B59B6" :energy 3 :allow-audience? false
             :hit {:cells [[5 0] [7 0]] :select :shuffle :mode :decay :beats 1.0}
             :accent {:cells [[6 0]] :select :shuffle :mode :decay :beats 2.0}
             :flash {:cue [4 0] :fx [] :solo? true}
             :bed nil :fx []}}
   :settings (:settings library/empty-library)})

(defn apply-starter-vibes
  "Apply the starter vibes to a state map if it has no vibes, keeping its
   vibe settings. Use it with apply-starter-cue-chains: the vibes name the
   starter cues."
  [state]
  (if (empty? (get-in state [:config :vibes :library]))
    (update-in state [:config :vibes] #(merge (create-starter-vibes) (select-keys % [:settings])))
    state))


;; Starter Effects


(defn create-starter-effects
  "The effect cells a new project starts with, for [:chains :effect-chains]:
   the Master at [0 3] (vibes.faders/master-cell), on, whose Intensity
   follows fader 1 and is full until the fader first moves (V5 A)."
  []
  {[0 3] (faders/master-cell)})

(defn apply-starter-effects
  "Apply the starter effect cells to a state map if its effects grid is
   empty: a new project. A loaded one keeps its own (even none)."
  [state]
  (if (empty? (get-in state [:chains :effect-chains]))
    (assoc-in state [:chains :effect-chains] (create-starter-effects))
    state))
