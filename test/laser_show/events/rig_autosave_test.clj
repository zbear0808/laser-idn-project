(ns laser-show.events.rig-autosave-test
  "The rig file follows the rig (plans/2026-11-19-zones/09 §4, §6): after
   any event that changes the rig, the pipeline asks the rig writer to save
   it; a show edit never does. Every event runs through the real pipeline
   (events/handle-event-pipeline) and only the effect it asks for is looked
   at: nothing here writes a file."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.events.core :as events]
            [laser-show.state.domains :as domains]
            [laser-show.state.history :as history]
            [laser-show.state.rig-file :as rig-file]))

(def ^:private rig-path
  "Never written: the effects are only looked at."
  (str (System/getProperty "java.io.tmpdir") "/rig-autosave-test/rig.edn"))

(defn- rigged
  "A saved show on a rig of two lasers, Laser L in Graphics, with a rig file."
  []
  (-> (domains/build-initial-state)
      (assoc :projectors {:p1 {:name "Laser L" :host "10.0.0.1" :port 7255 :enabled? true :zone-groups [:graphics]
                               :stable-id [:host "10.0.0.1" 0]}
                          :p2 {:name "Laser R" :host "10.0.0.2" :port 7255 :enabled? true :zone-groups []
                               :stable-id [:host "10.0.0.2" 0]}})
      (assoc-in [:chains :cue-chains [0 0]] {:items [{:type :preset :preset-id :circle}]})
      (assoc-in [:rig-file :path] rig-path)
      (history/mark-clean)))

(defn- handle [state event]
  (events/handle-event-pipeline (assoc event :state state :time 1000)))

(defn- run
  "The effects of event, and of what it dispatches inline, the last ones."
  [state event]
  (let [effects (handle state event)]
    (if-let [next-event (:dispatch effects)]
      (run (or (:state effects) state) next-event)
      effects)))

(defn- write [effects] (:rig/write effects))

(deftest rig-edit-requests-a-write-test
  (let [effects (handle (rigged) {:event/type :rig/set-middle :middle :both})
        s (:state effects)]
    (is (= {:path rig-path :doc (rig-file/rig-document s) :seq 1} (write effects)))
    (is (= :both (get-in (write effects) [:doc :rig :middle])) "the new rig")
    (is (= 1 (get-in s [:rig-file :requested])) "counted, so the line says Saving rig… until it is written")
    (is (= {:graphics (get-in s [:zone-groups :graphics])} (get-in s [:rig-file :group-copy]))
        "the group copy it wrote is kept")
    (is (false? (get-in s [:project :dirty?])) "and the show isn't unsaved")
    (is (= ["Change Middle Laser"] (mapv :description (get-in s [:history :undo-stack]))) "but it is undoable")
    (testing "every rig path: a projector, a virtual projector, the calibration"
      (doseq [event [{:event/type :projectors/update-settings :projector-id :p1 :updates {:name "Stage L"}}
                     {:event/type :projectors/set-enabled :projector-id :p2 :enabled? false}
                     {:event/type :projectors/set-safety :projector-id :p1 :safety {:limit [-0.5 -0.5 0.5 0.5]}}
                     {:event/type :projectors/toggle-zone-group :projector-id :p2 :zone-group-id :crowd}
                     {:event/type :projectors/add-effect :projector-id :p1 :effect {:effect-id :rgb-curves}}]]
        (is (some? (write (handle (rigged) event))) (str (:event/type event)))))
    (testing "the next one counts on"
      (is (= 2 (:seq (write (handle s {:event/type :rig/set-split-width :split-width 0.6}))))))))

(deftest show-edit-requests-no-write-test
  (doseq [event [{:event/type :timing/set-bpm :bpm 128.0}
                 {:event/type :grid/clear-cell :col 0 :row 0}
                 {:event/type :ui/set-active-tab :tab-id :projectors}
                 {:event/type :zone-groups/save-edit :group-id :crowd :name "Audience" :description "" :color "#E67E22"}]]
    (let [effects (handle (rigged) event)]
      (is (some? (:state effects)) (str (:event/type event)))
      (is (nil? (write effects)) (str (:event/type event) ": the show's, not the rig's"))))
  (testing "a write's own result doesn't ask for another"
    (is (nil? (write (handle (rigged) {:event/type :rig/written :path rig-path :seq 1 :at 2000}))))))

(deftest undo-of-a-rig-edit-requests-a-write-test
  (let [moved (:state (handle (rigged) {:event/type :rig/move-projector :projector-id :p2 :target :p1 :side :before}))
        undone (handle moved {:event/type :edit/undo})]
    (is (= [:p2 :p1] (get-in moved [:rig :order])))
    (is (= [] (get-in (write undone) [:doc :rig :order])) "the file follows the undo")
    (is (= [:p2 :p1] (get-in (write (handle (:state undone) {:event/type :edit/redo})) [:doc :rig :order])) "and the redo")))

