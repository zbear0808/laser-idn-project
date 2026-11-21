(ns laser-show.views.tabs.projectors-test
  "Pure helpers of the projectors tab: the output status line and the header
   of the rig strip (views/components/rig_strip.clj), and the scan-rate
   field's parser."
  (:require [clojure.test :refer [deftest is]]
            [laser-show.views.components.rig-strip :as rig-strip]
            [laser-show.views.tabs.projectors]))

(def ^:private output-status-text rig-strip/output-status-text)
(def ^:private parse-scan-rate #'laser-show.views.tabs.projectors/parse-scan-rate)
(def ^:private kept-settings-lines #'laser-show.views.tabs.projectors/kept-settings-lines)

(deftest kept-settings-lines-test
  (is (= ["Serial: NV-00123" "Output: 0"] (kept-settings-lines {:serial "NV-00123" :output 0}))
      "a driver that isn't installed: its settings as key: value lines, in their order, the keys humanised")
  (is (= ["Max packet bytes: 1400" "Fast: true" "Mode: wide" "Pins: [1 2]" "Ns key: x"]
         (kept-settings-lines {:max-packet-bytes 1400 :fast? true :mode :wide :pins [1 2] :acme/ns-key "x"})))
  (is (= [] (kept-settings-lines nil)))
  (is (= [] (kept-settings-lines {}))))

(deftest output-status-text-test
  (is (= "Streaming · 59.8 fps" (output-status-text {:state :streaming :fps 59.8})))
  (is (= "Streaming · 25.0 fps (limited by scan rate)"
         (output-status-text {:state :streaming :fps 25.0 :scan-limited? true})))
  (is (= "Streaming" (output-status-text {:state :streaming})))
  (is (= "Starting…" (output-status-text {:state :starting})))
  (is (= "Error: no host" (output-status-text {:state :error :error "no host"})))
  (is (nil? (output-status-text nil)) "not streaming: no line")
  (is (= "Streaming · 59.8 fps · Corner Pin skipped"
         (output-status-text {:state :streaming :fps 59.8 :warning "Corner Pin skipped"}))
      "a skipped calibration effect is named")
  (is (= "Safety error: output off"
         (output-status-text {:state :error :kind :safety :error "Safety error: output off" :detail "boom"}))
      "a safety error says the output is off, without \"Error: \""))

(deftest driver-status-text-test
  (is (= "In use by ShowCtl on 10.0.0.3"
         (output-status-text {:state :error :error "In use by ShowCtl on 10.0.0.3" :busy? true}))
      "a device another program has says so, without \"Error: \"")
  (is (= "Error: The output driver is not responding"
         (output-status-text {:state :error :error "The output driver is not responding"})))
  (is (= "Error: Output driver :acme/nova is not installed"
         (output-status-text {:state :error :error "Output driver :acme/nova is not installed"})))
  (is (= "Streaming · 59.8 fps · Interlock open"
         (output-status-text {:state :streaming :fps 59.8 :warning "Interlock open" :driver-info {:interlock? true}}))
      "the driver's warning comes last"))

(deftest card-status-text-test
  (is (= {:text "Driver not installed" :tooltip "Output driver :acme/nova is not installed"}
         (rig-strip/card-status-text {:state :error :error "Output driver :acme/nova is not installed"}
                                     {:found? true :driver-installed? false}))
      "short on the card, the whole message in its tooltip")
  (is (= {:text "Driver not responding" :tooltip "The output driver is not responding"}
         (rig-strip/card-status-text {:state :error :kind :stuck :error "The output driver is not responding"}
                                     {:found? true :driver-installed? true})))
  (is (= {:text "In use by ShowCtl on 10.0.0.3" :tooltip "In use by ShowCtl on 10.0.0.3"}
         (rig-strip/card-status-text {:state :error :error "In use by ShowCtl on 10.0.0.3" :busy? true}
                                     {:found? true :driver-installed? true})))
  (is (= {:text "Safety error: output off" :tooltip "boom"}
         (rig-strip/card-status-text {:state :error :kind :safety :error "Safety error: output off" :detail "boom"}
                                     {:found? true :driver-installed? true})))
  (is (= {:text "Not found in the last scan" :tooltip "Not found in the last scan"}
         (rig-strip/card-status-text nil {:found? false :driver-installed? true})))
  (is (nil? (rig-strip/card-status-text nil {:found? true :driver-installed? true}))))

(deftest rig-header-text-test
  (is (= "4 outputs · 3 on" (rig-strip/header-text [{:enabled? true} {:enabled? true} {:enabled? false} {:enabled? true}])))
  (is (= "1 output · 1 on" (rig-strip/header-text [{:enabled? true}])))
  (is (= "Tick On on a card to send it output." (rig-strip/header-text [{:enabled? false} {:enabled? false}])))
  (is (= "No lasers yet: Scan Network finds them, or add one with Add Manual..." (rig-strip/header-text []))))

(deftest parse-scan-rate-test
  (doseq [[text expected] [["25000" 25000] ["25 000" 25000] ["25,000" 25000] [" 30000 " 30000]
                           ["25k" 25000] ["25K" 25000] ["27.5k" 27500] ["0" 0]
                           ["" nil] ["abc" nil] ["-5" nil] ["2.5" nil] ["25kk" nil] ["k" nil]]]
    (is (= expected (parse-scan-rate text)) (pr-str text))))
