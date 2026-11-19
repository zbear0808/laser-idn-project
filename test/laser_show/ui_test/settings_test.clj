(ns laser-show.ui-test.settings-test
  "The Settings tab: its cards, the MIDI card's buttons and fields, and the
   OSC card's one switch. Nothing here opens a device or a socket: the
   connected and listening states are put in the state by hand."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.scene Node Parent]
           [javafx.scene.control Button CheckBox Labeled]
           [javafx.scene.layout BackgroundFill Region]
           [javafx.scene.paint Color]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(defn- open-settings! []
  (ui/click! (ui/robot) "#settings-tab")
  (ui/assert-visible! "#osc-server-button"))

(defn- texts
  "The texts of the labels and buttons on the Settings tab."
  []
  (let [nodes (ui/on-fx-thread
               #(vec (tree-seq (fn [n] (instance? Parent n))
                               (fn [^Parent p] (seq (.getChildrenUnmodifiable p)))
                               (.getRoot (.getScene ^Node (first (ui/query-all "#osc-server-button")))))))]
    (set (keep #(when (instance? Labeled %) (ui/on-fx-thread (fn [] (.getText ^Labeled %)))) nodes))))

(defn- fill-hex
  "A region's background colour as #RRGGBB, with its CSS applied, or nil."
  [selector]
  (let [^Region region (first (ui/query-all selector))]
    (ui/on-fx-thread
     #(do (.applyCss region)
          (when-let [^Color c (some-> region .getBackground .getFills ^BackgroundFill first .getFill)]
            (when (pos? (.getOpacity c))
              (let [byte (fn [v] (Math/round (* 255.0 (double v))))]
                (format "#%02X%02X%02X" (byte (.getRed c)) (byte (.getGreen c)) (byte (.getBlue c))))))))))

(defn- button-text [selector]
  (ui/on-fx-thread #(.getText ^Button (first (ui/query-all selector)))))

(defn- tooltip-text [selector]
  (ui/on-fx-thread #(some-> ^Button (first (ui/query-all selector)) .getTooltip .getText)))

(defn- osc [] (get-in (state/get-raw-state) [:backend :input :osc]))

(deftest keyboard-card-is-gone-test
  (open-settings!)
  (let [shown (texts)]
    (is (every? shown ["Ableton Link" "MIDI Configuration" "OSC Configuration"]))
    (is (not-any? shown ["Keyboard Configuration" "Keyboard shortcuts and hotkey configuration coming soon..."])
        "no card that only says it's coming")))

(deftest disconnect-all-is-a-button-test
  (open-settings!)
  (testing "nothing connected: a secondary button, disabled, beside Auto-Connect"
    (is (ui/has-style-class? "#midi-disconnect-all-button" "button-secondary"))
    (is (not (ui/enabled? "#midi-disconnect-all-button")))
    (is (= "#404040" (fill-hex "#midi-disconnect-all-button")) "a button's background, not bare text"))
  (testing "a controller connected: it can be clicked"
    (state/swap-state! assoc-in [:backend :input :midi :connected-devices] #{"Launchpad"})
    (ui/assert-enabled! "#midi-disconnect-all-button")
    (is (= "#505050" (fill-hex "#midi-disconnect-all-button")) "Auto-Connect's look")))

(deftest midi-channel-filter-commits-test
  (let [robot (ui/robot)
        channels #(get-in (state/get-raw-state) [:backend :input :midi :channel-filter])]
    (open-settings!)
    (ui/fill! robot "#midi-channel-filter-field" "1, 2")
    (ui/submit! robot "#midi-channel-filter-field")
    (ui/wait-for! #(= #{1 2} (channels)))
    (testing "empty: all channels again"
      (ui/fill! robot "#midi-channel-filter-field" "")
      (ui/submit! robot "#midi-channel-filter-field")
      (ui/wait-for! #(nil? (channels))))))

(deftest osc-has-one-switch-test
  (let [robot (ui/robot)]
    (open-settings!)
    (testing "no Enabled box beside the server's button: the button is the switch"
      (is (empty? (filter #(and (instance? CheckBox %) (= "Enabled" (ui/on-fx-thread (fn [] (.getText ^CheckBox %)))))
                          (ui/query-all "#osc-settings .check-box"))))
      (is (= "Start Server" (button-text "#osc-server-button")))
      (is (= "Listen for OSC messages on this port" (tooltip-text "#osc-server-button"))))
    (testing "its port is the one typed"
      (ui/fill! robot "#osc-port-field" "9100")
      (ui/submit! robot "#osc-port-field")
      (ui/wait-for! #(= 9100 (:port (osc)))))
    (testing "listening: Stop Server, a warning button, stops it and takes nothing in"
      (state/swap-state! update-in [:backend :input :osc] assoc :server-running? true :enabled? true)
      (ui/wait-for! #(= "Stop Server" (button-text "#osc-server-button")))
      (is (= "#7F4C00" (fill-hex "#osc-server-button")) "a button, not bare text")
      ;; Fired, not clicked: the card can be below the tab's scrolled view
      (ui/on-fx-thread #(.fire ^Button (first (ui/query-all "#osc-server-button"))))
      (ui/wait-for! #(false? (:server-running? (osc))))
      (is (false? (:enabled? (osc))))
      (ui/wait-for! #(= "Start Server" (button-text "#osc-server-button"))))))
