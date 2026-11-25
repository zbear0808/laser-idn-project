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
            [laser-show.state.persistent :as persistent]
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


;; A group only the rig defines comes back with its last member (reactivity
;; plan 23): its definition goes with the undo step that can bring the
;; membership back

(def ^:private balcony
  "A: the rig's copy's definition of a group the open show doesn't define."
  {:id :group-balcony :name "Balcony" :description "House" :color "#2468AC"})

(def ^:private balcony-b
  "B: a show's group with the same id and name."
  {:id :group-balcony :name "Balcony" :description "Show" :color "#CC4400"})

(defn- balcony-rig
  "A saved show that doesn't define Balcony, on a rig whose one laser, :p
   (off), is in it: the rig's copy knows A. The rig file is at path (nil:
   none), read only with :read-only? true."
  ([] (balcony-rig rig-path))
  ([path & {:keys [read-only?]}]
   (-> (domains/build-initial-state)
       (assoc :projectors {:p {:name "Balcony L" :host "10.0.0.7" :port 7255 :enabled? false
                               :zone-groups [:group-balcony] :stable-id [:host "10.0.0.7" 0]}})
       (assoc-in [:chains :cue-chains [0 0]] {:items [{:type :preset :preset-id :circle}]})
       (update :rig-file assoc :path path :read-only? (boolean read-only?) :group-copy {:group-balcony balcony})
       (history/mark-clean))))

(defn- copy [state] (get-in state [:rig-file :group-copy]))

(defn- stacks [state] [(count (get-in state [:history :undo-stack])) (count (get-in state [:history :redo-stack]))])

(defn- steps
  "The effects of each event, each on the state the one before left."
  [state events]
  (rest (reductions (fn [effects event] (handle (:state effects) event)) {:state state} events)))

(def ^:private undo {:event/type :edit/undo})
(def ^:private redo {:event/type :edit/redo})
(def ^:private remove-p {:event/type :projectors/remove :projector-id :p})

