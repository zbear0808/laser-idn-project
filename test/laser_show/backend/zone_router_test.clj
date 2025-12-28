(ns laser-show.backend.zone-router-test
  "Tests for zone routing and target resolution."
  (:require [clojure.test :refer [deftest testing]]
            [laser-show.backend.zone-router :as router]))

;; ============================================================================
;; Target Resolution Tests
;; ============================================================================

(deftest test-resolve-single-zone-target
  (testing "Resolving single zone target"
    ;; TODO: Test make-zone-target and resolve-target
    ))

(deftest test-resolve-zone-group-target
  (testing "Resolving zone group target"
    ;; TODO: Test make-group-target and resolve-target
    ))

(deftest test-resolve-multi-zone-target
  (testing "Resolving multiple zones target"
    ;; TODO: Test make-zones-target and resolve-target
    ))

;; ============================================================================
;; Priority Resolution Tests
;; ============================================================================

(deftest test-priority-conflicts
  (testing "Priority conflict resolution"
    ;; TODO: Test resolve-priority-conflicts
    ;; When multiple zones target same projector, highest priority wins
    ))

;; ============================================================================
;; Frame Preparation Tests
;; ============================================================================

(deftest test-prepare-projector-frames
  (testing "Frame preparation for projectors"
    ;; TODO: Test prepare-projector-frames
    ;; Verify correct transformation application
    ))
