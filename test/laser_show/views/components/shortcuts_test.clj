(ns laser-show.views.components.shortcuts-test
  "Shortcut text as each platform writes it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [laser-show.views.components.shortcuts :as shortcuts]))

(deftest mac-and-other-text-test
  (testing "macOS: ⌘ and the key, as its menus show a :shortcut accelerator"
    (is (= "⌘C" (shortcuts/shortcut-text :mac :c)))
    (is (= "⌘V" (shortcuts/shortcut-text :mac "v")))
    (is (= "⌘Click" (shortcuts/shortcut-text :mac "Click"))))
  (testing "elsewhere: Ctrl+ and the key"
    (is (= "Ctrl+C" (shortcuts/shortcut-text :other :c)))
    (is (= "Ctrl+G" (shortcuts/shortcut-text :other "g")))
    (is (= "Ctrl+Click" (shortcuts/shortcut-text :other "Click"))))
  (testing "without a platform, this JVM's"
    (let [mac? (str/includes? (str/lower-case (System/getProperty "os.name")) "mac")]
      (is (= (if mac? "⌘C" "Ctrl+C") (shortcuts/shortcut-text :c))))))
