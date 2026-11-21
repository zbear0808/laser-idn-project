(ns laser-show.events.zone-groups-handlers-test
  "Tests for zone group handlers.
   
   SIMPLIFIED ARCHITECTURE (v2):
   Tests zone group CRUD operations, selection, and editor dialog.
   Zone groups now reference projectors instead of zones."
  (:require
   [clojure.test :refer [deftest is testing]] 
   [laser-show.state.history :as history]
   [laser-show.events.handlers.zone-groups :as zone-groups]
   [laser-show.state.domains :refer [build-initial-state]]
   [laser-show.events.core :as events]
   [laser-show.events.handlers.ui :as ui]
   [laser-show.routing.refs :as refs]
   [laser-show.common.util :as u]))


;; Test Data Fixtures


(def test-projector-id :proj-1)

(def base-state
  "Base state with default zone groups and a test projector."
  (-> (build-initial-state)
      (assoc-in [:projectors test-projector-id]
                {:name "Test Projector"
                 :host "192.168.1.10"
                 :port 7255
                 :enabled? true
                 :zone-groups [:all :left]
                 :tags #{}
                 :corner-pin {:tl-x -1.0 :tl-y 1.0
                              :tr-x 1.0 :tr-y 1.0
                              :bl-x -1.0 :bl-y -1.0
                              :br-x 1.0 :br-y -1.0}})))


;; Selection Tests


(deftest handle-zone-groups-select-test
  (testing "Selecting a zone group sets selected-group and clears projector selection"
    (let [state-with-projector (assoc-in base-state [:projector-ui :active-projector] test-projector-id)
          event {:event/type :zone-groups/select
                 :group-id :left
                 :state state-with-projector}
          result (zone-groups/handle event)]
      (is (= :left (get-in result [:state :zone-group-ui :selected-group])))
      (is (nil? (get-in result [:state :projector-ui :active-projector])))
      (is (nil? (get-in result [:state :projector-ui :active-virtual-projector]))))))


(deftest show-opens-the-group-in-its-tab-test
  (let [selected (assoc-in base-state [:projector-ui :active-projector] test-projector-id)
        state (:state (zone-groups/handle {:event/type :zone-groups/show :group-id :auto/left :state selected}))]
    (is (= :auto/left (get-in state [:zone-group-ui :selected-group])) "All and the automatic groups too")
    (is (= :zones (get-in state [:ui :active-tab])))
    (is (= test-projector-id (get-in state [:projector-ui :active-projector])) "the card stays selected"))
  (is (some? (:state (zone-groups/handle {:event/type :zone-groups/show :state base-state}))) "called bare"))


;; Add/Create Tests


(deftest handle-zone-groups-add-test
  (testing "Add opens zone group editor dialog in create mode"
    (let [event {:event/type :zone-groups/add
                 :state base-state}
          result (zone-groups/handle event)]
      (is (true? (get-in result [:state :ui :dialogs :zone-group-editor :open?])))
      ;; FLATTENED: Dialog fields live alongside :open?, not under :data
      (is (false? (get-in result [:state :ui :dialogs :zone-group-editor :editing?])))
      (is (nil? (get-in result [:state :ui :dialogs :zone-group-editor :group-id])))
      (is (= "" (get-in result [:state :ui :dialogs :zone-group-editor :name]))))))


