(ns laser-show.routing.zone-maps-test
  "Zone maps (zones plan Z4, 06 §4.1, §8.4; spec 01 §4, §9): the built-ins,
   zones wrapping over the lanes, overrides, the effective map's order, when
   a switch lands, a press scheduling it, pinning, and the editor's
   warnings."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.routing.targets :as targets]
            [laser-show.routing.zone-maps :as zone-maps]))

(def ^:private crowd-sweep
  {:id :map/custom-1 :name "Crowd sweep" :lanes [:auto/left :crowd] :overrides {:zone/c :auto/center}})

(def ^:private state
  {:zone-maps {:map/custom-1 crowd-sweep
               :map/custom-2 {:id :map/custom-2 :name "Beams first" :lanes [:graphics]}}
   :zone-map {:active :map/left-right :pending nil}
   :timing {:bpm 120.0}
   :playback {:playing? true}})

(def ^:private running {:running? true})

(deftest built-in-maps-test
  (is (= [{:id :map/left-right :name "Left / Right" :lanes [:auto/left :auto/right] :overrides {} :built-in? true}
          {:id :map/odd-even :name "Odd / Even" :lanes [:auto/odd :auto/even] :overrides {} :built-in? true}
          {:id :map/unison :name "Unison" :lanes [:all] :overrides {} :built-in? true}]
         zone-maps/built-ins))
  (is (= :map/left-right zone-maps/default-map-id))
  (testing "every map: the built-ins, then yours by name"
    (is (= [:map/left-right :map/odd-even :map/unison :map/custom-2 :map/custom-1]
           (mapv :id (zone-maps/all-maps state)))))
  (testing "by id"
    (is (= "Odd / Even" (:name (zone-maps/map-by-id state :map/odd-even))))
    (is (= crowd-sweep (zone-maps/map-by-id state :map/custom-1)))
    (is (nil? (zone-maps/map-by-id state :map/gone)))
    (is (zone-maps/built-in? :map/unison))
    (is (not (zone-maps/built-in? :map/custom-1)))
    (testing "a stored map can't take a built-in's place"
      (is (= "Left / Right" (:name (zone-maps/map-by-id (assoc-in state [:zone-maps :map/left-right] {:name "Mine"}) :map/left-right)))))))

