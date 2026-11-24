(ns laser-show.events.handlers.registries
  "The effect and modulator registries' descriptors in app state
   (laser-show.animation.registries, plans/2026-11-23-cljfx-reactivity/17).
   Pure. What it writes is under [:backend :registries]: never saved and
   never undone ([:backend] isn't a document root).

   - :registries/published {:effects :modulators}: what is registered now
     (animation.registries/published-event), at start and after a code
     reload")

(defn- handle-published
  "Store what is registered: :effects {id descriptor} and :modulators {type
   descriptor}, in place of what was."
  [{:keys [effects modulators state]}]
  {:state (update-in state [:backend :registries] assoc
                     :effects (or effects {})
                     :modulators (or modulators {}))})

(defn handle
  "Dispatch :registries/* events to their handlers."
  [{:keys [event/type] :as event}]
  (case type
    :registries/published (handle-published event)
    {}))
