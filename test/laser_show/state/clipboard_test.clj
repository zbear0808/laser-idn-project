(ns laser-show.state.clipboard-test
  "The clipboard's pure helpers (keyboard plan Phase 4, Step 4a): what a clip
   is, its text on the OS clipboard, and which target takes which clip. No
   app state, no FX toolkit, no OS clipboard."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [laser-show.state.clipboard :as clipboard]))

(def ^:private item
  {:id #uuid "00000000-0000-0000-0000-000000000001" :type :preset :preset-id :circle
   :params {:radius 0.5} :effects []})

(def ^:private effect
  {:id #uuid "00000000-0000-0000-0000-00000000000a" :effect-id :axis-transform :enabled? true
   :params {:mirror-x? true}})

(deftest list-clip-shapes-test
  (is (= {:type :cue-chain-items :items [item] :copied-at 42}
         (clipboard/list-clip :cue-chain-items [item] 42)))
  (is (= {:type :item-effects :effects [effect] :copied-at 42}
         (clipboard/list-clip :item-effects [effect] 42)))
  (is (nil? (clipboard/list-clip :timeline-tracks [{:id :t1 :type :track}] 42))
      "4b: no list copies tracks")
  (is (nil? (clipboard/list-clip nil [item] 42)) "4b: a list without a type copies nothing")
  (is (nil? (clipboard/list-clip :cue-chain-items [] 42)) "nothing to copy: no clip"))

(deftest cell-clip-shapes-test
  (is (= {:type :cue-chain :data {:items [item]} :copied-at 7}
         (clipboard/cell-clip :cue-chain {:items [item]} 7)))
  (is (= {:type :effects-cell :data {:items [effect] :active? true} :copied-at 7}
         (clipboard/cell-clip :effects-cell {:items [effect] :active? true} 7))))

(deftest an-empty-cell-makes-no-clip-test
  ;; Step 4b: an empty cell copies nothing, so a paste never empties a cell
  (doseq [cell [nil {} {:items []} {:items [] :active? true} {:tracks [] :items []}]]
    (is (nil? (clipboard/cell-clip :cue-chain cell 7)) (pr-str cell)))
  (is (some? (clipboard/cell-clip :cue-chain {:editor-type :timeline :tracks [{:id :t1}]} 7))
      "a timeline cue with tracks and no clips yet is no empty cell")
  (doseq [target [:cue-chain :effects-cell]]
    (is (not (clipboard/accepts? {:type target :data nil :copied-at 1} target)) "another build's empty-cell clip")
    (is (not (clipboard/accepts? {:type target :data {:items []} :copied-at 1} target)))))

(deftest effect-copies-keep-groups-as-they-are-test
  (let [group {:id #uuid "00000000-0000-0000-0000-00000000000b" :type :group :name "G" :enabled? true
               :items [effect]}]
    (is (= {:type :item-effects :effects [group effect] :copied-at 1}
           (clipboard/list-clip :item-effects [group effect] 1))
        "no :params nil on a group, and its effects as copy keeps them")))

(deftest grid-clip-shapes-test
  (is (= {:type :step-grid :grid {:zones [:zone/a] :steps [[:zone/a] []]} :copied-at 9}
         (clipboard/grid-clip {:zones [:zone/a] :steps [[:zone/a] []]} 9))))

(deftest system-text-round-trip-test
  (doseq [clip [(clipboard/list-clip :cue-chain-items [item] 1)
                (clipboard/list-clip :item-effects [effect] 2)
                (clipboard/cell-clip :cue-chain {:items [item] :destination-zone {:zone-group-id :all}} 3)
                (clipboard/grid-clip {:zones [:zone/a :zone/b] :steps [[:zone/a] [:zone/b]]} 4)]]
    (let [text (clipboard/->system-text clip)]
      (is (not (str/includes? text "\n")) "one line of EDN")
      (is (= clip (clipboard/<-system-text text)) (str "the clip back, uuids included: " (:type clip))))))

(deftest system-text-ignores-foreign-text-test
  (doseq [text ["hello" "" "   " "{:a 1}" "{:type :no-such-type :items []}" "[1 2]" "{:type"
                "{:type :timeline-tracks :items []}" "{:type :effect-chain :effects []}" "{:type :effect :effect-id :scale}"
                "{:type :cue-chain-items #no/such-tag 1}" nil]]
    (is (nil? (clipboard/<-system-text text)) (pr-str (some-> text (subs 0 (min 40 (count text)))))))
  (testing "a text of 5,000,001 characters isn't read at all"
    (is (nil? (clipboard/<-system-text (str "{" (apply str (repeat 5000000 " "))))))))

(deftest list-type-test
  (testing "04 §7: each list's type comes from its component id"
    (is (= :cue-chain-items (clipboard/list-type [:cue-chain 0 0])))
    (is (= :item-effects (clipboard/list-type [:item-effects 2 1])))
    (is (= :item-effects (clipboard/list-type [:effect-chain 0 3])))
    (is (= :item-effects (clipboard/list-type [:projector-effects :p1]))))
  (testing "any other list has none"
    (doseq [id [[:timeline-tracks 0 0] :timeline-tracks [:something 0 0] nil []]]
      (is (nil? (clipboard/list-type id)) (pr-str id)))))

(deftest accepts-strict-types-and-conversions-test
  (let [cue-items (clipboard/list-clip :cue-chain-items [item] 1)
        item-effects (clipboard/list-clip :item-effects [effect] 1)
        effect-chain {:type :effect-chain :effects [effect] :active? true :copied-at 1}
        one-effect (assoc effect :type :effect :copied-at 1)
        tracks {:type :timeline-tracks :items [{:id :t1 :type :track}] :copied-at 1}
        cue-cell (clipboard/cell-clip :cue-chain {:items [item]} 1)
        fx-cell (clipboard/cell-clip :effects-cell {:items [effect] :active? true} 1)
        grid (clipboard/grid-clip {:zones [:zone/a] :steps [[:zone/a]]} 1)
        clips [cue-items item-effects effect-chain one-effect tracks cue-cell fx-cell grid]
        takes (fn [target] (set (map :type (filter #(clipboard/accepts? % target) clips))))]
    (testing "04 §7, second table: a list its own kind, a cell its own"
      (is (contains? (takes :cue-chain-items) :cue-chain-items))
      (is (not (contains? (takes :cue-chain-items) :item-effects)) "effects never land among a cue's presets")
      (is (contains? (takes :item-effects) :item-effects))
      (is (not (contains? (takes :item-effects) :cue-chain-items)) "a cue's presets never land among effects")
      (is (= #{:cue-chain} (takes :cue-chain)))
      (is (= #{:effects-cell} (takes :effects-cell)))
      (is (= #{:step-grid} (takes :step-grid))))
    (testing "types nothing writes paste nowhere"
      (doseq [target [:cue-chain-items :item-effects :cue-chain :effects-cell :step-grid]]
        (is (not-any? #(clipboard/accepts? % target) [effect-chain one-effect tracks]) (str target))))
    (testing "what each target gets"
      (is (= [item] (clipboard/payload cue-items :cue-chain-items)))
      (is (= [effect] (clipboard/payload item-effects :item-effects)))
      (is (= {:items [item]} (clipboard/payload cue-cell :cue-chain)))
      (is (= {:zones [:zone/a] :steps [[:zone/a]]} (clipboard/payload grid :step-grid)))
      (is (nil? (clipboard/payload cue-items :item-effects)))
      (is (nil? (clipboard/payload item-effects :cue-chain-items))))
    (testing "D9 A's two conversions: a cue cell's items into a cue list, an effects cell's effects into an effect list"
      (is (= #{:cue-chain-items :cue-chain} (takes :cue-chain-items)))
      (is (= #{:item-effects :effects-cell} (takes :item-effects)))
      (is (= [item] (clipboard/payload cue-cell :cue-chain-items)))
      (is (= [effect] (clipboard/payload fx-cell :item-effects)))
      (is (nil? (clipboard/payload cue-cell :item-effects)))
      (is (nil? (clipboard/payload fx-cell :cue-chain-items))))
    (testing "a timeline cue's cell pastes onto a cell, never into a list"
      (let [timeline (clipboard/cell-clip :cue-chain {:editor-type :timeline :tracks [{:id :t1 :type :track}]
                                                     :items [(assoc item :track-id :t1)]} 1)
            implicit (clipboard/cell-clip :cue-chain {:items [(assoc item :timeline/start 0.0)]} 1)]
        (doseq [c [timeline implicit]]
          (is (not (clipboard/accepts? c :cue-chain-items)))
          (is (clipboard/accepts? c :cue-chain)))))
    (testing "nothing takes nothing, and no target is no list"
      (is (not-any? #(clipboard/accepts? nil %) [:cue-chain-items :item-effects :cue-chain :effects-cell :step-grid]))
      (is (not-any? #(clipboard/accepts? % nil) clips))
      (is (not (clipboard/accepts? (assoc cue-items :items []) :cue-chain-items)) "an empty list pastes nothing"))))

(deftest adopt-fills-only-an-empty-slot-test
  (let [mine (clipboard/list-clip :cue-chain-items [item] 1)
        theirs (clipboard/cell-clip :cue-chain {:items [item]} 2)
        empty-state {:ui {:clipboard nil}}]
    (is (= theirs (get-in (clipboard/adopt empty-state theirs) [:ui :clipboard])) "an empty slot takes the read")
    (is (= mine (get-in (clipboard/adopt (assoc-in empty-state [:ui :clipboard] mine) theirs) [:ui :clipboard]))
        "a copy made while the read was out wins")
    (is (identical? empty-state (clipboard/adopt empty-state nil)) "nothing read: nothing changes")))
