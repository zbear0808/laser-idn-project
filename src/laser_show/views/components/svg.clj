(ns laser-show.views.components.svg
  "Minimal SVG path component for cljfx.
   
   Renders SVG path(s), either from:
   - A raw SVG path data string via :path
   - An SVG file path via :src (extracts all <path> elements)
   
   Example usage:
   
   ;; From raw path data
   {:fx/type svg/icon
    :path \"M10 10 L20 20 Z\"
    :size 24
    :style-class \"my-icon\"}
   
   ;; From SVG file (renders all paths)
   {:fx/type svg/icon
    :src \"resources/icons/my-icon.svg\"
    :size 16
    :style-class [\"icon\" \"toolbar-icon\"]}
   
   ;; With custom colors
   {:fx/type svg/icon
    :src \"resources/icons/my-icon.svg\"
    :size 24
    :fill :white
    :stroke :black}"
  (:require [clojure.java.io :as io]
            [clojure.xml :as xml]
            [laser-show.common.util :as u]))

(set! *warn-on-reflection* true)


;; SVG Parsing


(defn- collect-path-elements
  "Recursively collect all <path> elements in parsed XML."
  [element]
  (cond
    (= :path (:tag element))
    [element]
    
    (seq (:content element))
    (into [] (mapcat collect-path-elements) (:content element))
    
    :else []))

(defn- parse-dimension
  "Parse an SVG dimension value (e.g. '800px', '24', '100%') to double.
   Returns nil for percentage or invalid values."
  [dim-str]
  (when dim-str
    (when-let [match (re-find #"^(-?[\d.]+)" dim-str)]
      (try
        (Double/parseDouble (if (string? match) match (first match)))
        (catch NumberFormatException _ nil)))))

(defn- parse-viewbox
  "Parse SVG viewBox attribute string into [x y width height]."
  [viewbox-str]
  (when viewbox-str
    (let [parts (map #(Double/parseDouble %) (re-seq #"-?[\d.]+" viewbox-str))]
      (when (= 4 (count parts))
        (vec parts)))))

(defn- parse-svg-file
  "Parse an SVG file and extract all path d attributes and viewBox."
  [source]
  (let [file (io/file source)
        ;; Try file first, then classpath resource
        input-stream (if (.exists file)
                       (io/input-stream file)
                       (when-let [resource (io/resource source)]
                         (io/input-stream resource)))
        _ (when-not input-stream
            (throw (ex-info (str "SVG file not found: " source) {:source source})))
        xml-data (with-open [is input-stream]
                   (xml/parse is))
        path-elems (collect-path-elements xml-data)
        viewbox (parse-viewbox (get-in xml-data [:attrs :viewBox]))]
    (when (empty? path-elems)
      (throw (ex-info (str "No <path> element found in SVG: " source) {:source source})))
    {:paths (mapv #(get-in % [:attrs :d]) path-elems)
     :viewbox viewbox
     :width (parse-dimension (get-in xml-data [:attrs :width]))
     :height (parse-dimension (get-in xml-data [:attrs :height]))}))


;; SVG Cache


(def ^:private svg-cache (atom {}))

(defn- load-svg
  "Load and cache parsed SVG data from a file."
  [source]
  (if-let [cached (@svg-cache source)]
    cached
    (let [parsed (parse-svg-file source)]
      (swap! svg-cache assoc source parsed)
      parsed)))

(defn clear-cache!
  "Clear the SVG cache. Useful during development."
  []
  (reset! svg-cache {}))


;; cljfx Component


(defn icon
  "Render SVG path(s) as a cljfx component.
   
   Props:
   - :path        - Raw SVG path data string (e.g. \"M10 10 L20 20 Z\")
   - :src         - Path to SVG file (extracts all <path> elements)
   - :size        - Target size in pixels (scales to fit, default: nil = no scaling)
   - :viewbox     - Original viewBox [x y w h] for raw :path data (needed for scaling)
   - :style-class - CSS class(es) for styling (string or vector of strings)
   - :fill        - Fill color for the SVG paths (keyword, hex string, or Color object)
   - :stroke      - Stroke color for the SVG paths (keyword, hex string, or Color object)
   
   One of :path or :src is required.
   When using :size with :path, you should also provide :viewbox for correct scaling."
  [{:keys [path src size viewbox style-class fill stroke]}]
  (let [svg-data (cond
                   src (load-svg src)
                   path {:paths [path] :viewbox viewbox}
                   :else (throw (ex-info "Must provide :path or :src" {})))
        paths (:paths svg-data)
        [_ _ vw vh] (or (:viewbox svg-data) [0 0 24 24])
        scale (when size
                (/ (double size) (max vw vh)))
        children (mapv (fn [p]
                         (u/assoc-some {:fx/type :svg-path :content p}
                                       :fill fill
                                       :stroke stroke))
                       paths)
        base-props {:fx/type :group
                    :scale-x (or scale 1.0)
                    :scale-y (or scale 1.0)
                    :children children}]
    (u/assoc-some base-props
                  :style-class style-class)))
