(ns laser-show-test-addon.sample.helper
  "Required by the sample driver on its first frame, not when it loads.")

(defn describe [frame]
  (str (count frame) " points"))
