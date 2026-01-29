(ns laser-show.css.icons
  (:require [cljfx.css :as css]
            [laser-show.css.theme :as theme]))

(def styles
  (css/register ::styles
                {".icon"
                 {:-fx-fill (:text-primary theme/semantic-colors)
                  :-fx-font-family "\"FontAwesome\""}

                 ;; Common hover effect for icons inside buttons
                 ".button:hover .icon"
                 {:-fx-fill (:text-primary theme/semantic-colors) ; Keeping primary, change to interactive hover if needed or leave to button styling
                  }}))
