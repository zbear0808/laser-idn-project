(ns laser-show.routing.compose
  "What one laser draws from a tick (plans/2026-11-19-zones/01 §6, §7; 05
   §4). Pure: the tick's cues, each its frames by key ({key frame}, one
   render), and the tick's routing (routing.rig/routing) go in; the laser's
   frame comes out, before its projector effects. The engines, the preview's
   laser cells and the cue warnings compose from the same snapshot, so they
   agree on every tick.

   For laser P and one cue:
   1. each key's entries for P are the union of its refs' entries
      (routing.rig/resolve-ref), and a key with none is skipped;
   2. its region is the whole laser when an entry is whole or the entries
      cover both halves, else that half; a side (a :both middle's) is kept
      only when every entry has it (contributions);
   3. each key is one contribution, however many of its refs reach P;
   4. contributions with identical? frames are one picture (merge-pictures);
   5. a :both middle splits when, over the tick's cues, a picture tagged
      :left and a different one tagged :right reach it (split-sides?);
   6. the pictures go into the buckets :L, whole and :R, each in key order
      (key-order), each bucket is joined, :L and :R are fitted into their
      halves (fit-region), and the buckets are joined (build).
   Then the cues are joined in their order (projector-frame).

   A laser whose safe area is too small for halves (routing :regions nil)
   draws its whole content only, and never splits."
  (:require [laser-show.animation.frames :as frames]
            [laser-show.routing.rig :as rig]))


;; The order of keys

(defn- ref-order [ref]
  (if-let [i (rig/zone-index ref)]
    [0 i "" ""]
    (if (keyword? ref)
      [1 0 (or (namespace ref) "") (name ref)]
      [2 0 "" (str ref)])))

(defn key-order
  "A frame key's place, as a tuple compare sorts: zone refs first by index,
   then the other single refs by namespace and name, then vector keys, ref by
   ref in that same order (a key that is the start of another comes first).
   So the order never depends on the order of the keys in a cue's map."
  [k]
  (if (vector? k)
    ;; ref by ref: [r1 [r2 [r3 nil]]] compares as a list would
    [1 (reduce (fn [tail r] [(ref-order r) tail]) nil (rseq k))]
    [0 (ref-order k)]))


;; Contributions and pictures

