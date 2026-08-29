(ns laser-show.experiments.dnd-repro
  (:require [cljfx.api :as fx]
            [clojure.pprint :as pprint])
  (:import [javafx.scene.input TransferMode ClipboardContent DragEvent MouseEvent]
           [javafx.application Platform]
           [javafx.stage Stage]))


;; State


(defonce state (atom {:source-items ["Item A" "Item B" "Item C" "Item D"]
                      :target-items ["Target 1" "Target 2" "Target 3"]
                      :dialog-open? false}))

(defn log [msg & args]
  (let [thread-name (.getName (Thread/currentThread))]
    (println (format "[%s] %s %s" thread-name msg (if (seq args) (apply str args) "")))))


;; Components


(defn draggable-item [{:keys [text color]}]
  {:fx/type :label
   :text text
   :style {:-fx-background-color color
           :-fx-padding 10
           :-fx-text-fill "white"
           :-fx-font-weight "bold"}
   :on-drag-detected (fn [^MouseEvent e]
                       (log "DRAG DETECTED: " text)
                       (let [db (.startDragAndDrop (.getSource e) (into-array TransferMode [TransferMode/MOVE]))
                             content (ClipboardContent.)]
                         (.putString content text)
                         (.setContent db content)
                         (.consume e)))
   :on-drag-done (fn [^DragEvent e]
                   (log "DRAG DONE: " text " accepted: " (.isAccepted e) " dropCompleted: " (.isDropCompleted e))
                   (.consume e))})

(defn drop-target [{:keys [items on-drop bg-color title]}]
  {:fx/type :v-box
   :spacing 10
   :style {:-fx-background-color bg-color
           :-fx-padding 20
           :-fx-min-width 200
           :-fx-min-height 300}
   :on-drag-over (fn [^DragEvent e]
                   (when (.hasString (.getDragboard e))
                     (.acceptTransferModes e (into-array TransferMode [TransferMode/MOVE])))
                   (.consume e))
   :on-drag-dropped (fn [^DragEvent e]
                      (log "DROP RECEIVED in " title)
                      (let [db (.getDragboard e)]
                        (if (.hasString db)
                          (let [content (.getString db)]
                            (log "  Content: " content)
                            (on-drop content)
                            (.setDropCompleted e true)
                            (log "  setDropCompleted(true) called"))
                          (do
                            (log "  No string content")
                            (.setDropCompleted e false))))
                      (.consume e))
   :children (into [{:fx/type :label
                     :text (str title " (" (count items) ")")
                     :style {:-fx-font-size 16 :-fx-font-weight "bold"}}]
                   (map (fn [item]
                          {:fx/type draggable-item
                           :text item
                           :color "#4CAF50"})
                        items))})


;; Views


(defn dnd-view [{:keys [source-items target-items title bg-color]}]
  {:fx/type :h-box
   :spacing 20
   :padding 20
   :style {:-fx-background-color bg-color}
   :children [{:fx/type :v-box
               :spacing 10
               :children (into [{:fx/type :label :text (str title " Source")
                                 :style {:-fx-font-size 18}}]
                               (map (fn [item]
                                      {:fx/type draggable-item
                                       :text item
                                       :color "#2196F3"})
                                    source-items))}
              {:fx/type drop-target
               :title (str title " Target")
               :bg-color "#e0e0e0"
               :items target-items
               :on-drop (fn [item]
                          (log "State update triggered for " title " with item " item)
                          (swap! state (fn [s]
                                         (let [items (:target-items s)]
                                           (if (some #{item} items)
                                             (do
                                               (log "  Item exists in target, reordering (remove+add)")
                                               (assoc s :target-items (conj (vec (remove #{item} items)) item)))
                                             (do
                                               (log "  Item new to target, adding")
                                               (update s :target-items conj (str item " (in " title ")"))))))))}]})

(defn root-view [{:keys [source-items target-items dialog-open?]}]
  {:fx/type :stage
   :showing true
   :title "DnD Repro - Main Window"
   :width 800
   :height 600
   :scene {:fx/type :scene
           :root {:fx/type :v-box
                  :children [{:fx/type :button
                              :text "Open Dialog"
                              :on-action (fn [_] (swap! state assoc :dialog-open? true))}
                             {:fx/type dnd-view
                              :title "Main Window"
                              :bg-color "#ffffff"
                              :source-items source-items
                              :target-items target-items}]}}})

(defn dialog-view [{:keys [source-items target-items]}]
  {:fx/type :stage
   :showing true
   :title "DnD Repro - Dialog"
   :width 600
   :height 400
   :on-close-request (fn [_] (swap! state assoc :dialog-open? false))
   :scene {:fx/type :scene
           :root {:fx/type dnd-view
                  :title "Dialog"
                  :bg-color "#f0f8ff"
                  :source-items source-items
                  :target-items target-items}}})

(defn app-view [current-state]
  {:fx/type fx/ext-many
   :desc (filterv some?
                  [{:fx/type root-view
                    :source-items (:source-items current-state)
                    :target-items (:target-items current-state)
                    :dialog-open? (:dialog-open? current-state)}
                   (when (:dialog-open? current-state)
                     {:fx/type dialog-view
                      :source-items (:source-items current-state)
                      :target-items (:target-items current-state)})])})


;; Entry Point


(def renderer
  (fx/create-renderer
   :middleware (fx/wrap-map-desc assoc :fx/type app-view)
   :opts {:fx.opt/map-event-handler (fn [e] e)}))

(defn -main []
  (fx/mount-renderer state renderer))