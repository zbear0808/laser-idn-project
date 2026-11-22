(ns laser-show.views.dialogs.common
  "Helpers the dialogs and other views share."
  (:import [javafx.application Platform]
           [javafx.beans.value ChangeListener]
           [javafx.scene Parent]))

(defn cell-label
  "A grid cell's name, as the grid's row letters and column numbers read:
   \"A1\" for [0 0], \"D8\" for col 7, row 3. A nil col or row counts as 0."
  [col row]
  (str (char (+ 65 (or row 0))) (inc (or col 0))))

(defn fit-window-to-content!
  "Keep a dialog's window the size of its content (an :on-created of
   fx/ext-on-instance-lifecycle around the scene's root; the stage sets no
   :width or :height). After any layout change the window is sized to the
   scene again when the content's preferred height isn't the window's, so
   the window is as big as the platform's fonts and title bar need. Reads
   only the node, never state."
  [^Parent root]
  (.addListener (.needsLayoutProperty root)
                (reify ChangeListener
                  (changed [_ _ _ needs?]
                    (when needs?
                      (Platform/runLater
                       (fn []
                         (when-let [scene (.getScene root)]
                           (when-let [window (.getWindow scene)]
                             (when (> (Math/abs (- (.prefHeight root (.getWidth scene)) (.getHeight scene))) 0.5)
                               (.sizeToScene window)))))))))))
