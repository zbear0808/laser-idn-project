(ns laser-show.views.components.addon-settings
  "Settings → Add-ons (plans/2026-11-19-output-drivers/02 § D3): the add-ons
   folder, what loaded from it at startup (Loaded, Failed or Skipped, with
   why, and each add-on's drivers), what Install add-on… copied in since
   (it loads at the next start), Install add-on… and Open add-ons folder.
   Add-ons load only at startup, so nothing here loads or unloads one."
  (:require [cljfx.api :as fx]
            [laser-show.css.core :as css]
            [laser-show.subs :as subs]))


;; Rows


(defn- state-color [state]
  (case state
    :loaded (css/green)
    :skipped (css/accent-warning)
    :pending (css/text-secondary)
    (css/red)))

(defn- row-label
  "A label of a row, with an id of its own."
  [id text style]
  {:fx/type :label
   :id id
   :text text
   :wrap-text true
   :style style})

(defn- addon-row
  "One add-on: a dot for its state, its name and version, Loaded / Failed /
   Skipped with why, its drivers and its file."
  [{:keys [id title state state-text drivers file]}]
  {:fx/type :h-box
   :id id
   :spacing 10
   :alignment :top-left
   :padding {:left 8 :right 8 :top 6 :bottom 6}
   :style (str "-fx-background-color: " (css/bg-elevated) "; "
               "-fx-background-radius: 4;")
   :children [{:fx/type :circle
               :radius 4
               :fill (state-color state)
               :translate-y 5}
              {:fx/type :v-box
               :spacing 2
               :h-box/hgrow :always
               :children (filterv some?
                                  [{:fx/type :h-box
                                    :spacing 8
                                    :alignment :center-left
                                    :children [(row-label (str id "-title") title
                                                          (str "-fx-text-fill: " (css/text-primary) "; -fx-font-weight: bold;"))
                                               {:fx/type :region :h-box/hgrow :always}
                                               (row-label (str id "-file") file
                                                          (str "-fx-text-fill: " (css/text-muted) "; -fx-font-size: 11;"))]}
                                   (row-label (str id "-state") state-text
                                              (str "-fx-text-fill: " (state-color state) ";"))
                                   (when drivers
                                     (row-label (str id "-drivers") (str "Drivers: " drivers)
                                                (str "-fx-text-fill: " (css/text-secondary) "; -fx-font-size: 11;")))])}]})


;; Section


(defn addon-settings-section
  "Add-ons section for the settings tab."
  [{:keys [fx/context]}]
  (let [dir (fx/sub-ctx context subs/addons-dir)
        rows (fx/sub-ctx context subs/addon-rows)
        pending (fx/sub-ctx context subs/pending-install-rows)
        muted (str "-fx-text-fill: " (css/text-muted) "; -fx-font-size: 11;")]
    {:fx/type :v-box
     :id "addons-section"
     :spacing 12
     :style (str "-fx-background-color: " (css/bg-primary) "; "
                 "-fx-padding: 16; "
                 "-fx-background-radius: 8; "
                 "-fx-border-color: " (css/border) "; "
                 "-fx-border-radius: 8; "
                 "-fx-border-width: 1;")
     :children
     [{:fx/type :h-box
       :spacing 8
       :alignment :center-left
       :children [{:fx/type :label
                   :text "Add-ons"
                   :style (str "-fx-font-size: 16; -fx-font-weight: bold; "
                               "-fx-text-fill: " (css/text-primary) ";")}
                  {:fx/type :region :h-box/hgrow :always}
                  {:fx/type :button
                   :id "addons-install-button"
                   :text "Install add-on…"
                   :style-class ["button-primary"]
                   :on-action {:event/type :addons/pick-install}}
                  {:fx/type :button
                   :id "addons-open-folder-button"
                   :text "Open add-ons folder"
                   :style-class ["button-secondary"]
                   :on-action {:event/type :addons/open-folder}}]}

      {:fx/type :v-box
       :spacing 2
       :children [{:fx/type :label
                   :text "Output drivers from add-on jars in this folder load when Laser Show starts."
                   :wrap-text true
                   :style (str "-fx-text-fill: " (css/text-secondary) ";")}
                  {:fx/type :label
                   :id "addons-folder-path"
                   :text (str dir)
                   :wrap-text true
                   :style (str "-fx-text-fill: " (css/text-primary) "; -fx-font-family: monospace;")}]}

      {:fx/type :v-box
       :spacing 4
       :children (if (or (seq rows) (seq pending))
                   (into (vec (map-indexed (fn [i row]
                                             (assoc row :fx/type addon-row :fx/key [:addon i] :id (str "addon-row-" i)))
                                           rows))
                         (map-indexed (fn [i row]
                                        (assoc row :fx/type addon-row :fx/key [:pending i]
                                               :id (str "addon-pending-row-" i) :state :pending))
                                      pending))
                   [{:fx/type :label
                     :id "addons-empty"
                     :text "No add-ons yet. Install one, or put its .jar in the folder and restart Laser Show."
                     :wrap-text true
                     :style (str "-fx-text-fill: " (css/text-muted) "; "
                                 "-fx-font-style: italic; "
                                 "-fx-padding: 8;")}])}

      {:fx/type :label
       :id "addons-remove-hint"
       :text "To remove an add-on, quit Laser Show and delete its file from the folder."
       :wrap-text true
       :style muted}]}))
