(ns laser-show.vibes.library
  "Vibes: cues grouped by feel, at [:config :vibes] (saved, undoable).

   {:order [vibe-id ...] :next-id n
    :library {vibe-id vibe}
    :settings {:quick-tap-ms :lock-hold-ms :hit-taps-tempo?}}

   A vibe names cue cells per role and effect cells for its FX set:
   {:id :name :color :energy :allow-audience?
    :hit {:cells [[col row] ...] :select :shuffle|:sequence
          :mode :decay|:gate|:sustain :beats 1.0}
    :accent {...the same}
    :flash {:cue [col row] :fx [[col row] ...] :solo? bool}
    :bed [col row] or nil
    :fx [[col row] ...]}

   Pure reads; laser-show.vibes.engine plays them."
  (:require [laser-show.state.domains :as domains]
            [laser-show.timeline.playback :as playback]))

(def empty-library
  "A project's vibes before there are any (state.domains config :vibes)."
  (:vibes domains/config-initial))

(defn settings
  "The vibe settings, a key missing from the project as its default."
  [state]
  (merge (:settings empty-library) (get-in state [:config :vibes :settings])))

(defn vibe
  "The vibe with id, or nil."
  [state id]
  (get-in state [:config :vibes :library id]))

(defn current-id
  "The vibe playing: [:vibe-runtime :current] while it names one, else the
   first of :order (nothing current yet, or it was undone or deleted). nil
   without vibes."
  [state]
  (let [current (get-in state [:vibe-runtime :current])]
    (if (vibe state current)
      current
      (first (get-in state [:config :vibes :order])))))

(defn current-vibe [state]
  (vibe state (current-id state)))

(defn next-id
  "The vibe after id in :order, wrapping (the first when id isn't in it); nil
   without vibes."
  [state id]
  (let [order (get-in state [:config :vibes :order])
        after (rest (drop-while #(not= id %) order))]
    (or (first after) (first order))))


;; Who a cue reaches (V6)

(defn audience-groups
  "Zone groups that reach the audience: :crowd, and every zone group of a
   projector tagged :crowd-scanning, or of a virtual projector that is or
   whose projector is."
  [state]
  (let [projectors (:projectors state)
        crowd? (fn [p] (some #{:crowd-scanning} (:tags p)))]
    (into #{:crowd}
          (mapcat :zone-groups)
          (concat (filter crowd? (vals projectors))
                  (filter #(or (crowd? %) (crowd? (get projectors (:parent-projector-id %))))
                          (vals (:virtual-projectors state)))))))

(defn- zone-group-ids
  "Every zone group cue can send to, in any of the places the routing reads
   (routing/zone_effects.clj:127-156): its destination (:all without one,
   or with none set, as the routing falls back), and each item's, group's,
   track's and folder's :zone-group-id and the :target-zone of its zone
   selectors, on or off."
  [cue]
  (let [nodes (concat (tree-seq #(seq (:items %)) :items cue)
                      (tree-seq #(seq (:items %)) :items {:items (:tracks cue)}))]
    (into #{(or (get-in cue [:destination-zone :zone-group-id]) :all)}
          (comp (mapcat (fn [node]
                          (cons (:zone-group-id node)
                                (keep #(when (= :zone-selector (:effect-id %))
                                         (get-in % [:params :target-zone]))
                                      (:effects node)))))
                (remove nil?))
          nodes)))

(defn audience-cell?
  "Can the cue at cell reach the audience: does it name an audience group
   (audience-groups) anywhere?"
  ([state cell] (audience-cell? state cell (audience-groups state)))
  ([state cell groups]
   (boolean (some groups (zone-group-ids (get-in state [:chains :cue-chains cell]))))))

(defn playable-cells
  "Those of cells (in order, each once) vibe may start: the cue has items,
   doesn't follow MIDI timecode, and can't reach the audience, unless the
   vibe has :allow-audience? true."
  [state vibe cells]
  (let [chains (get-in state [:chains :cue-chains])
        groups (when-not (:allow-audience? vibe) (audience-groups state))]
    (into []
          (comp (distinct)
                (filter (fn [cell]
                          (let [cue (get chains cell)]
                            (and (seq (:items cue))
                                 (not (playback/chases-mtc? cue))
                                 (not (and groups (audience-cell? state cell groups))))))))
          cells)))
