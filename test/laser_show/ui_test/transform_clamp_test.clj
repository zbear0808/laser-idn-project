(ns laser-show.ui-test.transform-clamp-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [laser-show.animation.effects :as effects]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.state.persistent :as persistent]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.scene.control TitledPane]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(deftest advanced-clamp-is-collapsed-and-edits-only-the-selected-transform-test
  (let [robot (ui/robot)
        items-path [:chains :effect-chains [0 3] :items]
        items #(get-in (state/get-raw-state) items-path)
        first-scale {:id :up :effect-id :scale :enabled? true :params {:x-scale 2.0}}
        half {:id :down :effect-id :scale :enabled? true :params {:x-scale 0.5}}
        result-x #(ffirst (effects/apply-effect-chain [[0.75 0.0 1.0 0.0 0.0]] {:effects (items)}))
        select! (fn [id] (events/dispatch! {:event/type :list/select-item :item-id id :mode :single
                                             :component-id [:effect-chain 0 3] :items-path items-path}))
        pane "#transform-advanced-up"
        box "#clamp-after-transform-up"]
    (events/write-state! assoc-in items-path [first-scale half {:id :brightness :effect-id :intensity}])
    (events/dispatch! {:event/type :effect-chain/open-editor :col 0 :row 3})
    (select! :up)
    (ui/wait-for! #(some? (ui/query pane)))
    (is (false? (ui/on-fx-thread #(.isExpanded ^TitledPane (ui/query pane)))))
    (is (= 0.5 (result-x)))
    (is (not (contains? (first (items)) :clamp-after?)) "opening an old show does not write a default")
    (ui/click! robot (str pane " > .title"))
    (ui/assert-selected! box)
    (ui/click! robot box)
    (ui/wait-for! #(= 0.75 (result-x)))
    (is (= half (second (items))))
    (is (false? (get-in (persistent/project-files (state/get-raw-state))
                        (into ["content.edn"] (conj items-path 0 :clamp-after?)))))
    (events/dispatch! {:event/type :edit/undo})
    (ui/wait-for! #(= 0.5 (result-x)))
    (ui/assert-selected! box)
    (select! :down)
    (ui/wait-for! #(some? (ui/query "#transform-advanced-down")))
    (is (false? (ui/on-fx-thread #(.isExpanded ^TitledPane (ui/query "#transform-advanced-down")))))
    (is (nil? (ui/query pane)))
    (select! :brightness)
    (ui/wait-for! #(empty? (ui/query-all ".transform-advanced")))))
