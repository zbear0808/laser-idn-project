(ns laser-show.input.keyboard
  "Keyboard trigger identity, display and ownership policy."
  (:require [clojure.string :as str])
  (:import [java.util Locale]))

(defn key-id
  "The persisted id for a KeyCode enum name (BACK_SPACE becomes :back_space)."
  [^String key-code-name]
  (keyword (.toLowerCase key-code-name Locale/ROOT)))

(defn label
  "The key's display name. Top-row and numpad digits stay distinguishable."
  [key-id]
  (when key-id
    (let [k (name key-id)]
      (cond
        (= :back_space key-id) "Backspace"
        (re-matches #"digit[0-9]" k) (subs k 5)
        (re-matches #"numpad[0-9]" k) (str "Numpad " (subs k 6))
        :else (str/join " " (map str/capitalize (str/split k #"_")))))))

(defn builtin-shortcut
  "The built-in shortcut a binding on this key overrides, if any."
  [key-id]
  (case key-id
    :space  {:label "Play/Stop"}
    :delete {:label "Clear Cell"}
    :r      {:label "Retrigger"}
    :f11    {:label "Fullscreen"}
    nil))

(def ^:private non-trigger-keys
  #{:undefined :shortcut :shift :control :alt :alt_graph :meta :windows :command})

(def ^:private pass {:consume? false :emit nil :cancel-learn? false})

(defn decide
  "Should this window consume the event, emit a router edge, or cancel learn?
   :held? means the window emitted the matching press, including a learn press.
   Only a new press checks the focus, current binding and enable switch."
  [{:keys [key-id phase held? enabled? text-target? claimed? modifiers
           reserved? learning bound?]}]
  (cond
    (= :released phase) (if held? {:consume? true :emit :released :cancel-learn? false} pass)
    (not= :pressed phase) pass
    held? {:consume? true :emit nil :cancel-learn? false} ; auto-repeat

    ;; Escape remains a UI command, even when it cancels a learn.
    (= :escape key-id) (if (and learning (empty? modifiers)) (assoc pass :cancel-learn? true) pass)

    (or (not enabled?) text-target? reserved? (nil? key-id) (non-trigger-keys key-id)
        (some #{:ctrl :alt :meta} modifiers) (= :continuous learning)) pass

    (or (= :press learning) (and bound? (not claimed?)))
    {:consume? true :emit :pressed :cancel-learn? false}

    :else pass))