(defn- region-of
  "Spec §6 step 2: the whole laser (nil) when any region is, or when they
   cover both halves; otherwise their one half."
  [regions]
  (if (or (some nil? regions) (and (some #{:L} regions) (some #{:R} regions)))
    nil
    (first regions)))

(defn- agreed
  "The side every entry has, or nil."
  [sides]
  (let [[s & more] (distinct sides)]
    (when-not more s)))

(defn contributions
  "Spec §6 steps 1-3 for one cue: for each key with entries for pid, in key
   order, {:keys [k] :frame f :region r :side s}, r nil for the whole laser.
   A vector key is one contribution however many of its refs reach pid."
  [zone-frames routing pid]
  (->> zone-frames
       (keep (fn [[k f]]
               (when (seq f)
                 (let [entries (into [] (comp (mapcat #(rig/resolve-ref routing %))
                                              (filter #(= pid (nth % 0))))
                                     (rig/key-refs k))]
                   (when (seq entries)
                     {:keys [k]
                      :frame f
                      :region (region-of (map #(nth % 1) entries))
                      :side (agreed (map #(nth % 2) entries))})))))
       (sort-by (comp key-order first :keys))
       vec))

(defn merge-pictures
  "Spec §6 step 4: contributions whose frames are identical? are one
   picture, with their keys together, their regions merged by step 2's rule
   and a side only when both have it. In order of each picture's first key."
  [contribs]
  (reduce (fn [acc c]
            (if-let [i (first (keep-indexed (fn [i m] (when (identical? (:frame m) (:frame c)) i)) acc))]
              (update acc i (fn [m] {:keys (into (:keys m) (:keys c))
                                     :frame (:frame m)
                                     :region (region-of [(:region m) (:region c)])
                                     :side (when (= (:side m) (:side c)) (:side m))}))
              (conj acc c)))
          []
          contribs))

(defn- pictures [zone-frames routing pid]
  (merge-pictures (contributions zone-frames routing pid)))

(defn- tagged-apart?
  "Is there, among pictures, a whole one tagged :left and a different one
   tagged :right?"
  [pictures]
  (let [tagged (filter #(and (nil? (:region %)) (:side %)) pictures)
        lefts (filterv #(= :left (:side %)) tagged)
        rights (filterv #(= :right (:side %)) tagged)]
    (boolean (some (fn [l] (some #(not (identical? (:frame l) (:frame %))) rights)) lefts))))

(defn split-sides?
  "Spec §6 step 5 (Z3 S-1): does pid split, its pictures tagged :left going
   to its left half and those tagged :right to its right? True when, over
   all of the tick's cues, a whole picture tagged :left and a different one
   tagged :right reach it, and it has halves."
  [cues routing pid]
  (boolean (and (get-in routing [:outputs pid :regions])
                (tagged-apart? (into [] (mapcat #(pictures (:zone-frames %) routing pid)) cues)))))


;; Plans and frames

(defn- plan-of
  "pictures in their buckets; with no halves (regions nil), the ones for a
   half are left out."
  [pictures regions split?]
  (reduce (fn [plan {:keys [frame region side]}]
            (let [bucket (cond region region
                               (and split? (= :left side)) :L
                               (and split? (= :right side)) :R
                               :else :whole)]
              (if (and (not= :whole bucket) (nil? regions))
                plan
                (update plan bucket conj frame))))
          {:L [] :whole [] :R []}
          pictures))

(defn cue-plan
  "Spec §6 steps 4-6 for one cue: {:L [f …] :whole [f …] :R [f …]}, each in
   key order; with split?, the pictures tagged :left go to :L and :right to
   :R. nil when the cue doesn't reach pid."
  [zone-frames routing pid split?]
  (let [ps (pictures zone-frames routing pid)]
    (when (seq ps)
      (plan-of ps (get-in routing [:outputs pid :regions]) split?))))

(defn plan-points
  "How many points build makes of plan, from the counts: every frame's
   points and 2 per join (frames/concatenate-frames' blanking jump)."
  [plan]
  (let [fs (concat (:L plan) (:whole plan) (:R plan))
        n (count fs)]
    (if (zero? n)
      0
      (+ (reduce + (map count fs)) (* 2 (dec n))))))

(defn fit-region
  "Spec §7: frame fitted into a half, [cx cy s]: each point (x, y) moves to
   (cx + s·x, cy + s·y), its colour unchanged."
  [frame [cx cy s]]
  (let [cx (double cx) cy (double cy) s (double s)]
    (mapv (fn [p]
            (let [x (+ cx (* s (double (nth p 0))))
                  y (+ cy (* s (double (nth p 1))))]
              (if (== 5 (count p))
                [x y (nth p 2) (nth p 3) (nth p 4)]
                (assoc p 0 x 1 y))))
          frame)))

(defn build
  "plan's frame: each bucket joined, :L and :R fitted into fits' halves
   ({:L [cx cy s] :R …}), then :L, whole and :R joined. A half with no fit
   is left out. nil when nothing is left."
  [plan fits]
  (let [half (fn [k]
               (when-let [fit (get fits k)]
                 (some-> (frames/concatenate-frames (get plan k)) (fit-region fit))))]
    (frames/concatenate-frames (filterv some? [(half :L) (frames/concatenate-frames (:whole plan)) (half :R)]))))

(defn projector-frame
  "What pid draws of the tick's cues (spec §6), before its projector
   effects: {:frame f :regions n}, every cue's frame (build) joined in the
   cues' order, n how many of its halves it draws (0-2). nil when nothing
   reaches pid. When it would draw halves its safe area has no room for
   (routing :regions nil, S-9), it draws its whole content only, and says
   :no-room? true (with :frame [] when there is nothing else)."
  [cues routing pid]
  (when-let [out (get-in routing [:outputs pid])]
    (let [per-cue (mapv #(pictures (:zone-frames %) routing pid) cues)]
      (when (some seq per-cue)
        (let [regions (:regions out)
              split? (boolean (and regions (tagged-apart? (into [] cat per-cue))))
              plans (into [] (comp (filter seq) (map #(plan-of % regions split?))) per-cue)
              built (into [] (keep #(build % regions)) plans)]
          (cond-> {:frame (or (frames/concatenate-frames built) [])
                   :regions (count (filter (fn [k] (some #(seq (get % k)) plans)) [:L :R]))}
            (and (nil? regions) (some #(some :region %) per-cue)) (assoc :no-room? true)))))))

(defn cue-points
  "How many points pid draws of one cue (plan-points), 0 when the cue
   doesn't reach it."
  [zone-frames routing pid]
  (if-let [out (get-in routing [:outputs pid])]
    (plan-points (plan-of (pictures zone-frames routing pid) (:regions out) false))
    0))


;; Cue warnings (spec §8)

(defn- heaviest-item
  "{:id :points} of the top-level item of zone-frames' :item-points
   metadata ([[item-id {key points}] …], an edited cue's) with the most
   points on pid, counting the keys that reach it; nil without the counts."
  [zone-frames routing pid]
  (let [reaches? (memoize (fn [k] (some #(= pid (nth % 0)) (mapcat #(rig/resolve-ref routing %) (rig/key-refs k)))))]
    (reduce (fn [best [id by-key]]
              (let [points (reduce-kv (fn [n k p] (if (reaches? k) (+ n (long p)) n)) 0 by-key)]
                (if (and (pos? points) (or (nil? best) (> points (:points best))))
                  {:id id :points points}
                  best)))
            nil
            (:item-points (meta zone-frames)))))

(defn cue-warnings
  "Spec §8's cue warnings: {cell {:projector :points :fps :min-fps :scan-rate
   :item}} for each cue of the tick that a laser it reaches would draw below
   that laser's :min-fps (its scan rate over the cue's points on it,
   cue-points, at most its :decimate-budget: a laser with Decimate in its
   calibration chain is counted as drawing no more, Q-D-2 B, though anchors
   can leave more; the warning is an estimate), at its worst laser: the
   lowest fps, ties to the lower :index, an excluded laser's (nil) last.
   :item {:id :points} is the top-level item with the most points on that
   laser, when the cue's frames carry :item-points (an edited cue's,
   frame-service)."
  [cues routing]
  (into {}
        (keep (fn [{:keys [cell zone-frames]}]
                (when-let [worst (->> (:outputs routing)
                                      (keep (fn [[pid {:keys [index scan-rate fit decimate-budget]}]]
                                              (let [points (cond-> (cue-points zone-frames routing pid)
                                                             decimate-budget (min decimate-budget))
                                                    fps (when (pos? points) (/ (double scan-rate) points))]
                                                (when (and fps (< fps (double (:min-fps fit))))
                                                  {:projector pid :points points :fps fps :min-fps (:min-fps fit)
                                                   :scan-rate scan-rate :index index}))))
                                      (sort-by (juxt :fps #(or (:index %) Long/MAX_VALUE) (comp str :projector)))
                                      first)]
                  [cell (let [item (heaviest-item zone-frames routing (:projector worst))]
                          (cond-> (dissoc worst :index)
                            item (assoc :item item)))])))
        cues))
