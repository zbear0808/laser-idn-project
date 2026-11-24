(ns laser-show.views.components.list-dnd-test
  "A list drop decides and dispatches from one read of app state, taken
   when the drop comes (reactivity plan 32): a row's self-drop check and
   the :list/perform-drop it dispatches use the same snapshot, and so does
   a drop on the list's background. Each test serves one snapshot per
   read, a different one each time."
  (:require [cljfx.api :as fx]
            [clojure.test :refer [deftest is testing]]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.views.components.list-dnd :as dnd])
  (:import [com.sun.javafx.scene.input DragboardHelper]
           [com.sun.javafx.tk TKClipboard]
           [javafx.scene.input ClipboardContent DragEvent TransferMode]
           [javafx.scene.layout Pane]
           [javafx.util Pair]))

(def ^:private component-id [:cue-chain 0 0])

(def ^:private address
  {:component-id component-id
   :items-path [:chains :cue-chains [0 0] :items]
   :on-change-event :chain/set-items
   :on-change-params {:domain :cue-chains :entity-key [0 0]}})

(defn- dragging [ids] {:list-ui {component-id {:dragging-ids ids}}})

(defn- drop-event
  "A DRAG_DROPPED at node with a list drag's string on its dragboard. FX
   thread only."
  [node]
  (let [store (atom (into {} (doto (ClipboardContent.) (.putString "item"))))
        db (DragboardHelper/createDragboard
            (reify TKClipboard
              (getContentTypes [_] (set (keys @store)))
              (putContent [_ pairs]
                (doseq [^Pair p pairs] (swap! store assoc (.getKey p) (.getValue p)))
                true)
              (getContent [_ fmt] (get @store fmt))
              (hasContent [_ fmt] (contains? @store fmt))
              (getTransferModes [_] #{TransferMode/MOVE})
              (setDragView [_ _])
              (setDragViewOffsetX [_ _])
              (setDragViewOffsetY [_ _])
              (getDragView [_] nil)
              (getDragViewOffsetX [_] 0.0)
              (getDragViewOffsetY [_] 0.0)))]
    (DragEvent. DragEvent/DRAG_DROPPED db 5.0 5.0 5.0 5.0 TransferMode/MOVE nil node nil)))

(defn- drop-with-reads
  "Drop on node, on the FX thread, while app state reads give snapshots, one
   per read (the last again once they run out). The :dragging-ids of each
   :list/perform-drop it dispatched, and whether it completed."
  [^Pane node snapshots]
  (let [left (atom snapshots)
        dispatched (atom [])
        event (atom nil)]
    (with-redefs [state/get-raw-state (fn [] (let [[s & more] @left] (when more (reset! left more)) s))
                  events/dispatch! #(swap! dispatched conj %)]
      @(fx/on-fx-thread
        (let [e (drop-event node)]
          (reset! event e)
          (.handle (.getOnDragDropped node) e))))
    {:drops (keep #(when (= :list/perform-drop (:event/type %)) (vec (:dragging-ids %))) @dispatched)
     :completed? (.isDropCompleted ^DragEvent @event)}))

(deftest a-row-drop-reads-the-drag-once-test
  (let [target (random-uuid)
        dragged (random-uuid)
        row (doto (Pane.) (dnd/setup-drag-target! target false (constantly address)))]
    (testing "the drag as the drop read it: dispatched, even if app state changes after"
      (is (= {:drops [[dragged]] :completed? true}
             (drop-with-reads row [(dragging #{dragged}) (dragging #{target})]))))
    (testing "the row itself in the drag: no drop, whatever a later read says"
      (is (= {:drops [] :completed? false}
             (drop-with-reads row [(dragging #{target}) (dragging #{dragged})]))))))

(deftest a-background-drop-reads-the-drag-once-test
  (let [dragged (random-uuid)
        background (doto (Pane.) (dnd/setup-container-drop-target! (constantly address)))]
    (is (= {:drops [[dragged]] :completed? true}
           (drop-with-reads background [(dragging #{dragged}) (dragging #{(random-uuid)})])))))
