(ns laser-show.views.components.icons
  "FontAwesome icon components using ControlsFX and clj-font-awesome.
   
   Provides cljfx-compatible icon components that render as Text nodes (via ControlsFX Glyph),
   allowing CSS styling via -fx-text-fill (or -fx-fill for Shapes) for easy recoloring.
   
   Also provides fa-icon for FontAwesome 7 icons via clj-font-awesome library."
  (:require
   [cljfx.api :as fx]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [laser-show.common.util :as u]
   [clj-font-awesome.core :as fa])
  (:import
   [org.controlsfx.glyphfont FontAwesome$Glyph GlyphFontRegistry]))

(def ^:private font (GlyphFontRegistry/font "FontAwesome"))

;; FontAwesome 7 icon using clj-font-awesome library
(defn fa-icon
  "Renders a FontAwesome 7 icon using clj-font-awesome.
   
   Props:
   - :name        (required) Keyword icon name (e.g., :gear, :heart, :play)
   - :style       :solid (default), :regular, or :brands
   - :size        Font size in pixels (default: 16)
   - :color       Text fill color
   - :style-class Additional CSS class(es)"
  [{:keys [name style size color style-class] :or {style :solid size 16}}]
  (cond-> {:fx/type fa/icon
           :name name
           :style style
           :size size}
    color       (assoc :color color)
    style-class (assoc :style-class style-class)))

;; Original ControlsFX-based icon (FontAwesome 4)
(defn icon
  "Renders a FontAwesome icon.
   
   Props:
   - :icon        (required) Keyword representing the icon (e.g., :play, :stop, :gear)
   - :size        (optional) Font size in pixels (default: 16)
   - :style-class (optional) Extra CSS style class"
  [{:keys [icon size style-class] :or {size 8}}]
  {:fx/type fx/ext-instance-factory
   :create (fn []
             (try
               (let [glyph-name (-> icon 
                                    (name)
                                    (str/replace "-" "_")
                                    (str/upper-case))
                     glyph-node (.create font glyph-name)]
                 (.setFontSize glyph-node size)
                 glyph-node)
               (catch Exception e
                 (log/error "Error creating icon:" icon (u/exception->map e))
                 (javafx.scene.text.Text. "?"))))
   :props {:style-class (cond-> ["icon"]
                          style-class (conj style-class))
           :style {:-fx-font-size size}}})