(deftest zones-wrap-over-lanes-test
  (let [lr (zone-maps/map-by-id state :map/left-right)]
    (is (= [:auto/left :auto/right :auto/left :auto/right :auto/left]
           (mapv #(zone-maps/zone-lane lr %) [:zone/a :zone/b :zone/c :zone/d :zone/e]))
        "A, B, C, D, E: Left, Right, Left, Right, Left (Q-Z1 A)")
    (is (= :auto/right (zone-maps/zone-lane lr :zone/ab)) "zone 27 is odd: Right")
    (is (= [:all :all :all] (mapv #(zone-maps/zone-lane (zone-maps/map-by-id state :map/unison) %) [:zone/a :zone/b :zone/z]))
        "Unison: every zone to All")
    (is (= [:graphics :graphics] (mapv #(zone-maps/zone-lane {:lanes [:graphics]} %) [:zone/a :zone/q])) "one lane")
    (is (nil? (zone-maps/zone-lane lr :graphics)) "a ref that isn't a zone has no lane")))

(deftest overrides-beat-the-wrap-test
  (is (= [:auto/left :crowd :auto/center :crowd]
         (mapv #(zone-maps/zone-lane crowd-sweep %) [:zone/a :zone/b :zone/c :zone/d]))
      "C would wrap to Left; the override sends it to Center")
  (is (= :auto/center (zone-maps/zone-lane {:lanes [] :overrides {:zone/c :auto/center}} :zone/c))
      "an override needs no lane"))

(deftest empty-lanes-go-nowhere-test
  (is (nil? (zone-maps/zone-lane {:lanes []} :zone/a)) "no lanes")
  (is (nil? (zone-maps/zone-lane {} :zone/a)))
  (is (= [:auto/left nil :auto/left nil] (mapv #(zone-maps/zone-lane {:lanes [:auto/left nil]} %) [:zone/a :zone/b :zone/c :zone/d]))
      "an empty lane: its zones go nowhere")
  (is (nil? (zone-maps/zone-lane {:lanes [:zone/b]} :zone/a)) "a zone never maps to a zone"))

(deftest effective-map-order-test
  (let [pending (fn [at] (assoc-in state [:zone-map :pending] {:id 1 :map :map/odd-even :at-beat at :bar? false}))]
    (testing "the active map"
      (is (= :map/left-right (zone-maps/effective-map-id state 3.0 nil))))
    (testing "a pending switch, once due"
      (is (= :map/left-right (zone-maps/effective-map-id (pending 17.0) 16.99 nil)) "not yet")
      (is (= :map/odd-even (zone-maps/effective-map-id (pending 17.0) 17.0 nil)) "on the beat")
      (is (= :map/odd-even (zone-maps/effective-map-id (pending 17.0) (- 17.0 1e-12) nil)) "a hair before counts")
      (is (= :map/odd-even (zone-maps/effective-map-id (pending 17.0) 18.5 nil)) "and after, until it is committed"))
    (testing "the Map cycle's pick (Z5) beats the active map, unless the cycle is paused"
      (is (= :map/unison (zone-maps/effective-map-id state 3.0 :map/unison)))
      (is (= :map/left-right (zone-maps/effective-map-id (assoc-in state [:zone-map :cycle-paused?] true) 3.0 :map/unison)))
      (is (= :map/odd-even (zone-maps/effective-map-id (pending 2.0) 3.0 :map/unison)) "a due switch beats the cycle"))
    (testing "an id that is no map is Left / Right"
      (is (= :map/left-right (zone-maps/effective-map-id (assoc-in state [:zone-map :active] :map/gone) 0.0 nil)))
      (is (= :map/left-right (zone-maps/effective-map-id {} 0.0 nil)) "nothing set")
      (is (= :map/custom-1 (zone-maps/effective-map-id (assoc-in state [:zone-map :active] :map/custom-1) 0.0 nil))))))

(deftest switch-at-table-test
  (testing "06 §8.4 at 120 BPM: the window is 0.1 beat, 0.025 bar"
    (doseq [[beats bar? at] [[16.04 false nil]
                             [16.50 false 17.0]
                             [16.93 false 17.0]
                             [16.04 true nil]
                             [17.00 true 20.0]
                             [19.95 true 20.0]]]
      (is (= at (zone-maps/switch-at {:beats beats :bpm 120.0 :bar? bar? :clock? true}))
          (str beats (if bar? " bar" " beat")))))
  (testing "no clock, no tempo: at once"
    (is (nil? (zone-maps/switch-at {:beats 16.5 :bpm 120.0 :clock? false})))
    (is (nil? (zone-maps/switch-at {:beats 16.5 :bpm 0.0 :clock? true})))
    (is (nil? (zone-maps/switch-at {:beats 16.5 :bpm nil :clock? true}))))
  (testing "the window follows the tempo: 0.2 beat at 240 BPM"
    (is (nil? (zone-maps/switch-at {:beats 16.15 :bpm 240.0 :clock? true})))
    (is (= 17.0 (zone-maps/switch-at {:beats 16.25 :bpm 240.0 :clock? true}))))
  (testing "a press dated by its input counts from then, at most 250 ms back"
    (let [close? (fn [a b] (< (Math/abs (- (double a) (double b))) 1e-9))]
      (is (close? 16.0 (zone-maps/press-beats {:beats 16.3 :now-ms 1150 :input-time 1000} 120.0)) "150 ms: 0.3 beat")
      (is (close? 15.8 (zone-maps/press-beats {:beats 16.3 :now-ms 5000 :input-time 1000} 120.0)) "never more than 250 ms")
      (is (= 16.3 (zone-maps/press-beats {:beats 16.3 :now-ms 1000} 120.0)) "a pad press: now")
      (is (= 16.3 (zone-maps/press-beats {:beats 16.3 :now-ms 1000 :input-time 2000} 120.0)) "never ahead"))))

(deftest schedule-test
  (let [clock (fn [beats] (assoc running :beats beats :now-ms 0 :id :p1))]
    (testing "within 50 ms of a beat: at once"
      (is (= {:active :map/odd-even :pending nil}
             (:zone-map (zone-maps/schedule state :map/odd-even false (clock 16.04))))))
    (testing "otherwise on the next beat, pending"
      (is (= {:active :map/left-right :pending {:id :p1 :map :map/odd-even :at-beat 17.0 :bar? false}}
             (:zone-map (zone-maps/schedule state :map/odd-even false (clock 16.5))))))
    (testing "or the next bar"
      (is (= {:id :p1 :map :map/unison :at-beat 20.0 :bar? true}
             (get-in (zone-maps/schedule state :map/unison true (clock 17.0)) [:zone-map :pending]))))
    (let [waiting (zone-maps/schedule state :map/odd-even false (clock 16.5))]
      (testing "another press replaces the pending one"
        (is (= {:active :map/left-right :pending {:id :p1 :map :map/unison :at-beat 17.0 :bar? false}}
               (:zone-map (zone-maps/schedule waiting :map/unison false (clock 16.6))))))
      (testing "pressing the active map cancels it"
        (is (= {:active :map/left-right :pending nil}
               (:zone-map (zone-maps/schedule waiting :map/left-right false (clock 16.6))))))
      (testing "a pending switch already due is committed first"
        (is (= {:active :map/odd-even :pending {:id :p1 :map :map/unison :at-beat 18.0 :bar? false}}
               (:zone-map (zone-maps/schedule waiting :map/unison false (clock 17.5)))))
        (is (= {:active :map/odd-even :pending nil}
               (:zone-map (zone-maps/schedule waiting :map/odd-even false (clock 17.5))))
            "and then it is the active map: nothing to do")))
    (testing "no clock thread, or the transport stopped: at once"
      (is (= :map/odd-even (get-in (zone-maps/schedule state :map/odd-even false (assoc (clock 16.5) :running? false)) [:zone-map :active])))
      (is (= :map/odd-even (get-in (zone-maps/schedule (assoc-in state [:playback :playing?] false) :map/odd-even false (clock 16.5))
                                   [:zone-map :active]))))
    (testing "a press from a key counts from when it was played"
      (is (= :map/odd-even (get-in (zone-maps/schedule state :map/odd-even false (assoc (clock 16.2) :now-ms 1100 :input-time 1000))
                                   [:zone-map :active]))
          "16.2 beats now, 16.0 when it was pressed: at once"))
    (testing "every switch lands through commit"
      (is (= {:active :map/unison :pending nil} (:zone-map (zone-maps/commit (assoc-in state [:zone-map :pending] {:map :map/odd-even}) :map/unison)))))))

(deftest pin-test
  (let [lr (zone-maps/map-by-id state :map/left-right)]
    (is (= :auto/left (zone-maps/pin lr :zone/a)))
    (is (= [:auto/left :auto/right] (zone-maps/pin lr [:zone/a :zone/b])))
    (is (= :auto/left (zone-maps/pin lr [:zone/a :zone/c])) "two zones on one lane: once")
    (is (= [:graphics :auto/right] (zone-maps/pin lr [:graphics :zone/b])) "a group stays")
    (is (= :graphics (zone-maps/pin lr :graphics)))
    (is (= :graphics (zone-maps/pin {:lanes [nil]} [:zone/a :graphics])) "a zone on an empty lane goes")
    (is (nil? (zone-maps/pin {:lanes []} :zone/a)))
    (is (= [:crowd :auto/center] (zone-maps/pin crowd-sweep [:zone/b :zone/c])) "overrides too")
    (is (= targets/pin zone-maps/pin))))

(deftest map-warnings-test
  (let [names (targets/names [{:id :all :name "All" :kind :all} {:id :auto/left :name "Left" :kind :auto}
                              {:id :auto/center :name "Center" :kind :auto} {:id :graphics :name "Graphics" :kind :custom}
                              {:id :crowd :name "Crowd Scanning" :kind :custom}]
                             {})
        ctx {:names names :zones #{:zone/a :zone/b :zone/d} :reach {:all 3 :auto/left 1 :auto/center 1 :crowd 1 :graphics 0}
             :audience #{:crowd}}]
    (is (= [] (zone-maps/map-warnings {:lanes [:auto/left :all]} ctx)) "nothing to say")
    (is (= ["No lanes: every zone goes nowhere."] (zone-maps/map-warnings {:lanes []} ctx)))
    (is (= ["Lane 2 is empty: zones B, D go nowhere."] (zone-maps/map-warnings {:lanes [:auto/left nil]} ctx))
        "the zones the show uses on it")
    (is (= ["Lane 3 is empty: zones C, F go nowhere."] (zone-maps/map-warnings {:lanes [:auto/left :all nil]} ctx))
        "none used on it: the first two that would be")
    (is (= ["Lane 2 (Graphics) has no projector: zones B, D go nowhere."] (zone-maps/map-warnings {:lanes [:auto/left :graphics]} ctx)))
    (is (= ["Lane 2 (Crowd Scanning) reaches the audience."] (zone-maps/map-warnings crowd-sweep ctx)))
    (is (= ["Override C → Front: Front no longer exists."] (zone-maps/map-warnings {:lanes [:all] :overrides {:zone/c :front}} ctx)))
    (is (= ["Lane 1: Front no longer exists, so zones A, B, D go nowhere."] (zone-maps/map-warnings {:lanes [:front]} ctx)))
    (testing "a zone with an override isn't on its lane"
      (is (= ["Lane 2 is empty: zone B goes nowhere."] (zone-maps/map-warnings {:lanes [:auto/left nil] :overrides {:zone/d :all}} ctx))))
    (testing "the next custom id"
      (is (= :map/custom-3 (zone-maps/next-custom-id state)))
      (is (= :map/custom-1 (zone-maps/next-custom-id {}))))))
