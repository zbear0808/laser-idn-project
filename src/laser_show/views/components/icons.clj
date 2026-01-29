(ns laser-show.views.components.icons
  "FontAwesome icon components using ControlsFX.
   
   Provides cljfx-compatible icon components that render as Text nodes (via ControlsFX Glyph),
   allowing CSS styling via -fx-text-fill (or -fx-fill for Shapes) for easy recoloring."
  (:require
   [cljfx.api :as fx]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [laser-show.common.util :as u])
  (:import
   [org.controlsfx.glyphfont FontAwesome$Glyph GlyphFontRegistry]))

(def ^:private font (GlyphFontRegistry/font "FontAwesome"))

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
               (let [glyph-name (str/upper-case (name icon))
                     glyph-node (.create font glyph-name)]
                 (.setFontSize glyph-node size)
                 glyph-node)
               (catch Exception e
                 (log/error "Error creating icon:" icon (u/exception->map e))
                 (javafx.scene.text.Text. "?"))))
   :props {:style-class (cond-> ["icon"]
                          style-class (conj style-class))
           :style {:-fx-font-size size}}})