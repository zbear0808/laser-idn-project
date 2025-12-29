(ns laser-show.ui.styles
  "Stylesheet helpers for the application.
   
   MIGRATION NOTE:
   This namespace is being deprecated in favor of laser-show.css.core.
   Use css/all-stylesheet-urls for stylesheets, and access colors via
   the theme map in laser-show.css.theme.
   
   For new code, use:
   {:fx/type :scene
    :stylesheets (css/all-stylesheet-urls)
    :root {...}}
   
   For color constants:
   (require '[laser-show.css.core :as css])
   (css/bg-dark) => \"#252525\""
  (:require [clojure.java.io :as io]
            [laser-show.css.core :as css]))

;; ============================================================================
;; Re-exports from css.core (for migration convenience)
;; ============================================================================

(def all-stylesheet-urls
  "Returns all CSS stylesheet URLs for the application.
   Alias for css/all-stylesheet-urls."
  css/all-stylesheet-urls)

(def dialog-stylesheet-urls
  "Returns CSS stylesheet URLs appropriate for dialogs.
   Alias for css/dialog-stylesheet-urls."
  css/dialog-stylesheet-urls)

;; ============================================================================
;; Legacy helpers (kept for backwards compatibility)
;; ============================================================================

(defn resource-stylesheet
  "Get the URL string for a CSS file from resources.
   Path should be relative to resources folder.
   
   DEPRECATED: Static CSS files in resources are being migrated to
   cljfx/css definitions in the laser-show.css namespace."
  [path]
  (str (.toExternalForm (io/resource path))))

(defn inline-stylesheet
  "Create an inline data: URL stylesheet from CSS string.
   
   DEPRECATED: For truly dynamic styles that depend on runtime values,
   use inline :style props. For static styles, define them in
   laser-show.css.* namespaces using cljfx/css."
  [css-string]
  (str "data:text/css,"
       (java.net.URLEncoder/encode css-string "UTF-8")))

;; DEPRECATED - use css/all-stylesheet-urls instead
(def base-theme-css
  "DEPRECATED: Use css/all-stylesheet-urls which includes theme.
   Base theme CSS applied to all scenes."
  ".root { -fx-base: #1E1E1E; -fx-background: #1E1E1E; }")

(defn all-stylesheets
  "DEPRECATED: Use css/all-stylesheet-urls directly.
   
   This function is kept temporarily for backwards compatibility.
   Optionally accepts additional inline CSS strings to append."
  ([base-stylesheets]
   base-stylesheets)
  ([base-stylesheets & additional-css-strings]
   (into (vec base-stylesheets)
         (mapv inline-stylesheet additional-css-strings))))
