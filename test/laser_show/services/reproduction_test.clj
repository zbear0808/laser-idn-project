(ns laser-show.services.reproduction-test
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.services.frame-service :as frame-service]
            [laser-show.animation.types :as t]
            [laser-show.animation.generators :as gen]
            [laser-show.animation.effects.intensity :as intensity]
            [laser-show.idn.output-config :as output-config]
            [laser-show.idn.fragmentation :as frag]))

(defn validate-point [idx pt]
  (let [x (nth pt t/X)
        y (nth pt t/Y)
        r (nth pt t/R)
        g (nth pt t/G)
        b (nth pt t/B)]
    (when (or (Double/isNaN x) (Double/isInfinite x)
              (Double/isNaN y) (Double/isInfinite y)
              (Double/isNaN r) (Double/isInfinite r)
              (Double/isNaN g) (Double/isInfinite g)
              (Double/isNaN b) (Double/isInfinite b))
      (str "Point " idx " has invalid values: " pt))))

(deftest reproduction-test
  (testing "Reproduce user scenario"
    (let [track-id #uuid "00000000-0000-0000-0000-000000000001"
          cue-chain
          {:type :cue-chain-items,
           :tracks [{:id track-id, :name "Track 1", :zone-group-id :all}]
           :items
           [{:type :group,
             :id #uuid "e99e1510-227b-43b0-b311-83f723561f50",
             :name "New Group",
             :track-id track-id
             :items
             [{:type :preset,
               :id #uuid "2471b1ef-70e0-4534-b475-9b19056d9da8",
               :preset-id :spiral,
               :params
               {:red 1.0, :green 1.0, :blue 1.0, :turns 3, :start-radius 0.1, :end-radius 0.5, :num-points 128},
               :effects []
               :enabled? true}
              {:type :preset,
               :id #uuid "efadfc75-bb9b-42dc-8b16-f25eb21613aa",
               :preset-id :triangle,
               :params
               {:red 1.0, :green 1.0, :blue 1.0, :size 0.5, :num-points 21},
               :effects [],
               :enabled? true}
              {:type :preset,
               :id #uuid "234a18e5-5e35-4dec-8026-ac3982c5db1c",
               :preset-id :star,
               :params
               {:red 1.0, :green 1.0, :blue 1.0, :spikes 5, :outer-radius 0.5, :inner-radius 0.25, :num-points 8},
               :effects [],
               :enabled? true}],
             :enabled? true,
             :collapsed? false,
             :effects []}]}

          bpm 120.0
          elapsed-ms 1000
          trigger-time 0
          timing-ctx {:accumulated-beats 2.0 :accumulated-ms 1000.0 :phase-offset 0.0 :effective-beats 2.0 :bpm bpm :trigger-time trigger-time}

          full-cue-chain {:items (:items cue-chain) :tracks (:tracks cue-chain) :destination-zone {:zone-group-id :all}}

          frames-by-zone (frame-service/generate-frames-by-zone full-cue-chain elapsed-ms bpm trigger-time timing-ctx)
          generated-frame (get frames-by-zone :all)]

      (is (vector? generated-frame))
      (is (seq generated-frame))

      (println "Generated frame count:" (count generated-frame))

      ;; Validate all points
      (doseq [[idx pt] (map-indexed vector generated-frame)]
        (is (nil? (validate-point idx pt)) (str "Invalid point found: " (validate-point idx pt))))

      ;; Inspect Star Points
      ;; Spiral (128) + 2 + Triangle (63) + 2 = 195
      ;; The star should start around index 195
      (let [star-start-idx 195
            star-points (if (> (count generated-frame) star-start-idx)
                          (subvec generated-frame star-start-idx)
                          [])]
        (println "Star segment count:" (count star-points))

        ;; Check first few star points
        (doseq [[i pt] (map-indexed vector (take 10 star-points))]
          (println (str "Star Pt " i ": " pt)))

        ;; Check if they are accidentally blanked
        (let [visible-star-points (filter #(not (t/blanked? %)) star-points)]
          (println "Visible star points count:" (count visible-star-points))
          ;; (is (pos? (count visible-star-points)) "Star should have visible points")
          ))

      (testing "IDN Fragmentation"
        (let [buf (java.nio.ByteBuffer/allocate 65536)
              out-config laser-show.idn.output-config/standard-config
              packets (laser-show.idn.fragmentation/frame->fragmented-packets
                       buf generated-frame 0 0 1000
                       :output-config out-config)]
          (println "Generated packets:" (count packets))
          (println "Using Config:" (laser-show.idn.output-config/config-name out-config))
          (println "Bytes per sample:" (laser-show.idn.output-config/bytes-per-sample out-config))
          (is (= 8 (laser-show.idn.output-config/bytes-per-sample out-config)) "Standard config should be 8 bytes per sample")

          (let [bps (laser-show.idn.output-config/bytes-per-sample out-config)]
            (doseq [[i pkt] (map-indexed vector packets)]
              (println "Packet" i "size:" (count pkt))
              ;; Verify sample data is properly aligned to bytes-per-sample.
              ;; First packet: 8 (Chan) + 4 (Config) + 16 (Tags) + 4 (Frame) = 32 bytes overhead.
              ;; Sequel packet: 8 (Chan) + 4 (Frame) = 12 bytes overhead.
              (let [overhead (if (zero? i) 32 12)
                    sample-data-size (- (count pkt) overhead)]
                (is (zero? (mod sample-data-size bps))
                    (str "Packet " i " sample data (" sample-data-size " bytes) should be divisible by bytes-per-sample (" bps ")")))))

          (is (> (count packets) 1) "Frame should be fragmented (size > MTU)"))))))
