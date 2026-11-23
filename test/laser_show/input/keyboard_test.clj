(ns laser-show.input.keyboard-test
  (:require [clojure.test :refer [are deftest is testing]]
            [laser-show.input.keyboard :as keyboard]))

(deftest key-id-test
  (are [code id] (= id (keyboard/key-id code))
    "SPACE" :space, "A" :a, "DIGIT1" :digit1, "NUMPAD3" :numpad3,
    "F5" :f5, "LEFT" :left, "BACK_SPACE" :back_space))

(deftest label-test
  (are [id text] (= text (keyboard/label id))
    :space "Space", :a "A", :digit1 "1", :numpad3 "Numpad 3",
    :f5 "F5", :left "Left", :back_space "Backspace", :page_up "Page Up"))

(deftest builtin-shortcut-test
  (are [id shortcut] (= shortcut (keyboard/builtin-shortcut id))
    :space  {:label "Play/Stop"}
    :delete {:label "Clear Cell"}
    :r      {:label "Retrigger"}
    :f11    {:label "Fullscreen"}
    :escape nil
    :a      nil))

(deftest decide-table-test
  (let [decide #(keyboard/decide (merge {:key-id :space :phase :pressed :enabled? true
                                         :modifiers #{} :bound? true} %))
        pass {:consume? false :emit nil :cancel-learn? false}
        cancel {:consume? false :emit nil :cancel-learn? true}
        press {:consume? true :emit :pressed :cancel-learn? false}
        release {:consume? true :emit :released :cancel-learn? false}
        repeat-down {:consume? true :emit nil :cancel-learn? false}]
    (testing "a bound key overrides a built-in, unless the focused editor needs it"
      (are [ctx expected] (= expected (decide ctx))
        {} press
        {:modifiers #{:shift}} press
        {:bound? false} pass
        {:claimed? true} pass
        {:text-target? true} pass
        {:reserved? true} pass
        {:enabled? false} pass))
    (testing "a discrete learn can take an unbound or claimed key; a fader learn cannot"
      (are [ctx expected] (= expected (decide ctx))
        {:bound? false :claimed? true :learning :press} press
        {:learning :press :text-target? true} pass
        {:learning :press :enabled? false} pass
        {:learning :press :reserved? true} pass
        {:learning :continuous} pass)
      (doseq [modifiers [#{:ctrl} #{:meta} #{:alt :shift}]]
        (is (= pass (decide {:modifiers modifiers :learning :press})) (str modifiers)))
      (doseq [id [:shift :control :alt :alt_graph :meta :windows :command :shortcut :undefined nil]]
        (is (= pass (decide {:key-id id :learning :press})) (str id))))
    (testing "Escape cancels either kind of learn, only on a plain press, and always passes through"
      (are [ctx expected] (= expected (decide (assoc ctx :key-id :escape)))
        {} pass
        {:learning :press :reserved? true} cancel
        {:learning :continuous} cancel
        {:learning :press :modifiers #{:shift}} pass
        {:learning :press :modifiers #{:meta}} pass
        {:learning :press :phase :released} pass))
    (testing "ownership lasts until key-up even if eligibility changes mid-press"
      (are [ctx expected] (= expected (decide ctx))
        {:phase :released :held? true} release
        {:phase :released :held? true :text-target? true :enabled? false :claimed? true
         :modifiers #{:alt} :bound? false :learning :continuous} release
        {:phase :released} pass
        {:phase :released :learning :press} pass
        {:held? true} repeat-down
        {:held? true :bound? false :claimed? true :text-target? true :learning :press} repeat-down))))
