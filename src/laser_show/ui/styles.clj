(ns laser-show.ui.styles
  "Centralized stylesheet management for the application.
   
   Provides:
   - all-stylesheets: Vector of all app CSS stylesheets for use in scenes
   - resource-stylesheet: Helper to get a stylesheet URL from resources
   - inline-stylesheet: Helper to create inline CSS stylesheet
   
   Usage in any scene:
   {:fx/type :scene
    :stylesheets (styles/all-stylesheets)
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

(defn all-stylesheets
  "Returns a vector of all application stylesheets.
   Use this in any scene to get consistent styling.
   
   Optionally accepts additional inline CSS strings to append."
  ([]
   [(inline-stylesheet base-theme-css)
    (resource-stylesheet "styles/tabs.css")])
  ([& additional-css-strings]
   (into (all-stylesheets)
         (mapv inline-stylesheet additional-css-strings))))
