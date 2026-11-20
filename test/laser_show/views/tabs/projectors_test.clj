(ns laser-show.views.tabs.projectors-test
  "Pure helpers of the projectors tab: the output status line and the
   scan-rate field's parser."
  (:require [clojure.test :refer [deftest is]]
            [laser-show.views.tabs.projectors]))

(def ^:private output-status-text #'laser-show.views.tabs.projectors/output-status-text)
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
      "a skipped calibration effect is named"))

(deftest parse-scan-rate-test
  (doseq [[text expected] [["25000" 25000] ["25 000" 25000] ["25,000" 25000] [" 30000 " 30000]
                           ["25k" 25000] ["25K" 25000] ["27.5k" 27500] ["0" 0]
                           ["" nil] ["abc" nil] ["-5" nil] ["2.5" nil] ["25kk" nil] ["k" nil]]]
    (is (= expected (parse-scan-rate text)) (pr-str text))))
