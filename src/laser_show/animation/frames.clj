(ns laser-show.animation.frames
  "Joining frames (pure). A join puts a blanking jump between two frames, so
   the galvos travel dark from the end of one to the start of the next.
   frame-service/concatenate-frames is this one; it lives here so the routing
   (routing/compose.clj) can join frames without requiring the frame service."
  (:require [laser-show.animation.types :as t]))

(defn- create-blanking-jump
  "Create blanking points to safely jump from one position to another.
   Returns a vector of blanking points that:
   1. Turn off laser at current position (source)
   2. Move to new position with laser off (destination)
   
   This prevents visible lines when galvos travel between shapes."
  [from-point to-point]
  (let [;; First: blanking point at source (turn off laser at current position)
        blank-at-source (when from-point
                          [(from-point t/X) (from-point t/Y) 0.0 0.0 0.0])
        ;; Second: blanking point at destination (move to new position with laser off)
        blank-at-dest (when to-point
                        [(to-point t/X) (to-point t/Y) 0.0 0.0 0.0])]
    (filterv some? [blank-at-source blank-at-dest])))

(defn concatenate-frames
  "Concatenate multiple frames with blanking points between them.
   Returns a frame (vector of points) or nil."
  [frames]
  (when (seq frames)
    (let [combined-points (reduce
                           (fn [acc frame]
                             (if (empty? frame)
                               acc
                               (if (empty? acc)
                                 ;; First frame - just add points
                                 (vec frame)
                                 ;; Subsequent frames - add blanking jump then points
                                 (let [last-point (peek acc)
                                       first-new-point (first frame)
                                       blanking (create-blanking-jump last-point first-new-point)]
                                   (-> acc
                                       (into blanking)
                                       (into frame))))))
                           []
                           frames)]
      (when (seq combined-points)
        combined-points))))
