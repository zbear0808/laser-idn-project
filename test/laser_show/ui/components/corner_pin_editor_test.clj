(ns laser-show.ui.components.corner-pin-editor-test
  "Tests for corner pin visual editor component - PUBLIC API ONLY."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.ui.components.corner-pin-editor :as corner-pin]))

;; ============================================================================
;; Public API Behavioral Tests
;; ============================================================================

(deftest editor-creation-with-defaults
  (testing "Editor can be created with default corners"
    (let [editor (corner-pin/create-corner-pin-editor)]
      (is (some? editor) "Editor should be created")
      (is (map? editor) "Editor should be a map with API functions")
      ;; Check initial corners (should be unit square -1 to 1)
      (let [corners ((:get-corners editor))]
        (is (map? corners) "Corners should be returned as map")
        (is (every? #(contains? corners %) [:tl :tr :bl :br]) "All four corners should be present")))))

(deftest editor-respects-custom-corners
  (testing "Editor uses custom initial corners when provided"
    (let [custom-corners {:tl {:x -0.5 :y 0.5}
                          :tr {:x 0.5 :y 0.5}
                          :bl {:x -0.5 :y -0.5}
                          :br {:x 0.5 :y -0.5}}
          editor (corner-pin/create-corner-pin-editor :initial-corners custom-corners)
          corners ((:get-corners editor))]
      (is (= -0.5 (get-in corners [:tl :x])))
      (is (= 0.5 (get-in corners [:tl :y])))
      (is (= 0.5 (get-in corners [:tr :x])))
      (is (= 0.5 (get-in corners [:tr :y]))))))

(deftest corners-can-be-updated
  (testing "Corners can be updated programmatically"
    (let [editor (corner-pin/create-corner-pin-editor)
          new-corners {:tl {:x -1.5 :y 1.5}
                       :tr {:x 1.5 :y 1.5}
                       :bl {:x -1.5 :y -1.5}
                       :br {:x 1.5 :y -1.5}}]
      ((:set-corners! editor) new-corners)
      (let [updated-corners ((:get-corners editor))]
        (is (= -1.5 (get-in updated-corners [:tl :x])))
        (is (= 1.5 (get-in updated-corners [:tl :y])))
        (is (= 1.5 (get-in updated-corners [:tr :x])))
        (is (= 1.5 (get-in updated-corners [:tr :y])))))))

(deftest on-change-callback-behavior
  (testing "on-change callback is invoked when corners are updated"
    (let [callback-called (atom 0)
          callback-corners (atom nil)
          editor (corner-pin/create-corner-pin-editor
                  :on-change (fn [corners]
                               (swap! callback-called inc)
                               (reset! callback-corners corners)))]
      ;; Update corners
      ((:set-corners! editor) {:tl {:x -0.8 :y 0.8}
                               :tr {:x 0.8 :y 0.8}
                               :bl {:x -0.8 :y -0.8}
                               :br {:x 0.8 :y -0.8}})
      ;; Callback should have been invoked
      (is (pos? @callback-called) "Callback should be called when corners change")
      (is (some? @callback-corners) "Callback should receive corner data"))))
