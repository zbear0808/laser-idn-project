(ns laser-show.ui-test.cases.example-test
  "Example UI integration tests demonstrating the LLM vision testing framework.
   All test functions are inside a (comment ...) block — they will not run
   automatically. Evaluate them manually from a REPL when ready to test."
  (:require [laser-show.ui-test.driver :as driver]
            [laser-show.ui-test.llm-agent :as llm]
            [laser-show.ui-test.runner :as runner]))

(comment

  ;; ===========================================================================
  ;; Global config for REPL evaluation
  ;; ===========================================================================
  (def config (llm/load-config))

  ;; ===========================================================================
  ;; Example 1: Verify the initial application render
  ;; ===========================================================================
  (defn test-initial-render []
    (runner/run-test!
     (fn []
       (driver/capture-node! nil "test-results/initial-render.jpg")
       (let [{:keys [pass? reasoning]}
             (llm/verify-ui config
                            "test-results/initial-render.jpg"
                            "Does this look like a laser show control application?
                             Is there a toolbar at the top with transport controls (play/stop)?
                             Is there a grid visible in the main area?")]
         (println "Initial render test:" (if pass? "PASS" "FAIL"))
         (println "Reasoning:" reasoning)
         (assert pass? reasoning)))))

  ;; ===========================================================================
  ;; Example 2: Test transport button toggles playback state
  ;; ===========================================================================
  (defn test-transport-play []
    (runner/run-test!
     (fn []
       (driver/click! "#play-btn")
       (driver/wait!)
       (driver/capture-node! nil "test-results/after-play.jpg")
       (let [{:keys [pass? reasoning]}
             (llm/verify-ui config
                            "test-results/after-play.jpg"
                            "Look at the transport controls in the toolbar.
                             Is there a stop button visible (indicating playback is active)?
                             Has the play button changed to a stop icon?")]
         (println "Transport play test:" (if pass? "PASS" "FAIL"))
         (println "Reasoning:" reasoning)
         (assert pass? reasoning)))))

  ;; ===========================================================================
  ;; Example 3: Test opening a modal dialog
  ;; ===========================================================================
  (defn test-modal-dialog-opens []
    (runner/run-test!
     (fn []
       ;; Click a button that opens a dialog/modal
       (driver/click! "#effect-editor-btn")
       (driver/wait!)
       ;; Capture the modal — driver traverses Window/getWindows to find it
       (driver/capture-node! nil "test-results/effect-editor-modal.jpg")
       (let [{:keys [pass? reasoning]}
             (llm/verify-ui config
                            "test-results/effect-editor-modal.jpg"
                            "Is there a modal dialog or editor window visible?
                             Does it appear to be an effect chain editor with controls?")]
         (println "Modal dialog test:" (if pass? "PASS" "FAIL"))
         (println "Reasoning:" reasoning)
         (assert pass? reasoning)))))

  ;; ===========================================================================
  ;; Example 4: Test slider interaction
  ;; ===========================================================================
  (defn test-slider-changes-value []
    (runner/run-test!
     (fn []
       (driver/slide! "#bpm-slider" 140.0)
       (driver/wait!)
       (driver/capture-node! nil "test-results/bpm-changed.jpg")
       (let [{:keys [pass? reasoning]}
             (llm/verify-ui config
                            "test-results/bpm-changed.jpg"
                            "Look at the BPM display or slider in the toolbar/status area.
                             Does it show a value of 140 or close to 140?")]
         (println "Slider test:" (if pass? "PASS" "FAIL"))
         (println "Reasoning:" reasoning)
         (assert pass? reasoning)))))

  ;; ===========================================================================
  ;; Run all examples (evaluate manually in REPL)
  ;; ===========================================================================
  (defn run-all-examples []
    (doseq [test-fn [test-initial-render
                     test-transport-play
                     test-modal-dialog-opens
                     test-slider-changes-value]]
      (try
        (test-fn)
        (catch Exception e
          (println "FAILED:" (.getMessage e))))))

  ) ;; end comment
