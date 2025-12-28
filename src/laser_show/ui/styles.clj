(ns laser-show.ui.styles
  "Stylesheet helpers for the application.
   
   Provides:
   - resource-stylesheet: Helper to get a stylesheet URL from resources
   - inline-stylesheet: Helper to create inline CSS stylesheet
   - base-theme-css: Base theme CSS string
   
   Note: For hot-reloadable stylesheets, use the subs/stylesheet-urls subscription
   which combines static and dynamic styles from state.
   
   Usage for scenes with hot-reload support:
   {:fx/type :scene
    :stylesheets (fx/sub-ctx context subs/stylesheet-urls)
    :root {...}}
   
   For dialogs needing extra CSS:
   {:fx/type :scene
    :stylesheets (into (fx/sub-ctx context subs/stylesheet-urls)
                       [(styles/inline-stylesheet extra-css)])
    :root {...}}"
  (:require [clojure.java.io :as io]))

(defn resource-stylesheet
  "Get the URL string for a CSS file from resources.
   Path should be relative to resources folder.
   Example: (resource-stylesheet \"styles/tabs.css\")"
  [path]
  (str (.toExternalForm (io/resource path))))

(defn inline-stylesheet
  "Create an inline data: URL stylesheet from CSS string.
   Useful for small overrides or scene-specific styles."
  [css-string]
  (str "data:text/css,"
       (java.net.URLEncoder/encode css-string "UTF-8")))

(def base-theme-css
  "Base theme CSS applied to all scenes."
  ".root { -fx-base: #1E1E1E; -fx-background: #1E1E1E; }")

;; Note: all-stylesheets is kept for backwards compatibility with dialogs
;; that need to append extra CSS. For main scenes, use subs/stylesheet-urls.
(defn all-stylesheets
  "Returns a vector of all application stylesheets.
   
   DEPRECATED: Use subs/stylesheet-urls subscription for hot-reload support.
   This function is kept for backwards compatibility with dialogs that need
   to append extra CSS strings.
   
   Optionally accepts additional inline CSS strings to append."
  ([base-stylesheets]
   base-stylesheets)
  ([base-stylesheets & additional-css-strings]
   (into (vec base-stylesheets)
         (mapv inline-stylesheet additional-css-strings))))
