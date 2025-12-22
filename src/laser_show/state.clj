(ns laser-show.state
  "Central managed state for the Laser Show application.
   This namespace contains the single source of truth atom."
  (:require [laser-show.ui.layout :as layout]))

(def default-state
  "Initial application state."
  {:playing? false
   :current-animation nil
   :animation-start-time 0
   
   ;; Grid State (formerly in ui.grid)
   :grid {:cells {[0 0] {:preset-id :circle}
                  [1 0] {:preset-id :spinning-square}
                  [2 0] {:preset-id :triangle}
                  [3 0] {:preset-id :star}
                  [4 0] {:preset-id :spiral}
                  [5 0] {:preset-id :wave}
                  [6 0] {:preset-id :beam-fan}
                  [7 0] {:preset-id :rainbow-circle}}
          :active-cell nil    ; [col row] or nil
          :selected-cell nil  ; [col row] or nil
          :size [layout/default-grid-cols layout/default-grid-rows]}
   
   ;; IDN / Network State
   :idn {:connected? false
         :target nil
         :streaming-engine nil}
   
   ;; Logging
   :logging {:enabled? false
             :file nil
             :path "idn-packets.log"}
   
   ;; Effect Grid
   :effects {:active-effects {}  ; Map of [col row] -> effect-data
             }
   
   ;; UI References (Still need these for seesaw interop, but keep them separate)
   :ui {:main-frame nil
        :preview-panel nil
        :grid-panel nil
        :effects-panel nil
        :status-bar nil
        :toolbar nil}})

(defonce app-state
  ^{:doc "The Single Source of Truth."}
  (atom default-state))
