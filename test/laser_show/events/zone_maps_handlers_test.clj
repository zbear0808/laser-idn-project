(ns laser-show.events.zone-maps-handlers-test
  "The zones, the show default and the zone maps' events (zones plan Z4, 06
   §6)."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.zone-maps :as zone-maps]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]))

(defn- fresh [] (history/mark-clean (domains/build-initial-state)))

(defn- run
  "state after event through the real pipeline (the history and the
   unsaved flag included), as the agent runs it."
  [state event]
  (or (:state (events/handle-event-pipeline (assoc event :state state :time 1000))) state))

(deftest set-default-target-test
  (let [s (run (fresh) {:event/type :zones/set-default-target :target [:zone/b :zone/a]})]
    (is (= [:zone/a :zone/b] (get-in s [:config :default-target])) "in normal form")
    (is (= "Change Show Target" (history/undo-label (:history s))) "an undo step")
    (is (true? (get-in s [:project :dirty?])) "unsaved")
    (is (nil? (get-in (run s {:event/type :zones/set-default-target :target nil}) [:config :default-target]))
        "none: All")
    (is (= :graphics (get-in (zone-maps/handle {:event/type :zones/set-default-target :fx/event {:id :graphics} :state (fresh)})
                             [:state :config :default-target]))
        "from a picker")))
