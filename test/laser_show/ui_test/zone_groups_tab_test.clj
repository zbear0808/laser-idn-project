(ns laser-show.ui-test.zone-groups-tab-test
  "The Zone Groups tab, group first (plans/2026-11-19-zones/04 §7): All,
   the automatic groups, each laser and yours in its list; the selected
   group's lasers in rig order, ticked for your groups, explained for the
   automatic ones; the same membership as the rows under the cards on the
   Projectors tab."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]
            [laser-show.views.components.applied-value :as applied-value])
  (:import [javafx.scene Node]
           [javafx.scene.control CheckBox]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(defn- app-state [] (state/get-raw-state))

(defn- with-rig!
  "Lasers A, B and C at 10.0.0.1-3, B in Graphics, and a group of your own,
   Balcony."
  []
  (events/write-state! #(-> %
                            (assoc :projectors
                                   (into {} (for [[id n] [[:a 1] [:b 2] [:c 3]]]
                                              [id {:name (str "Laser " (str/upper-case (name id))) :host (str "10.0.0." n)
                                                   :port 7255 :service-id 1 :enabled? true :exclude-from-all? false
                                                   :zone-groups (if (= :b id) [:graphics] [])
                                                   :output-config {:color-bit-depth 8 :xy-bit-depth 16}}])))
                            (assoc-in [:zone-groups :group-balcony] {:id :group-balcony :name "Balcony" :description ""
                                                                     :color "#123456"}))))

