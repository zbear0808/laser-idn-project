(ns laser-show.events.registries-handlers-test
  "The effect and modulator registries as data in app state
   (plans/2026-11-23-cljfx-reactivity/17): what :registries/published
   stores, and that a code reload publishes them."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.effects.shape]
            [laser-show.animation.modulator-registry :as modulator-registry]
            [laser-show.animation.modulators]
            [laser-show.animation.registries :as registries]
            [laser-show.dev.reload :as reload]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]))

(defn- data?
  "Is x plain data all the way down: maps, collections and EDN scalars, no
   function or other object?"
  [x]
  (cond
    (map? x) (every? (fn [[k v]] (and (data? k) (data? v))) x)
    (coll? x) (every? data? x)
    :else (or (nil? x) (keyword? x) (string? x) (number? x) (boolean? x) (symbol? x) (char? x))))

(defn- run [state event]
  (:state (events/handle-event-pipeline (assoc event :state state :time 0))))

(deftest published-registries-are-data-test
  (let [{:keys [effects modulators] :as event} (registries/published-event)
        s0 (domains/build-initial-state)
        s (run s0 event)]
    (is (= {:effects {} :modulators {}} (get-in s0 [:backend :registries])) "nothing before the first")
    (is (= :registries/published (:event/type event)))
    (is (= (set (map :id (effects/list-effects))) (set (keys effects))) "every registered effect")
    (is (= (set (keys @modulator-registry/!modulators)) (set (keys modulators))) "every modulator type")
    (is (= (dissoc (effects/get-effect :scale) :apply-transducer) (:scale effects)) "an effect without its function")
    (is (= (dissoc (modulator-registry/get-modulator :envelope) :evaluator) (:envelope modulators))
        "a modulator type without its evaluator")
    (is (data? effects))
    (is (data? modulators))
    (is (= {:effects effects :modulators modulators} (get-in s [:backend :registries])))
    (testing "app state's, never the show's: the same document, no undo step, not unsaved"
      (is (= (history/extract-document-state s0) (history/extract-document-state s)))
      (is (= (:history s0) (:history s)))
      (is (not (get-in s [:project :dirty?]))))
    (testing "published again, nothing re-registered: equal"
      (is (= (get-in s [:backend :registries]) (get-in (run s (registries/published-event)) [:backend :registries]))))))

(deftest a-code-reload-publishes-the-registries-test
  (let [scale (effects/get-effect :scale)]
    (state/init-state! (domains/build-initial-state))
    (try
      (effects/register-effect! (assoc scale :name "Size"))
      (with-redefs [reload/discover-namespaces (constantly [])]
        (is (:success? (reload/reload-app-code!))))
      (is (= "Size" (get-in (state/get-raw-state) [:backend :registries :effects :scale :name]))
          "what the registries hold after the reload")
      (is (= (:effects (registries/descriptors)) (get-in (state/get-raw-state) [:backend :registries :effects])))
      (finally
        (effects/register-effect! scale)))))
