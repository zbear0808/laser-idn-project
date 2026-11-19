(ns laser-show.views.components.measured
  "A view described from its own size: the size its parent lays it out at,
   kept in view-local state (fx/ext-state), as the timeline editor keeps its
   lanes' size, and never in app state. For panes that grow with the window:
   the grid's cells, the preview's canvases."
  (:require [cljfx.api :as fx])
  (:import [javafx.beans.value ChangeListener]
           [javafx.scene.layout Region]))

(defn- track-size!
  "Keep the local state's :width and :height at the region's size, in whole
   pixels."
  [^Region region swap-state]
  (let [listener (reify ChangeListener
                   (changed [_ _ _ _]
                     (swap-state assoc
                                 :width (Math/floor (.getWidth region))
                                 :height (Math/floor (.getHeight region)))))]
    (.addListener (.widthProperty region) listener)
    (.addListener (.heightProperty region) listener)))

(defn- sized
  [{:keys [state swap-state desc]}]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created #(track-size! % swap-state)
   :desc (merge desc state)})

(defn measured
  "desc, given :width and :height: the size of the region it describes, as
   its parent lays it out (none before the first layout). That size has to
   come from the parent (a grow constraint, a min size of its own), not from
   what desc puts in the region, or each would keep resizing the other."
  [{:keys [desc]}]
  {:fx/type fx/ext-state
   :initial-state {}
   :desc {:fx/type sized
          :desc desc}})
