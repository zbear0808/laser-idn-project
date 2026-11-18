(ns laser-show.events.core-test
  "Tests for the event wiring in laser-show.events.core: which effects continue
   the current event inline, which call back through the dispatcher they are
   given, the no-app dispatch! fallback, and the dev-mode lost-update detector."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.tools.logging.test :as log-test]
   [laser-show.events.core :as events]
   [laser-show.events.handlers.timing :as timing]
   [laser-show.state.core :as state]
   [laser-show.state.domains :as domains]))

(use-fixtures :each (fn [f]
                      (state/init-state! (domains/build-initial-state))
                      (events/set-dispatch-fn! nil)
                      (events/set-exit-fn! nil)
                      (try (f) (finally (events/set-exit-fn! nil)))))

(defn- with-dev-property
  "Run f with the laser-show.dev system property set to value (nil clears it),
   then restore it, so these tests don't depend on how the JVM was started."
  [value f]
  (let [old (System/getProperty "laser-show.dev")]
    (if value
      (System/setProperty "laser-show.dev" value)
      (System/clearProperty "laser-show.dev"))
    (try (f)
         (finally (if old
                    (System/setProperty "laser-show.dev" old)
                    (System/clearProperty "laser-show.dev"))))))


;; Wiring


(deftest effects-and-co-effects-are-shared-maps-test
  (is (= #{:state :time} (set (keys events/co-effects))))
  (is (every? #(contains? events/effects %)
              [:state :dispatch :dispatch-later :timing/calculate-bpm :link/sync-to-downbeat
               :input/await-learn :multi-engine/start :multi-engine/stop :multi-engine/refresh
               :output/write-status
               :project/save :project/load :fx/show-file-chooser :system/exit :system/open-url
               :dev/reload :clipboard/copy-effects :clipboard/paste-effects
               :clipboard/paste-projector-effects :projectors/scan])))

(deftest dispatch-continues-inline-test
  (testing "a :dispatch follow-up runs now, in the same call, not through the dispatcher"
    (let [exits (atom 0)
          later (atom [])]
      (events/set-exit-fn! #(swap! exits inc))
      (state/swap-state! assoc-in [:ui :dialogs :unsaved-confirm]
                         {:open? true :pending {:event/type :file/exit}})
      (events/event-handler {:event/type :file/confirm-discard} #(swap! later conj %))
      (is (= 1 @exits))
      (is (= [] @later))
      (is (false? (get-in (state/get-raw-state) [:ui :dialogs :unsaved-confirm :open?]))))))

(deftest tap-tempo-bpm-continues-inline-test
  (let [later (atom [])]
    (let [now (System/currentTimeMillis)]
      (state/swap-state! assoc-in [:timing :tap-times] [(- now 800) (- now 400)]))
    (events/event-handler {:event/type :timing/tap-tempo} #(swap! later conj %))
    (is (= [] @later))
    (is (< 140.0 (get-in (state/get-raw-state) [:timing :bpm]) 160.0)
        "taps 400 ms apart")))

(deftest dispatch-later-calls-back-through-the-dispatcher-test
  (let [got (promise)]
    ((get events/effects :dispatch-later) {:event {:event/type ::later} :delay-ms 0}
                                          #(deliver got %))
    (is (= {:event/type ::later} (deref got 2000 ::timeout)))))


;; No-app fallback


(deftest fallback-runs-every-effect-test
  (testing "without an app, dispatch! runs effects such as :system/exit"
    (let [exits (atom 0)]
      (events/set-exit-fn! #(swap! exits inc))
      (events/dispatch! {:event/type :file/exit})
      (is (= 1 @exits)))))


;; Lost-update detector (dev mode)


(defn- detector-entries []
  (filterv #(and (= "laser-show.events.core" (str (:logger-ns %)))
                 (str/includes? (str (:message %)) "lost update"))
           (log-test/the-log)))

(defn- set-bpm-with-a-write-in-between!
  "Handle :timing/set-bpm while write! changes app state between the event's
   snapshot and its write-back."
  [write!]
  (let [handle timing/handle]
    (with-redefs [timing/handle (fn [event] (write!) (handle event))]
      (events/event-handler {:event/type :timing/set-bpm :bpm 128.0}))))

(deftest lost-update-detector-test
  (with-dev-property "true"
    (fn []
      (testing "a write by another writer between snapshot and reset logs a WARN"
        (log-test/with-log
          (set-bpm-with-a-write-in-between!
           #(state/assoc-in-state! [:projectors :elsewhere] {:name "written meanwhile"}))
          (let [entries (detector-entries)]
            (is (= 1 (count entries)))
            (is (= :warn (:level (first entries))))
            (is (str/includes? (:message (first entries)) ":timing/set-bpm"))
            (is (str/includes? (:message (first entries)) ":projectors")))
          (is (= 128.0 (get-in (state/get-raw-state) [:timing :bpm])))))
      (testing "only the show clock's mirror writing in between is not a WARN"
        (log-test/with-log
          (set-bpm-with-a-write-in-between!
           #(state/swap-state! (fn [s]
                                 (-> s
                                     (assoc-in [:timing :global-clock :accumulated-beats] 4.0)
                                     (assoc-in [:playback :positions [0 0]] 2.5)
                                     (assoc-in [:backend :streaming :current-frame] {:points []})))))
          (is (not-any? #(= :warn (:level %)) (detector-entries)))))
      (testing "the cues and transport are the event flow's alone now: a write to them in between is a WARN"
        (log-test/with-log
          (set-bpm-with-a-write-in-between!
           #(state/assoc-in-state! [:playback :active-cues [0 0]] {:run (random-uuid)}))
          (is (= 1 (count (filter #(= :warn (:level %)) (detector-entries)))))))
      (testing "nothing in between logs nothing"
        (log-test/with-log
          (events/event-handler {:event/type :timing/set-bpm :bpm 100.0})
          (is (empty? (detector-entries)))))
      (testing "the snapshot does not end up in app state"
        (is (empty? (meta (state/get-raw-state))))))))

(deftest lost-update-detector-is-off-outside-dev-mode-test
  (with-dev-property nil
    (fn []
      (log-test/with-log
        (set-bpm-with-a-write-in-between!
         #(state/assoc-in-state! [:projectors :elsewhere] {:name "written meanwhile"}))
        (is (empty? (detector-entries)))))))