(deftest last-rig-only-member-round-trips-through-undo-redo-test
  (let [[_ undone :as all] (steps (balcony-rig) [remove-p undo redo undo])
        states (map :state all)]
    (is (= [[1 {}] [2 {:group-balcony balcony}] [3 {}] [4 {:group-balcony balcony}]]
           (map (comp (juxt :seq (comp :zone-groups :doc)) write) all))
        "each request's number and definitions: A comes back with its member")
    (is (= [nil [:group-balcony] nil [:group-balcony]]
           (map #(get-in % [:projectors :p :zone-groups]) states)
           (map #(get-in (write %) [:doc :projectors :p :zone-groups]) all))
        "the membership, here and in the file")
    (is (= [{} {:group-balcony balcony} {} {:group-balcony balcony}] (map copy states)) "the local copy")
    (is (= [[1 0] [0 1] [1 0] [0 1]] (map stacks states)))
    (is (= [false false false false] (map #(get-in % [:project :dirty?]) states)) "the show stays saved")
    (is (= [{:group-id :group-balcony :name "Balcony" :lasers 1}]
           (:rig-groups-not-in-show (rig-file/match-report (:state undone) {} {})))
        "the report names it again")
    (testing "the removal's write result, delivered after Undo"
      (let [late (handle (:state undone) {:event/type :rig/written :path rig-path :seq 1 :at 2000})]
        (is (= {:group-balcony balcony} (copy (:state late))) "can't change A")
        (is (nil? (write late)) "asks for no other write")
        (is (= [0 1] (stacks (:state late))) "and is no undo step")))
    (testing "an explicit Retry writes A and the membership as they are now, once"
      (let [retried (handle (:state undone) {:event/type :rig/retry-write})]
        (is (= [3 true {:group-balcony balcony} [:group-balcony]]
               ((juxt :seq :now? (comp :zone-groups :doc) (comp :zone-groups :p :projectors :doc)) (write retried))))
        (is (= 3 (get-in retried [:state :rig-file :requested])))))
    (testing "with an unsaved show edit before it, the show stays unsaved"
      (let [edited (:state (handle (balcony-rig) {:event/type :grid/clear-cell :col 0 :row 0}))
            states (map :state (steps edited [remove-p undo redo]))]
        (is (true? (get-in edited [:project :dirty?])))
        (is (= [true true true] (map #(get-in % [:project :dirty?]) states)))
        (is (= [{} {:group-balcony balcony} {}] (map copy states)))
        (is (nil? (get-in (second states) [:chains :cue-chains [0 0]])) "the show edit stays")))))

(def ^:private press #uuid "00000000-0000-0000-0000-0000000023a1")

(deftest last-virtual-member-is-restored-on-abort-test
  (let [s0 (-> (balcony-rig)
               (assoc :virtual-projectors {:vp {:name "Balcony VP" :parent-projector-id :p :zone-groups [:group-balcony]}})
               (history/mark-clean))
        left (:state (handle s0 {:event/type :projectors/toggle-zone-group :projector-id :p :zone-group-id :group-balcony}))
        removed (handle left remove-p)
        vp-groups #(get-in % [:virtual-projectors :vp :zone-groups])]
    (is (= [[] [:group-balcony]] ((juxt #(get-in % [:projectors :p :zone-groups]) vp-groups) left)))
    (is (= {:group-balcony balcony} (copy left)) "still listed, by the virtual output")
    (is (= {} (copy (:state removed)) (get-in (write removed) [:doc :zone-groups])) "its parent's removal took the last member")
    (is (nil? (vp-groups (:state removed))))
    (testing "Undo brings the virtual membership and A back together"
      (let [undone (handle (:state removed) undo)]
        (is (= [:group-balcony] (vp-groups (:state undone))))
        (is (= {:group-balcony balcony} (copy (:state undone)) (get-in (write undone) [:doc :zone-groups])))))
    (testing "a press whose edit removes it, aborted: the same, and no undo step"
      (let [[begun during aborted] (steps left [{:event/type :history/begin-transaction :label "Remove" :history/gesture-id press}
                                                (assoc remove-p :history/gesture-id press)
                                                {:event/type :history/abort-transaction :history/gesture-id press}])]
        (is (= press (history/gesture-id (:history (:state begun)))))
        (is (= {} (copy (:state during))))
        (is (= [:group-balcony] (vp-groups (:state aborted))))
        (is (= {:group-balcony balcony} (copy (:state aborted)) (get-in (write aborted) [:doc :zone-groups])))
        (is (= [1 0] (stacks left) (stacks (:state during)) (stacks (:state aborted))) "the toggle's step only")
        (is (nil? (get-in (:state aborted) [:history :transaction])))))))

(deftest undo-that-asks-first-installs-no-definition-test
  (let [live (-> (balcony-rig)
                 (update-in [:projectors :p] assoc :enabled? true :safety {:limit [-0.5 -0.5 0.5 0.5] :blocked []})
                 (assoc-in [:backend :streaming :running?] true))
        ;; One step: a press narrowed Balcony L's area and took it out of Balcony
        edited (:state (last (steps live [{:event/type :history/begin-transaction :label "Edit" :history/gesture-id press}
                                          {:event/type :projectors/set-safety :projector-id :p
                                           :safety {:limit [-0.3 -0.5 0.5 0.5]} :history/gesture-id press}
                                          {:event/type :projectors/toggle-zone-group :projector-id :p
                                           :zone-group-id :group-balcony :history/gesture-id press}
                                          {:event/type :history/commit-transaction :history/gesture-id press}])))
        asked (handle edited undo)]
    (is (= [{} [1 0]] [(copy edited) (stacks edited)]))
    (is (true? (get-in asked [:state :ui :dialogs :confirm :open?])) "Undo would let light reach more while it is on: it asks")
    (is (= [{} [1 0]] [(copy (:state asked)) (stacks (:state asked))]) "and neither installs A nor takes the step")
    (is (nil? (write asked)) "nor asks for a write")
    (testing "Allow: the step, its membership and A"
      (let [allowed (run (:state asked) {:event/type :ui/confirm
                                         :review-id (get-in asked [:state :ui :dialogs :confirm :safety-review :id])})]
        (is (= [:group-balcony] (get-in allowed [:state :projectors :p :zone-groups])))
        (is (= [{:group-balcony balcony} [0 1]] [(copy (:state allowed)) (stacks (:state allowed))]))
        (is (= {:group-balcony balcony} (get-in (write allowed) [:doc :zone-groups])))))))

(def ^:private upstairs
  "Another group only the rig defines, named Balcony too, with an id of its
   own."
  {:id :group-balcony-2 :name "Balcony" :description "Upstairs" :color "#88AA22"})

(deftest same-id-show-edit-restores-the-old-rig-definition-test
  (let [create {:event/type :zone-groups/create-new :name "Balcony" :description "Show" :color "#CC4400"}
        both (fn [g] {:group-balcony g :group-balcony-2 upstairs})]
    (doseq [[mode s0] [["a rig file" (balcony-rig)]
                       ["read only" (balcony-rig rig-path :read-only? true)]
                       ["no rig file" (balcony-rig nil)]]]
      (testing mode
        (let [s0 (-> s0
                     (assoc-in [:projectors :q] {:name "Upstairs L" :host "10.0.0.8" :port 7255 :enabled? false
                                                 :zone-groups [:group-balcony-2] :stable-id [:host "10.0.0.8" 0]})
                     (assoc-in [:rig-file :group-copy :group-balcony-2] upstairs)
                     (history/mark-clean))
              all (steps s0 [create undo redo])
              row (fn [{s :state}] [(select-keys (:zone-groups s) [:group-balcony :group-balcony-2])
                                    (rig-file/group-copy s)
                                    (get-in s [:project :dirty?])])]
          (is (= [[{:group-balcony balcony-b} (both balcony-b) true]
                  [{} (both balcony) false]
                  [{:group-balcony balcony-b} (both balcony-b) true]]
                 (map row all))
              "the show's B over the rig's id, then the rig's A again, then B; the other Balcony is another id")
          (is (= (map (comp rig-file/group-copy :state) all) (map (comp copy :state) all)) "and the copy kept is that one")
          (is (= (if (= "a rig file" mode) [1 2 3] [nil nil nil]) (map (comp :seq write) all))
              "a request only for a rig file this window may write"))))
    (testing "the report's Add on A: a show edit the rig file needn't follow"
      (let [added (handle (balcony-rig) {:event/type :rig/add-groups-to-show :group-ids [:group-balcony]})]
        (is (= balcony (get-in added [:state :zone-groups :group-balcony])))
        (is (true? (get-in added [:state :project :dirty?])))
        (is (nil? (write added)))
        (is (= {:group-balcony balcony} (copy (:state added))))))))

(defn- incoming-zip
  "What Open reads of a show made on another rig: its laser (off), order and
   calibration aren't this rig's; groups go into its show's."
  [groups]
  (persistent/project-files
   (-> (domains/build-initial-state)
       (assoc :projectors {:other {:name "Other L" :host "10.9.9.9" :port 7255 :enabled? false :zone-groups []
                                   :stable-id [:host "10.9.9.9" 0]}})
       (assoc :rig {:order [:other] :middle :none :split-width 0.9})
       (assoc-in [:chains :projector-effects :other] {:items [{:effect-id :corner-pin :id #uuid "23232323-2323-2323-2323-232323232323"}]})
       (assoc-in [:chains :cue-chains [1 1]] {:items [{:type :preset :preset-id :star}]})
       (update :zone-groups merge groups))))

(defn- open-zip [state contents]
  (handle state {:event/type :file/project-loaded :file-path "/shows/incoming.zip" :contents contents}))

(deftest new-and-open-start-from-the-restored-rig-test
  (let [[undone redone] (map :state (rest (steps (balcony-rig) [remove-p undo redo])))
        fresh? (fn [s] (and (= [0 0] (stacks s)) (nil? (get-in s [:history :transaction])) (false? (get-in s [:project :dirty?]))))
        same-rig? (fn [before after] (every? #(identical? (get-in before %) (get-in after %)) rig-file/rig-paths))
        report (fn [s] (let [{:keys [hardware chains]} (get-in s [:ui :dialogs :rig-report])]
                         (rig-file/match-report s hardware chains)))]
    (testing "the show saved after Undo: the membership in its copy of the rig, no Balcony in its groups, nothing of
              the rig file or the undo steps"
      (let [files (persistent/project-files undone)
            ks (set (filter keyword? (tree-seq coll? seq files)))]
        (is (= [:group-balcony] (get-in files ["hardware.edn" :projectors :p :zone-groups])))
        (is (not (contains? (get-in files ["hardware.edn" :zone-groups]) :group-balcony)))
        (is (not-any? ks [:rig-file :rig-only-group-definitions :group-copy]))))
    (testing "New after Undo brings Balcony into the new show; New after Redo doesn't"
      (let [after-undo (handle undone {:event/type :file/new-project})
            after-redo (handle redone {:event/type :file/new-project})]
        (is (= balcony (get-in after-undo [:state :zone-groups :group-balcony])))
        (is (= #{:graphics :crowd} (set (keys (get-in after-redo [:state :zone-groups])))) "the defaults only")
        (is (= [true true] [(fresh? (:state after-undo)) (fresh? (:state after-redo))]))
        (is (= [true true] [(same-rig? undone (:state after-undo)) (same-rig? redone (:state after-redo))]))
        (is (= [nil nil] [(write after-undo) (write after-redo)]) "the rig file needn't change")))
    (testing "Open after Undo, a show from another rig without Balcony"
      (let [opened (open-zip undone (incoming-zip {}))
            s (:state opened)]
        (is (= [:group-balcony] (get-in s [:projectors :p :zone-groups])) "the member is this rig's")
        (is (= {:group-balcony balcony} (copy s)) "and so is A")
        (is (not (contains? (:zone-groups s) :group-balcony)) "the show doesn't get it")
        (is (= [{:group-id :group-balcony :name "Balcony" :lasers 1}] (:rig-groups-not-in-show (report s))))
        (is (false? (get-in s [:ui :dialogs :rig-report :open?])) "a row to read: it doesn't open the report")
        (is (nil? (write opened)) "the rig file is as it was")
        (is (fresh? s))
        (is (same-rig? undone s))))
    (testing "Open after Undo, a show that defines Balcony as B: its definition wins"
      (let [opened (open-zip undone (incoming-zip {:group-balcony balcony-b}))
            s (:state opened)]
        (is (= {:group-balcony balcony-b} (copy s)))
        (is (= [3 {:group-balcony balcony-b}] ((juxt :seq (comp :zone-groups :doc)) (write opened))) "one write")
        (is (fresh? s))
        (is (same-rig? undone s))))
    (testing "Open with no rig file, when only the outgoing show defines B: the incoming show doesn't get it, the rig
              remembers it"
      (let [here (-> (balcony-rig nil)
                     (assoc-in [:zone-groups :group-balcony] balcony-b)
                     (assoc-in [:rig-file :group-copy] {})
                     (history/mark-clean))
            s (:state (open-zip here (incoming-zip {})))]
        (is (not (contains? (:zone-groups s) :group-balcony)))
        (is (= {:group-balcony balcony-b} (copy s)))))))
