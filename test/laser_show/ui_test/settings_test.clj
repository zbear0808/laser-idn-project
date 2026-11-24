(ns laser-show.ui-test.settings-test
  "The Settings tab: its cards, the MIDI card's buttons and fields, and the
   OSC and keyboard switches. Nothing here opens a device or a socket:
   the connected and listening states are put in the state by hand, or the
   device worker (input.devices) finds a fake MIDI input or opens a stand-in
   OSC server."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.input.devices :as devices]
            [laser-show.input.fixtures :as input-fixtures]
            [laser-show.input.midi :as midi]
            [laser-show.input.osc :as osc-input]
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

(defn- button
  "The button on the Settings tab whose text is text, or nil."
  [text]
  (ui/on-fx-thread
   #(->> (tree-seq (fn [n] (instance? Parent n))
                   (fn [^Parent p] (seq (.getChildrenUnmodifiable p)))
                   (.getRoot (.getScene ^Node (first (ui/query-all "#osc-server-button")))))
         (filter (fn [n] (and (instance? Button n) (= text (.getText ^Button n)))))
         first)))

(defn- fire!
  "Fire the button whose text is text, as a click does. Fired, not clicked:
   the card can be below the tab's scrolled view."
  [text]
  (ui/wait-for! #(some? (button text)))
  (ui/on-fx-thread #(.fire ^Button (button text))))

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

(deftest keyboard-card-has-its-switch-test
  (open-settings!)
  (let [robot (ui/robot)
        switch "#keyboard-enabled"
        enabled? #(get-in (state/get-raw-state) [:backend :input :keyboard :enabled?])]
    (is (every? (texts) ["Ableton Link" "MIDI Configuration" "OSC Configuration" "Keyboard"]))
    (ui/assert-text! "#keyboard-reserved" "Escape is reserved for Stop.")
    (ui/assert-selected! switch)
    (ui/scroll-into-view! switch)
    (ui/click! robot switch)
    (ui/wait-for! #(false? (enabled?)))
    (ui/click! robot switch)
    (ui/wait-for! #(true? (enabled?)))))

(deftest keyboard-card-lists-overridden-shortcuts-test
  (open-settings!)
  (ui/assert-text! "#keyboard-overrides" "No built-in shortcuts overridden.")
  (let [cue {:type :trigger-cue :target [0 0]}]
    (events/write-state!
     #(-> %
          (assoc-in [:config :input :trigger-map] {{:source :keyboard :id :space} cue
                                                   {:source :keyboard :id :digit1} cue})
          (assoc-in [:triggers :definitions] {:retrigger {:match {:source :keyboard :id :r} :action cue}
                                              :fullscreen {:match {:source :keyboard :id :f11} :action cue :enabled? false}})))
    (ui/assert-text! "#keyboard-overrides" "Overridden shortcuts: R (Retrigger), Space (Play/Stop)")
    (ui/scroll-into-view! "#keyboard-enabled")
    (ui/click! (ui/robot) "#keyboard-enabled")
    (ui/assert-text! "#keyboard-overrides" "Shortcuts overridden when enabled: R (Retrigger), Space (Play/Stop)")))

(deftest disconnect-all-is-a-button-test
  (open-settings!)
  (testing "nothing connected: a secondary button, disabled, beside Auto-Connect"
    (is (ui/has-style-class? "#midi-disconnect-all-button" "button-secondary"))
    (is (not (ui/enabled? "#midi-disconnect-all-button")))
    (is (= "#404040" (fill-hex "#midi-disconnect-all-button")) "a button's background, not bare text"))
  (testing "a controller connected: it can be clicked"
    (events/write-state! assoc-in [:backend :input :midi :connected-devices] #{"Launchpad"})
    (ui/assert-enabled! "#midi-disconnect-all-button")
    (is (= "#505050" (fill-hex "#midi-disconnect-all-button")) "Auto-Connect's look")))

(deftest refresh-and-connect-go-through-the-device-worker-test
  ;; The device worker lists one fake input; nothing real is opened
  (let [pad (input-fixtures/midi-source "Pad")
        midi-status #(get-in (state/get-raw-state) [:backend :input :midi])]
    (with-redefs [midi/list-devices (constantly [pad])]
      (open-settings!)
      (fire! "Refresh")
      (ui/wait-for! #(= ["Pad"] (:available-devices (midi-status))))
      (testing "the input's row, with Connect"
        (fire! "Connect")
        (ui/wait-for! #(= #{"Pad"} (:connected-devices (midi-status))))
        (ui/wait-for! #(some? (button "Disconnect")))
        (is (nil? (button "Connect")) "its row's button says Disconnect now")
        (ui/assert-enabled! "#midi-disconnect-all-button"))
      (testing "and Disconnect closes it"
        (fire! "Disconnect")
        (ui/wait-for! #(empty? (:connected-devices (midi-status))))
        (ui/wait-for! #(some? (button "Connect")))
        (is (empty? (get-in (state/get-raw-state) [:backend :input :midi :requests])) "every request answered")))))

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
      (events/write-state! update-in [:backend :input :osc] assoc :server-running? true :enabled? true)
      (ui/wait-for! #(= "Stop Server" (button-text "#osc-server-button")))
      (is (= "#7F4C00" (fill-hex "#osc-server-button")) "a button, not bare text")
      ;; Fired, not clicked: the card can be below the tab's scrolled view
      (ui/on-fx-thread #(.fire ^Button (first (ui/query-all "#osc-server-button"))))
      (ui/wait-for! #(false? (:server-running? (osc))))
      (is (false? (:enabled? (osc))))
      (ui/wait-for! #(= "Start Server" (button-text "#osc-server-button"))))))


;; The OSC port field and Start Server (reactivity plan 09)

(defn- with-fake-osc-server
  "(f opened): f with the device worker's server stood in for at
   osc/open-server! and close-server! (no socket), the ports opened in
   opened. Whatever f leaves listening is stopped while they stand in."
  [f]
  (let [opened (atom [])]
    (with-redefs [osc-input/open-server! (fn [port _handler] (swap! opened conj port) {::port port})
                  osc-input/close-server! identity]
      (try
        (f opened)
        (finally
          (when (:server-running? (osc))
            (ui/on-fx-thread #(.fire ^Button (first (ui/query-all "#osc-server-button"))))
            (ui/wait-for! #(and (false? (:server-running? (osc))) (nil? (:osc (devices/open-ports)))))))))))

(defn- port-text [] (ui/node-text "#osc-port-field"))

(deftest typed-port-then-start-listens-on-it-test
  (with-fake-osc-server
    (fn [opened]
      (let [robot (ui/robot)]
        (open-settings!)
        (ui/fill! robot "#osc-port-field" "9100")
        (is (= 9000 (:port (osc))) "typed, not applied yet")
        ;; A click takes the focus first: the field applies what it shows
        ;; before the button's action
        (ui/scroll-into-view! "#osc-server-button")
        (ui/click! robot "#osc-server-button")
        (ui/wait-for! #(:server-running? (osc)))
        (is (= [9100] @opened) "the server was asked for the port typed, not the one Start was drawn with")
        (is (= 9100 (:port (osc))))
        (ui/wait-for! #(= "Stop Server" (button-text "#osc-server-button")))
        (is (= "9100" (port-text)))
        (is (not (ui/enabled? "#osc-port-field")) "listening: the port can't be edited")))))

(deftest port-field-takes-only-a-port-test
  (let [robot (ui/robot)]
    (open-settings!)
    (is (= "9000" (port-text)))
    (testing "Enter applies a port"
      (ui/fill! robot "#osc-port-field" "9200")
      (ui/submit! robot "#osc-port-field")
      (ui/wait-for! #(= 9200 (:port (osc))))
      (is (= "9200" (port-text))))
    (testing "so does the field losing focus"
      (ui/fill! robot "#osc-port-field" "9300")
      (ui/focus! robot "#osc-server-button")
      (ui/wait-for! #(= 9300 (:port (osc))))
      (is (= "9300" (port-text))))
    (doseq [typed ["abc" "0" "70000" ""]]
      (testing (str (pr-str typed) ": no port, so the field shows the port that is set again, and nothing changes")
        (ui/fill! robot "#osc-port-field" typed)
        (ui/submit! robot "#osc-port-field")
        (ui/wait-for! #(= "9300" (port-text)))
        (ui/fill! robot "#osc-port-field" typed)
        (ui/focus! robot "#osc-server-button")
        (ui/wait-for! #(= "9300" (port-text)))
        (is (events/flush!))
        (is (= 9300 (:port (osc))))
        (is (nil? (get-in (state/get-raw-state) [:ui :notice])))))))