(deftest discovery-requests-a-write-test
  (let [effects (handle (rigged) {:event/type :projectors/scan-complete
                                  :devices [{:address "10.0.0.9" :host-name "dac" :unit-id "AA BB" :port 7255
                                             :services [{:service-id 1 :name "Head 1"}]}]})]
    (is (contains? (get-in (write effects) [:doc :projectors]) :projector-10-0-0-9-1) "a new laser reaches the file")
    (is (empty? (get-in effects [:state :history :undo-stack])) "with no undo step (invariant 10)")
    (testing "a scan that finds nothing new asks for nothing"
      (is (nil? (write (handle (rigged) {:event/type :projectors/scan-complete :devices []})))))))

(deftest member-group-rename-requests-a-write-test
  (let [effects (handle (rigged) {:event/type :zone-groups/save-edit :group-id :graphics
                                  :name "Gfx" :description "" :color "#9B59B6"})]
    (is (= "Gfx" (get-in (write effects) [:doc :zone-groups :graphics :name])) "Laser L is in it: the copy follows")
    (is (= "Gfx" (get-in effects [:state :rig-file :group-copy :graphics :name])))
    (is (true? (get-in effects [:state :project :dirty?])) "the definition is the show's: unsaved too")))

(deftest read-only-rig-requests-no-write-test
  (let [read-only (assoc-in (rigged) [:rig-file :read-only?] true)
        effects (handle read-only {:event/type :rig/set-middle :middle :both})]
    (is (= :both (get-in effects [:state :rig :middle])) "the edit still applies here")
    (is (nil? (write effects)) "but another window owns the file")
    (is (= {:state read-only} (select-keys (handle read-only {:event/type :rig/retry-write}) [:state :rig/write]))
        "and Retry does nothing")))

(deftest no-path-requests-no-write-test
  (let [no-file (assoc-in (rigged) [:rig-file :path] nil)]
    (is (nil? (write (handle no-file {:event/type :rig/set-middle :middle :both}))) "tests and the REPL: no rig file")
    (is (= 0 (get-in (domains/build-initial-state) [:rig-file :requested])))
    (is (nil? (get-in (domains/build-initial-state) [:rig-file :path])) "none unless the app sets one")))

(deftest write-failure-shows-a-notice-and-retries-test
  (let [asked (:state (handle (rigged) {:event/type :rig/set-middle :middle :both}))
        failed (handle asked {:event/type :rig/write-failed :path rig-path :seq 1 :error "No space left on device"})
        s (:state failed)]
    (is (= {:status :write-failed :error "No space left on device" :written 1}
           (select-keys (:rig-file s) [:status :error :written])))
    (is (= {:text "Rig not saved: No space left on device" :level :error} (dissoc (get-in s [:ui :notice]) :id)))
    (is (= :ui/clear-notice (get-in failed [:dispatch-later :event :event/type])))
    (let [retried (handle s {:event/type :rig/retry-write})]
      (is (= {:path rig-path :doc (rig-file/rig-document s) :seq 2 :now? true} (write retried))
          "Retry: the rig as it is now, written at once")
      (testing "written: saved, at that time"
        (let [saved (:state (handle (:state retried) {:event/type :rig/written :path rig-path :seq 2 :at 5000}))]
          (is (= {:status :saved :error nil :written-at 5000 :written 2}
                 (select-keys (:rig-file saved) [:status :error :written-at :written])))
          (testing "and an Exit waiting for it carries on"
            (is (= {:event/type :file/exit :force? true}
                   (:dispatch (handle (:state retried) {:event/type :rig/written :path rig-path :seq 2 :at 5000
                                                        :then {:event/type :file/exit :force? true}}))))))))
    (testing "a result for another path (a test before, a window before) changes nothing"
      (is (= {:state s} (handle s {:event/type :rig/written :path "/elsewhere/rig.edn" :seq 9 :at 1})))
      (is (= {:state s} (handle s {:event/type :rig/write-failed :path "/elsewhere/rig.edn" :seq 9 :error "x"}))))))

(deftest startup-notice-is-shown-test
  (let [effects (handle (rigged) {:event/type :rig/startup-notice :text "Your rig file couldn't be read." :level :warning})]
    (is (= {:text "Your rig file couldn't be read." :level :warning} (dissoc (get-in effects [:state :ui :notice]) :id)))
    (is (= {:state (rigged)} (handle (rigged) {:event/type :rig/startup-notice})) "called bare: nothing")))
