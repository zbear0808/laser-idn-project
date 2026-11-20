(ns laser-show.views.tabs.projectors-test
  "Pure helpers of the projectors tab: the output status line and the header
   of the rig strip (views/components/rig_strip.clj), and the scan-rate
   field's parser."
  (:require [clojure.test :refer [deftest is]]
            [laser-show.views.components.rig-strip :as rig-strip]
            [laser-show.views.tabs.projectors]))

(def ^:private output-status-text rig-strip/output-status-text)
(def ^:private parse-scan-rate #'laser-show.views.tabs.projectors/parse-scan-rate)

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
