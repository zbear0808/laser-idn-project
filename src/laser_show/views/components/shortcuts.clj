(ns laser-show.views.components.shortcuts
  "Shortcut text as the platform writes it, for labels and tooltips that name
   a shortcut: ⌘C on macOS, Ctrl+C elsewhere, as the menus show their
   [:shortcut :c] accelerators."
  (:require [clojure.string :as str]))

(def ^:private this-platform
  (if (str/includes? (str/lower-case (System/getProperty "os.name" "")) "mac")
    :mac
    :other))

(defn shortcut-text
  "The shortcut key and key as platform (:mac or :other; default this JVM's)
   writes them: (shortcut-text :c) is \"⌘C\" on macOS and \"Ctrl+C\"
   elsewhere. key is a letter (:c or \"c\", shown in capitals) or a word
   (\"Click\": \"⌘Click\", \"Ctrl+Click\")."
  ([key] (shortcut-text this-platform key))
  ([platform key]
   (let [k (name key)
         k (if (= 1 (count k)) (str/upper-case k) k)]
     (if (= :mac platform)
       (str "⌘" k)
       (str "Ctrl+" k)))))