(deftest handle-zone-groups-create-new-test
  (testing "Create new adds zone group and closes dialog"
    (let [state-with-dialog (assoc-in base-state [:ui :dialogs :zone-group-editor :open?] true)
          event {:event/type :zone-groups/create-new
                 :name "My New Group"
                 :description "Test description"
                 :color "#FF5500"
                 :state state-with-dialog}
          result (zone-groups/handle event)
          groups (get-in result [:state :zone-groups])
          new-group (u/seek #(= "My New Group" (:name (second %))) groups)]
      ;; Should have created a new group
      (is (some? new-group))
      (is (= "My New Group" (:name (second new-group))))
      (is (= "Test description" (:description (second new-group))))
      (is (= "#FF5500" (:color (second new-group))))
      ;; Dialog should be closed
      (is (false? (get-in result [:state :ui :dialogs :zone-group-editor :open?])))
      ;; New group should be selected
      (is (= (first new-group) (get-in result [:state :zone-group-ui :selected-group])))
      ;; Project should be dirty
      (is (history/derive-dirty (:state event) (:state result)))))
  
  (testing "Create with defaults works"
    (let [event {:event/type :zone-groups/create-new
                 :name "Minimal Group"
                 :state base-state}
          result (zone-groups/handle event)
          groups (get-in result [:state :zone-groups])
          new-group (u/seek #(= "Minimal Group" (:name (second %))) groups)]
      (is (some? new-group))
      (is (= "" (:description (second new-group))))
      (is (= "#808080" (:color (second new-group)))))))


;; Remove Tests


(deftest handle-zone-groups-remove-test
  (testing "Remove deletes group and clears from projectors and virtual projectors"
    ;; First create a custom group and assign it to a projector
    (let [custom-group-id :custom-group
          vp-id #uuid "22222222-2222-2222-2222-222222222222"
          state-with-custom (-> base-state
                                (assoc-in [:zone-groups custom-group-id]
                                          {:id custom-group-id
                                           :name "Custom"
                                           :description ""
                                           :color "#FF0000"})
                                (assoc-in [:projectors test-projector-id :zone-groups]
                                          [:all :left custom-group-id])
                                (assoc-in [:virtual-projectors vp-id]
                                          {:name "Test VP"
                                           :parent-projector-id test-projector-id
                                           :zone-groups [:all custom-group-id]
                                           :enabled? true})
                                (assoc-in [:zone-group-ui :selected-group] custom-group-id))
          event {:event/type :zone-groups/remove
                 :group-id custom-group-id
                 :state state-with-custom}
          result (zone-groups/handle event)]
      ;; Group should be removed
      (is (nil? (get-in result [:state :zone-groups custom-group-id])))
      ;; Projector should no longer reference the group
      (is (not (some #{custom-group-id}
                     (get-in result [:state :projectors test-projector-id :zone-groups]))))
      ;; Virtual projector should no longer reference the group
      (is (not (some #{custom-group-id}
                     (get-in result [:state :virtual-projectors vp-id :zone-groups]))))
      ;; Selection should be cleared
      (is (nil? (get-in result [:state :zone-group-ui :selected-group])))
      ;; Project should be dirty
      (is (history/derive-dirty (:state event) (:state result))))))


;; Edit Dialog Tests


(deftest handle-zone-groups-edit-test
  (testing "Edit opens dialog with existing group data"
    (let [event {:event/type :zone-groups/edit
                 :group-id :graphics
                 :state base-state}
          result (zone-groups/handle event)
          ;; FLATTENED: Dialog fields live alongside :open?, not under :data
          dialog (get-in result [:state :ui :dialogs :zone-group-editor])]
      (is (true? (:open? dialog)))
      (is (true? (:editing? dialog)))
      (is (= :graphics (:group-id dialog)))
      (is (= "Graphics" (:name dialog))))))


(deftest handle-zone-groups-save-edit-test
  (testing "Save edit updates group and closes dialog"
    ;; FLATTENED: Dialog fields live alongside :open?, not under :data
    (let [state-with-dialog (-> base-state
                                (assoc-in [:ui :dialogs :zone-group-editor]
                                          {:open? true
                                           :editing? true
                                           :group-id :graphics
                                           :name "Graphics Updated"
                                           :description "New desc"
                                           :color "#00FF00"}))
          event {:event/type :zone-groups/save-edit
                 :group-id :graphics
                 :name "Graphics Updated"
                 :description "New desc"
                 :color "#00FF00"
                 :state state-with-dialog}
          result (zone-groups/handle event)
          group (get-in result [:state :zone-groups :graphics])]
      (is (= "Graphics Updated" (:name group)))
      (is (= "New desc" (:description group)))
      (is (= "#00FF00" (:color group)))
      (is (false? (get-in result [:state :ui :dialogs :zone-group-editor :open?]))))))


(deftest built-in-groups-are-never-stored-test
  (doseq [ref [:all :auto/left :auto.laser/proj-1]]
    (testing (str ref)
      (is (identical? base-state (:state (zone-groups/handle {:event/type :zone-groups/save-edit :group-id ref
                                                              :name "Mine" :description "" :color "#FF0000"
                                                              :state base-state})))
          "saving an edit never stores All or an automatic group")
      (is (identical? base-state (:state (zone-groups/handle {:event/type :zone-groups/edit :group-id ref :state base-state})))
          "and the editor doesn't open for one"))))


(deftest handle-zone-groups-set-editor-color-test
  (testing "Set editor color updates dialog data"
    ;; FLATTENED: Dialog fields live alongside :open?, not under :data
    (let [state-with-dialog (assoc-in base-state
                                      [:ui :dialogs :zone-group-editor :color]
                                      "#808080")
          event {:event/type :zone-groups/set-editor-color
                 :color "#FF5500"
                 :state state-with-dialog}
          result (zone-groups/handle event)]
      (is (= "#FF5500" (get-in result [:state :ui :dialogs :zone-group-editor :color]))))))


;; Unknown Event Test


(deftest handle-unknown-event-test
  (testing "Unknown event returns empty map"
    (let [event {:event/type :zone-groups/unknown-event
                 :state base-state}
          result (zone-groups/handle event)]
      (is (= {} result)))))


;; Deleting a group: ask first, re-target its cues


(def ^:private balcony-state
  "Balcony is named by cue [0 0]'s item and cue [1 2]'s own target, and by
   the preview's second cell; the projector is in it."
  (-> base-state
      (assoc-in [:zone-groups :group-balcony] {:id :group-balcony :name "Balcony" :description "" :color "#FF0000"})
      (assoc-in [:projectors test-projector-id :zone-groups] [:all :group-balcony])
      (assoc-in [:chains :cue-chains [0 0]] {:name "Intro"
                                             :items [{:id "p" :type :preset :preset-id :circle
                                                      :target :group-balcony}]})
      (assoc-in [:chains :cue-chains [1 2]] {:name "Drop"
                                             :target :group-balcony
                                             :items []})
      (assoc-in [:config :preview :grid-cells] [{:target nil} {:target :group-balcony}])
      (assoc-in [:zone-group-ui :selected-group] :group-balcony)))

(deftest request-remove-opens-the-confirm-test
  (let [dialog (get-in (zone-groups/handle {:event/type :zone-groups/request-remove
                                            :group-id :group-balcony
                                            :state balcony-state})
                       [:state :ui :dialogs :confirm])]
    (is (:open? dialog))
    (is (= "Delete \"Balcony\"?" (:title dialog)))
    (is (= "2 cues and 1 projector use it." (:message dialog)))
    (is (= "A1 Intro · C2 Drop" (:details dialog)))
    (is (= :retarget-to (get-in dialog [:choice :key])))
    (is (= :all (:choice-value dialog)) "All by default")
    (is (= [:all :auto/left :auto/right :auto/center :auto/odd :auto/even :auto.laser/proj-1 :crowd :graphics :inherit]
           (mapv :value (get-in dialog [:choice :options])))
        "every target the pickers list (All first, the automatic groups, each laser, your other groups by name), and Nothing; never the group itself")
    (is (= {:event/type :zone-groups/remove :group-id :group-balcony} (:confirm-event dialog))))
  (testing "zones plan Z4: a column default and the show's name it too, and can be sent elsewhere"
    (let [dialog (get-in (zone-groups/handle {:event/type :zone-groups/request-remove
                                              :group-id :group-balcony
                                              :state (-> balcony-state
                                                         (assoc-in [:grid :column-targets] {2 [:group-balcony :zone/a] 4 :zone/b})
                                                         (assoc-in [:config :default-target] :group-balcony))})
                         [:state :ui :dialogs :confirm])]
      (is (= "2 cues, column 3, the show default and 1 projector use it." (:message dialog)))
      (is (= :retarget-to (get-in dialog [:choice :key])))))
  (testing "a column alone"
    (let [dialog (get-in (zone-groups/handle {:event/type :zone-groups/request-remove :group-id :graphics
                                              :state (assoc-in balcony-state [:grid :column-targets] {0 :graphics 1 :graphics})})
                         [:state :ui :dialogs :confirm])]
      (is (= "Columns 1 and 2 use it." (:message dialog)))
      (is (some? (:choice dialog)) "where they go: asked")))
  (testing "zones plan Z4: a zone map's lane or override names it too, and its lane can be sent elsewhere (06 SQ-13)"
    (let [ask (fn [maps] (get-in (zone-groups/handle {:event/type :zone-groups/request-remove :group-id :graphics
                                                      :state (assoc balcony-state :zone-maps maps)})
                                 [:state :ui :dialogs :confirm]))
          sweep {:name "Crowd sweep" :lanes [:auto/left :graphics] :overrides {}}]
      (is (= "The map Crowd sweep uses it." (:message (ask {:map/custom-1 sweep}))))
      (is (some? (:choice (ask {:map/custom-1 sweep}))) "where its lane goes: asked")
      (is (= "The maps Crowd sweep and Wide use it."
             (:message (ask {:map/custom-1 sweep
                             :map/custom-2 {:name "Wide" :lanes [:auto/left] :overrides {:zone/c :graphics}}}))))
      (is (= "2 cues, the map Crowd sweep and 1 projector use it."
             (get-in (zone-groups/handle {:event/type :zone-groups/request-remove :group-id :group-balcony
                                          :state (assoc balcony-state :zone-maps {:map/custom-1 (assoc sweep :lanes [:group-balcony])})})
                     [:state :ui :dialogs :confirm :message])))))
  (testing "a group nothing uses asks with no choice"
    (let [dialog (get-in (zone-groups/handle {:event/type :zone-groups/request-remove
                                              :group-id :graphics
                                              :state balcony-state})
                         [:state :ui :dialogs :confirm])]
      (is (= "Nothing uses it." (:message dialog)))
      (is (nil? (:choice dialog)))))
  (testing "more than six cues: the first six, then how many more"
    (let [many (reduce (fn [s col] (assoc-in s [:chains :cue-chains [col 0]]
                                             {:name (str "Cue " col) :target :graphics :items []}))
                       balcony-state (range 8))
          details (get-in (zone-groups/handle {:event/type :zone-groups/request-remove :group-id :graphics :state many})
                          [:state :ui :dialogs :confirm :details])]
      (is (= "A1 Cue 0 · A2 Cue 1 · A3 Cue 2 · A4 Cue 3 · A5 Cue 4 · A6 Cue 5 · and 2 more" details)))))

(deftest remove-retargets-cues-test
  (let [result (zone-groups/handle {:event/type :zone-groups/remove :group-id :group-balcony
                                    :retarget-to :graphics :state balcony-state})
        state (:state result)]
    (is (nil? (get-in state [:zone-groups :group-balcony])))
    (is (= :graphics (get-in state [:chains :cue-chains [0 0] :items 0 :target])))
    (is (= :graphics (get-in state [:chains :cue-chains [1 2] :target])))
    (is (= {:target :graphics} (get-in state [:config :preview :grid-cells 1])))
    (is (= #{} (refs/cues-using (get-in state [:chains :cue-chains]) :group-balcony)))
    (is (nil? (get-in state [:zone-group-ui :selected-group])))
    (is (= "Deleted Balcony · 2 cues now go to Graphics" (get-in state [:ui :notice :text])))
    (is (= "Deleted Balcony · 2 cues now go to All"
           (get-in (zone-groups/handle {:event/type :zone-groups/remove :group-id :group-balcony :state balcony-state})
                   [:state :ui :notice :text]))
        "All by its name, though no group stores it")))

(deftest remove-can-retarget-to-an-automatic-group-test
  (doseq [[target text] [[:auto/left "Left"] [:auto/even "Even"] [:auto.laser/proj-1 "Test Projector"]]]
    (let [state (:state (zone-groups/handle {:event/type :zone-groups/remove :group-id :group-balcony
                                             :retarget-to target :state balcony-state}))]
      (is (= target (get-in state [:chains :cue-chains [0 0] :items 0 :target])) (str target))
      (is (= target (get-in state [:chains :cue-chains [1 2] :target])))
      (is (= (str "Deleted Balcony · 2 cues now go to " text) (get-in state [:ui :notice :text]))
          "named as the pickers name it")))
  (testing "a laser that isn't there is no target: All"
    (is (= :all (get-in (zone-groups/handle {:event/type :zone-groups/remove :group-id :group-balcony
                                             :retarget-to :auto.laser/gone :state balcony-state})
                        [:state :chains :cue-chains [0 0] :items 0 :target])))))

(deftest remove-to-nothing-inherits-test
  (let [state (:state (zone-groups/handle {:event/type :zone-groups/remove :group-id :group-balcony
                                           :retarget-to :inherit :state balcony-state}))]
    (is (not (contains? (get-in state [:chains :cue-chains [0 0] :items 0]) :target)))
    (is (not (contains? (get-in state [:chains :cue-chains [1 2]]) :target)))
    (is (= "Deleted Balcony · 2 cues now inherit" (get-in state [:ui :notice :text]))))
  (testing "nil means Nothing too"
    (let [state (:state (zone-groups/handle {:event/type :zone-groups/remove :group-id :group-balcony
                                             :retarget-to nil :state balcony-state}))]
      (is (not (contains? (get-in state [:chains :cue-chains [0 0] :items 0]) :target))))))

(deftest retarget-to-a-missing-group-is-all-test
  (doseq [target [:no-such-group :group-balcony]]
    (let [state (:state (zone-groups/handle {:event/type :zone-groups/remove :group-id :group-balcony
                                             :retarget-to target :state balcony-state}))]
      (is (= :all (get-in state [:chains :cue-chains [0 0] :items 0 :target])) (str target))))
  (testing "no :retarget-to at all is All"
    (let [state (:state (zone-groups/handle {:event/type :zone-groups/remove :group-id :group-balcony
                                             :state balcony-state}))]
      (is (= :all (get-in state [:chains :cue-chains [0 0] :items 0 :target]))))))

(deftest remove-strips-membership-test
  (let [state (:state (zone-groups/handle {:event/type :zone-groups/remove :group-id :group-balcony
                                           :retarget-to :graphics :state balcony-state}))]
    (is (= [:all] (get-in state [:projectors test-projector-id :zone-groups]))
        "the group leaves the projector; the re-target never adds a membership")))

(deftest all-cannot-be-removed-test
  (doseq [event-type [:zone-groups/request-remove :zone-groups/remove]]
    (is (identical? balcony-state
                    (:state (zone-groups/handle {:event/type event-type :group-id :all :state balcony-state})))
        (str event-type))))

(deftest remove-is-one-undo-step-test
  (let [asked (:state (zone-groups/handle {:event/type :zone-groups/request-remove :group-id :group-balcony
                                           :state balcony-state}))
        confirmed (ui/handle {:event/type :ui/confirm :state asked})
        undo-depth #(count (get-in % [:history :undo-stack]))
        after (:state (events/handle-event-pipeline (assoc (:dispatch confirmed) :state (:state confirmed))))]
    (is (nil? (get-in after [:zone-groups :group-balcony])))
    (is (= :all (get-in after [:chains :cue-chains [0 0] :items 0 :target])))
    (is (= (inc (undo-depth balcony-state)) (undo-depth after)) "one undo step")))
\n

;; Group ids from names


(deftest create-new-ids-are-unique-test
  (let [create (fn [s n] (:state (zone-groups/handle {:event/type :zone-groups/create-new :name n :state s})))
        once (create base-state "Balcony")
        twice (create once "Balcony")]
    (is (= {:id :group-balcony :name "Balcony"} (select-keys (get-in once [:zone-groups :group-balcony]) [:id :name])))
    (is (= "Balcony" (get-in twice [:zone-groups :group-balcony-2 :name]))
        "the same name twice, in the same millisecond, gives two groups")
    (is (= 2 (count (filter #(= "Balcony" (:name %)) (vals (:zone-groups twice))))))
    (is (= :group-balcony-2 (get-in twice [:zone-group-ui :selected-group])))))

(deftest create-new-never-takes-a-built-in-id-test
  (is (= :group-all (zone-groups/unique-group-id base-state "All")))
  (is (= :group-left (zone-groups/unique-group-id base-state "Left")))
  (is (= :group-stage-left-2 (zone-groups/unique-group-id
                              (assoc-in base-state [:zone-groups :group-stage-left] {:name "Stage left"})
                              "Stage  Left!")))
  (is (= :group (zone-groups/unique-group-id base-state "")))
  (is (= :group (zone-groups/unique-group-id base-state "  ")))
  (is (= :group-2 (zone-groups/unique-group-id (assoc-in base-state [:zone-groups :group] {}) "!!"))))
