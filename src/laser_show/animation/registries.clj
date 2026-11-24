(ns laser-show.animation.registries
  "The effect and modulator registries as data for app state
   (plans/2026-11-23-cljfx-reactivity/17).

   The registries (animation.effects, animation.modulator-registry) are
   namespace atoms. They change when a namespace registers into them, at
   load and again on a code reload, and no subscription sees that. So what
   the UI derives from them follows app state instead: :registries/published
   puts their descriptors, each registration without its functions, in
   [:backend :registries] (never saved, never undone), at start
   (app/start!, app/start-test!) and after a code reload
   (dev.reload/reload-app-code!). The effect banks offer what it lists
   (subs/effect-bank-categories), and the tap reactions' selectors run
   again when its modulators change (subs/modulator-descriptors). An event
   that adds an effect from a bank carries the effect's id, and its handler
   reads the definition when it runs."
  (:require [laser-show.animation.effects :as effects]
            [laser-show.animation.modulator-registry :as modulators]))

(defn- descriptor
  "A registration without its functions (an effect's :apply-transducer,
   :apply-zones and :select-map, a modulator's :evaluator): data, for app
   state."
  [registration]
  (into {} (remove (comp fn? val)) registration))

(defn descriptors
  "What is registered now: {:effects {id descriptor} :modulators {type
   descriptor}}."
  []
  {:effects (update-vals @effects/!effect-registry descriptor)
   :modulators (update-vals @modulators/!modulators descriptor)})

(defn published-event
  "The :registries/published event for what is registered now
   (descriptors)."
  []
  (assoc (descriptors) :event/type :registries/published))