(defn- top-to-bottom
  "The ids of the nodes matching selector, top to bottom, without prefix."
  [selector prefix]
  (->> (ui/query-all selector)
       (map (fn [^Node n] [(second (ui/scene-bounds n)) (str/replace-first (ui/on-fx-thread #(.getId n)) prefix "")]))
       (sort-by first)
       (mapv second)))

(defn- left-to-right [selector prefix]
  (->> (ui/query-all selector)
       (map (fn [^Node n] [(first (ui/scene-bounds n)) (str/replace-first (ui/on-fx-thread #(.getId n)) prefix "")]))
       (sort-by first)
       (mapv second)))

(defn- groups [id] (get-in (app-state) [:projectors id :zone-groups]))

(deftest list-has-all-automatic-lasers-and-yours-test
  (let [robot (ui/robot)]
    (with-rig!)
    (ui/click! robot "#zones-tab")
    (ui/assert-text! "#zones-subtitle"
                     "Groups are where cues draw. All and the automatic groups follow the order on the Projectors tab.")
    (ui/wait-for! #(= ["all" "auto-left" "auto-right" "auto-center" "auto-odd" "auto-even"
                       "auto-laser-a" "auto-laser-b" "auto-laser-c"
                       "group-balcony" "crowd" "graphics"]
                      (top-to-bottom ".zone-group-item" "zone-group-item-")))
    (testing "each says how many lasers it reaches"
      (is (= ["3" "2" "2" "1" "2" "1" "1" "0" "1"]
             (mapv #(ui/node-text (str "#zone-group-count-" %))
                   ["all" "auto-left" "auto-right" "auto-center" "auto-odd" "auto-even" "auto-laser-a" "group-balcony" "graphics"]))))
    (ui/assert-visible! "#zone-groups-add-button")))

(deftest automatic-group-is-read-only-and-explained-test
  (let [robot (ui/robot)]
    (with-rig!)
    (ui/click! robot "#zones-tab")
    (ui/click! robot "#zone-group-item-auto-left")
    (ui/wait-for! #(some? (ui/query "#zone-group-explanation")))
    (ui/assert-text! "#zone-group-explanation"
                     "The left half of the rig. The middle laser, Laser B, is split: its left half is in Left.")
    (ui/assert-text! "#zone-group-members-title" "LASERS IN LEFT")
    (is (= ["■" "◧" "·"] (mapv #(ui/node-text (str "#zone-mark-" %)) ["a" "b" "c"])) "the same marks as the rows")
    (is (nil? (ui/query "#zone-output-a .check-box")) "no boxes: it follows the order")
    (ui/assert-text! "#zone-group-cannot-delete" "Automatic: can't be renamed or deleted.")
    (is (nil? (ui/query "#zone-group-edit-button")))
    (is (nil? (ui/query "#zone-group-delete-button")))
    (ui/click! robot "#zone-output-a")
    (ui/sync-fx!)
    (is (= [] (groups :a)) "a click changes nothing")
    (testing "the link to the Projectors tab, to change the order or the middle laser"
      (ui/click! robot "#zone-group-projectors-link")
      (ui/wait-for! #(= :projectors (get-in (app-state) [:ui :active-tab]))))))

(deftest custom-group-boxes-toggle-in-rig-order-test
  (let [robot (ui/robot)]
    (with-rig!)
    (events/write-state! assoc-in [:rig :order] [:c :a :b])
    (ui/click! robot "#zones-tab")
    (ui/click! robot "#zone-group-item-graphics")
    (ui/wait-for! #(= ["c" "a" "b"] (left-to-right ".zone-output" "zone-output-")))
    (ui/assert-text! "#zone-group-explanation"
                     "Click a laser to add it to Graphics or take it out. The boxes under the cards on the Projectors tab are the same ones.")
    (is (ui/selected? "#zone-output-b .check-box"))
    (ui/click! robot "#zone-output-a .check-box")
    (ui/wait-for! #(= [:graphics] (groups :a)))
    (ui/click! robot "#zone-output-b .label-bold")
    (ui/wait-for! #(= [] (groups :b)))
    (testing "in All, a click keeps a laser out of All or puts it back"
      (ui/click! robot "#zone-group-item-all")
      (ui/wait-for! #(ui/selected? "#zone-output-a .check-box"))
      (ui/click! robot "#zone-output-a .check-box")
      (ui/wait-for! #(true? (get-in (app-state) [:projectors :a :exclude-from-all?])))
      (ui/wait-for! #(= "×" (ui/node-text "#zone-mark-a"))))))

(deftest both-tabs-show-the-same-membership-test
  (let [robot (ui/robot)]
    (with-rig!)
    (ui/click! robot "#projectors-tab")
    ;; The rows are under the cards: at 1200 × 800 the strip shows the cards
    (ui/scroll-into-view! "#rig-cell-graphics-a")
    (ui/click! robot "#rig-cell-graphics-a")
    (ui/wait-for! #(= [:graphics] (groups :a)))
    (ui/click! robot "#zones-tab")
    (ui/click! robot "#zone-group-item-graphics")
    (ui/wait-for! #(ui/selected? "#zone-output-a .check-box"))
    (ui/click! robot "#zone-output-a .check-box")
    (ui/wait-for! #(= [] (groups :a)))
    (ui/click! robot "#projectors-tab")
    (ui/wait-for! #(= "□" (ui/node-text "#rig-cell-graphics-a")))))


;; A refused edit (plans/2026-11-23-cljfx-reactivity/13): the boxes show
;; the rig

(defn- notice-id [] (get-in (app-state) [:ui :notice :id]))

(defn- ticked
  "Whether the check box selector finds is ticked, on the FX thread; nil
   when there is none (ui/selected? says false for a missing node too)."
  [selector]
  (when-let [box (ui/query selector)]
    (ui/on-fx-thread #(.isSelected ^CheckBox box))))

(defn- attempt!
  "(act!), then wait for the show lock's new refusal of an event of type."
  [type act!]
  (let [before (notice-id)]
    (act!)
    (ui/wait-for! #(and (some? (notice-id)) (not= before (notice-id))
                        (= type (get-in (app-state) [:ui :show-lock-notice :event]))))))

(defn- lock! [locked?]
  (events/dispatch! {:event/type :vibe/set-show-lock :locked? locked?})
  (ui/wait-for! #(= locked? (true? (get-in (app-state) [:ui :show-lock?])))))

(deftest locked-member-boxes-reflect-the-rig-test
  (let [robot (ui/robot)
        excluded? #(get-in (app-state) [:projectors % :exclude-from-all?])
        vp-groups #(get-in (app-state) [:virtual-projectors :vp1 :zone-groups])
        undo-depth #(count (get-in (app-state) [:history :undo-stack]))]
    (with-rig!)
    (events/write-state! assoc-in [:virtual-projectors :vp1] {:name "VP 1" :parent-projector-id :a :zone-groups []})
    (ui/click! robot "#zones-tab")
    (ui/click! robot "#zone-group-item-all")
    (ui/wait-for! #(true? (ticked "#zone-output-a .check-box")))
    (lock! true)
    (let [depth (undo-depth)]
      (testing "All: ticked is in All"
        (attempt! :projectors/set-exclude-from-all #(ui/click! robot "#zone-output-a .check-box"))
        (is (false? (excluded? :a)))
        (ui/wait-for! #(true? (ticked "#zone-output-a .check-box")))
        (is (= "■" (ui/node-text "#zone-mark-a"))))
      (ui/click! robot "#zone-group-item-group-balcony")
      (ui/wait-for! #(some? (ui/query "#zone-output-vp1 .check-box")))
      (testing "your group: a laser's box, a virtual projector's, and a click on a laser"
        (attempt! :projectors/toggle-zone-group #(ui/click! robot "#zone-output-a .check-box"))
        (is (= [] (groups :a)))
        (ui/wait-for! #(false? (ticked "#zone-output-a .check-box")))
        (attempt! :projectors/vp-toggle-zone-group #(ui/click! robot "#zone-output-vp1 .check-box"))
        (is (= [] (vp-groups)))
        (ui/wait-for! #(false? (ticked "#zone-output-vp1 .check-box")))
        (attempt! :projectors/toggle-zone-group #(ui/click! robot "#zone-output-b .label-bold"))
        (is (= [:graphics] (groups :b)))
        (is (false? (ticked "#zone-output-b .check-box"))))
      (is (= depth (undo-depth)) "no undo step"))
    (testing "unlocked, a click toggles once"
      (lock! false)
      (let [depth (undo-depth)]
        (ui/click! robot "#zone-output-a .check-box")
        (ui/wait-for! #(= [:group-balcony] (groups :a)))
        (ui/wait-for! #(true? (ticked "#zone-output-a .check-box")))
        ;; past the box's wait, which sends nothing
        (Thread/sleep (+ applied-value/settle-ms 100))
        (is (events/flush!))
        (is (= [:group-balcony] (groups :a)))
        (is (true? (ticked "#zone-output-a .check-box")))
        (is (= (inc depth) (undo-depth)))
        (ui/click! robot "#zone-output-vp1 .check-box")
        (ui/wait-for! #(= [:group-balcony] (vp-groups)))
        (ui/wait-for! #(true? (ticked "#zone-output-vp1 .check-box")))))))
