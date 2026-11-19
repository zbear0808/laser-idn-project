(ns laser-show.views.dialogs.common
  "Helpers the dialogs and other views share.")

(defn cell-label
  "A grid cell's name, as the grid's row letters and column numbers read:
   \"A1\" for [0 0], \"D8\" for col 7, row 3. A nil col or row counts as 0."
  [col row]
  (str (char (+ 65 (or row 0))) (inc (or col 0))))
