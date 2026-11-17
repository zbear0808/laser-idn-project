(ns laser-show.views.components.visual-editors.timeline.drop
  "Where a drag from the timeline browser lands. Pure: a hit (from
   layout/hit-test, or a track header or device chain) and the dragged bank
   item in, the one event the drop dispatches and the preview to draw while
   hovering out. A drop is one stateless decision, unlike the
   press-move-release gestures in timeline.gesture.
   See plans/2026-11-08-timeline-rework/08-drag-from-browser.md §4.4."
  (:require [laser-show.timeline.model :as model]))

(defn valid-payload?
  "A bank-item drag payload: {:kind :preset|:effect :id kw :category kw|#{kw}|nil}."
  [m]
  (boolean
   (and (map? m)
        (contains? #{:preset :effect} (:kind m))
        (keyword? (:id m))
        (let [c (:category m)]
          (or (nil? c) (keyword? c) (and (set? c) (every? keyword? c)))))))

(defn zone-effect?
  "Zone effects reroute a clip's content, so they only go on clips."
  [{:keys [category]}]
  (or (= :zone category)
      (and (set? category) (contains? category :zone))))

(defn- preset-target [base hit {:keys [tracks snap]}]
  (let [{:keys [type row beat track-id]} hit]
    (case type
      (:lane :clip)
      (when (and (:id row) (number? beat))
        (let [start (max 0.0 (model/snap (double beat) snap))
              leaf (if (= :folder (:kind row)) (model/insert-track tracks (:id row)) (:id row))]
          (when leaf
            {:event (assoc base :track-id (:id row) :start start)
             :preview {:ghost {:row-id leaf :start start :length model/default-clip-length}}})))

      ;; No :start: the handler adds at the cursor, as a click does
      :header
      (when track-id
        {:event (assoc base :track-id track-id)})

      nil)))

(defn- effect-target [base payload hit]
  (let [{:keys [type row clip track-id index target]} hit
        zone? (zone-effect? payload)
        on-track (fn [id] (when (and id (not zone?))
                            {:event (assoc base :target-track-id id :select-target? true)
                             :preview {:highlight-row id}}))]
    (case type
      ;; Summary bars on a folder row stand for many clips: target the folder
      :clip (if (= :folder (:kind row))
              (on-track (:id row))
              (when-let [id (:id clip)]
                {:event (assoc base :target-item-id id :select-target? true)
                 :preview {:highlight-clip id}}))
      :lane (on-track (:id row))
      :header (on-track track-id)
      ;; The chain shows the target already: no select
      :chain (let [{:keys [kind entity]} target
                   id (:id entity)]
               (when (and id (nat-int? index)
                          (or (= :clip kind) (and (= :track kind) (not zone?))))
                 {:event (assoc base (if (= :clip kind) :target-item-id :target-track-id) id :index index)}))
      nil)))

(defn drop-target
  "{:event e :preview p} for dropping the bank item `payload` on `hit`, or nil
   when it can't land there. `hit` is a layout/hit-test result with the
   pointer's :beat (clip hits don't carry one), {:type :header :track-id id},
   or {:type :chain :target selection-target :index i}. opts: :col :row, the
   cue's :tracks and the editor's :snap. Previews: {:ghost {:row-id :start
   :length}}, {:highlight-clip id} or {:highlight-row id}. Never throws."
  [payload hit {:keys [col row] :as opts}]
  (try
    (when (and (valid-payload? payload) (map? hit))
      (let [base {:col col :row row :item-id (:id payload)}]
        (case (:kind payload)
          :preset (preset-target (assoc base :event/type :timeline/add-preset-to-track) hit opts)
          :effect (effect-target (assoc base :event/type :timeline/add-effect-to-item) payload hit))))
    (catch Exception _ nil)))

(defn chain-insert-index
  "Insertion index for a drop at x among the device cards, given each card's
   [min-x width] in the same coordinates: before the first card whose centre
   is right of x, else after the last."
  [spans x]
  (or (first (keep-indexed (fn [i [min-x w]]
                             (when (> (+ (double min-x) (/ (double w) 2.0)) (double x)) i))
                           spans))
      (count spans)))
