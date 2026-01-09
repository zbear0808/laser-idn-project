(ns laser-show.events.handlers.effect-params
  "Pure helpers for effect parameter manipulation.
   
   Works with any effect regardless of where it lives in state:
   - Effect chains: [:chains :effect-chains [col row] :items ...]
   - Cue chain items: [:chains :cue-chains [col row] :items ... :effects ...]  
   - Projector effects: [:chains :projector-effects id :items ...]
   
   All functions are path-agnostic and take explicit paths to the effect's
   :params map. This allows the same logic to be reused across different
   domains without duplication.")


;; Curve Point Operations


(defn add-curve-point
  "Add a new point to a curve, maintaining sorted order by X coordinate.
   
   Parameters:
   - state: Application state
   - params-path: Full path to the effect's :params map
   - channel: Channel keyword (:r, :g, or :b)
   - x, y: Point coordinates (will be converted to integers)
   
   Returns: Updated state with new point added and sorted"
  [state params-path channel x y]
  (let [param-key (keyword (str (name channel) "-curve-points"))
        curve-path (conj params-path param-key)
        current-points (get-in state curve-path [[0 0] [255 255]])
        new-point [(int x) (int y)]
        new-points (->> (conj current-points new-point)
                        (sort-by first)
                        vec)]
    (assoc-in state curve-path new-points)))

(defn update-curve-point
  "Update a curve point at the given index.
   Corner points (first/last) can only move in Y direction to maintain curve bounds.
   Non-corner points can move freely and will be re-sorted by X.
   
   Parameters:
   - state: Application state
   - params-path: Full path to the effect's :params map
   - channel: Channel keyword (:r, :g, or :b)
   - point-idx: Index of point to update
   - x, y: New coordinates (will be converted to integers)
   
   Returns: Updated state with point modified and re-sorted"
  [state params-path channel point-idx x y]
  (let [param-key (keyword (str (name channel) "-curve-points"))
        curve-path (conj params-path param-key)
        current-points (get-in state curve-path [[0 0] [255 255]])
        num-points (count current-points)
        ;; Corner points (first and last) can only move in Y
        is-corner? (or (= point-idx 0) (= point-idx (dec num-points)))
        current-point (nth current-points point-idx [0 0])
        updated-point (if is-corner?
                        [(first current-point) (int y)]  ; Keep original X for corners
                        [(int x) (int y)])
        updated-points (assoc current-points point-idx updated-point)
        sorted-points (->> updated-points
                          (sort-by first)
                          vec)]
    (assoc-in state curve-path sorted-points)))

(defn remove-curve-point
  "Remove a curve point at the given index.
   Corner points (first/last) cannot be removed - they define the curve bounds.
   
   Parameters:
   - state: Application state
   - params-path: Full path to the effect's :params map
   - channel: Channel keyword (:r, :g, or :b)
   - point-idx: Index of point to remove
   
   Returns: Updated state with point removed, or unchanged if corner point"
  [state params-path channel point-idx]
  (let [param-key (keyword (str (name channel) "-curve-points"))
        curve-path (conj params-path param-key)
        current-points (get-in state curve-path [[0 0] [255 255]])
        num-points (count current-points)
        ;; Cannot remove corner points (first and last)
        is-corner? (or (= point-idx 0) (= point-idx (dec num-points)))]
    (if is-corner?
      state  ; No change for corner points
      (let [updated-points (vec (concat (subvec current-points 0 point-idx)
                                        (subvec current-points (inc point-idx))))]
        (assoc-in state curve-path updated-points)))))


;; Spatial Parameter Operations


(defn update-spatial-params
  "Update x/y parameters from spatial drag operation.
   Uses param-map to translate point-id to actual parameter keys.
   
   Parameters:
   - state: Application state
   - params-path: Full path to the effect's :params map
   - point-id: ID of dragged point (e.g., :center, :tl, :tr, :br, :bl)
   - x, y: New coordinates in world space
   - param-map: Map from point IDs to param key pairs
                Example: {:center {:x :x :y :y}
                         :tl {:x :tl-x :y :tl-y}}
   
   Returns: Updated state with both x and y params set, or unchanged if point-id not found"
  [state params-path point-id x y param-map]
  (let [point-params (get param-map point-id)]
    (if point-params
      (let [x-key (:x point-params)
            y-key (:y point-params)]
        (-> state
            (assoc-in (conj params-path x-key) x)
            (assoc-in (conj params-path y-key) y)))
      state)))


;; UI State Operations


(defn set-active-curve-channel
  "Set which curve channel (R/G/B) is currently active in the UI.
   This is UI state, not effect state, so it uses a different path.
   
   Parameters:
   - state: Application state
   - ui-path: Path to UI state for this effect's curve editor
   - channel: Channel keyword (:r, :g, or :b)
   
   Returns: Updated state with active channel set"
  [state ui-path channel]
  (assoc-in state (conj ui-path :active-curve-channel) channel))
