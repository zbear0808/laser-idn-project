(ns laser-show.views.dialogs.rig-report
  "A show's rig matched onto this one (plans/2026-11-19-zones/09 §7), after
   File > Open. On a rig with no laser on: \"Set up your rig from
   friday.zip?\" (Q-ZR-3). On a rig that is set up: the report of what
   doesn't fit, with a fix for each.

   State: [:ui :dialogs :rig-report] {:open? :mode (:set-up or :report) :file
   :hardware :chains :pins}, what :file/project-loaded kept of the show's rig
   for as long as the show is open (New and Open reset it)."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.css.core :as css]
            [laser-show.routing.rig :as rig]
            [laser-show.state.rig-file :as rig-file]
            [laser-show.subs :as subs]
            [laser-show.views.dialogs.common :as common]))


;; The set-up question

(defn- set-up-question
  "rig-file/set-up-question for the open show's hardware on this rig."
  [context]
  (let [{:keys [hardware]} (fx/sub-ctx context subs/dialog-data :rig-report)]
    (rig-file/set-up-question {:projectors (fx/sub-val context :projectors)} hardware)))

(defn- possessive [n] (str n "'s"))

(defn unreadable-text
  "\"Audience's safety zone in friday.zip can't be read: it stays dark until
   you set one.\", for one laser or more."
  [names file]
  (if (= 1 (count names))
    (str (possessive (first names)) " safety zone in " file " can't be read: it stays dark until you set one.")
    (str (str/join " and " [(str/join ", " (map possessive (butlast names))) (possessive (last names))])
         " safety zones in " file " can't be read: they stay dark until you set them.")))

(defn- text-line
  ([id text] (text-line id text "label-secondary"))
  ([id text style-class]
   {:fx/type :label :id id :text text :wrap-text true :min-height :use-pref-size :style-class [style-class]}))

(defn- set-up-content
  [{:keys [fx/context]}]
  (let [{:keys [file]} (fx/sub-ctx context subs/dialog-data :rig-report)
        {:keys [lasers matched-by-ip unreadable]} (fx/sub-ctx context set-up-question)]
    {:fx/type :v-box
     :id "rig-report-dialog"
     :spacing 10
     :padding 20
     :pref-width 520
     :style-class ["container-primary"]
     :children
     (filterv some?
              [(text-line "rig-setup-title" (str "Set up your rig from " file "?") "header-primary")
               (text-line "rig-setup-intro" (str "No laser on this rig is turned on yet. " file " was made with " (count lasers) ":"))
               (assoc (text-line "rig-setup-lasers" (str/join " · " lasers) "label-bold") :style "-fx-padding: 0 0 0 16;")
               (text-line "rig-setup-what" "Their order, groups, safety zones and calibration become your rig.")
               (text-line "rig-setup-addresses" "Lasers found on this network keep their addresses.")
               (when (seq matched-by-ip)
                 (text-line "rig-setup-by-ip" (str "Matched by IP only, check them: "
                                                   (str/join ", " (map #(str (:name %) " (" (:host %) ")") matched-by-ip)))))
               (when (seq unreadable)
                 (assoc (text-line "rig-setup-unreadable" (unreadable-text unreadable file))
                        :style (str "-fx-text-fill: " (css/orange) ";")))
               {:fx/type :h-box
                :alignment :center-right
                :spacing 8
                :children [{:fx/type :button
                            :id "rig-setup-not-now"
                            :text "Not now"
                            :cancel-button true
                            :style-class ["button-secondary"]
                            :on-action {:event/type :ui/close-dialog :dialog-id :rig-report}}
                           {:fx/type :button
                            :id "rig-setup-button"
                            :text "Set up rig"
                            :default-button true
                            :style-class ["button-primary"]
                            :on-action {:event/type :rig/set-up-from-show}}]}])}))


;; The report

(defn- report-inputs
  "What match-report reads of the state."
  [state]
  {:projectors (:projectors state)
   :virtual-projectors (:virtual-projectors state)
   :zone-groups (:zone-groups state)
   :chains (select-keys (:chains state) [:cue-chains :projector-effects])
   :rig-file (select-keys (:rig-file state) [:group-copy])
   :project (select-keys (:project state) [:current-file])})

(defn report
  "rig-file/match-report for the show that is open, or nil before one was.
   It reads the effect cells' patterns as the Open handler does
   (subs/effect-ref-inputs), so the dialog and its link show the same
   rows."
  [context]
  (let [{:keys [file hardware chains]} (fx/sub-ctx context subs/dialog-data :rig-report)]
    (when file
      (rig-file/match-report (assoc-in (fx/sub-val context report-inputs) [:chains :effect-chains]
                                       (fx/sub-ctx context subs/effect-ref-inputs))
                             hardware chains))))

(defn warning-count
  "How many ⚠ rows the open show's report has."
  [context]
  (rig-file/warning-count (fx/sub-ctx context report)))

(defn- lasers-in-order
  "{:id :name} of this rig's lasers, left to right."
  [state]
  (mapv (fn [id] {:id id :name (rig/projector-name state id)})
        (rig/effective-order (get-in state [:rig :order]) (:projectors state))))

(defn- id-text [k] (if (keyword? k) (name k) (str k)))

(defn- count-text [n one many] (str n " " (if (= 1 n) one many)))

(defn- owners-text
  "\"2 cues\", \"1 effect cell\", \"2 cues and 1 effect cell\": a row's owners."
  [cues effect-cells]
  (str/join " and " (cond-> []
                      (pos? cues) (conj (count-text cues "cue" "cues"))
                      (pos? effect-cells) (conj (count-text effect-cells "effect cell" "effect cells")))))

(defn- names-text
  "\"Beams\", \"Beams and Crowd 2\", \"A, B and C\"."
  [names]
  (if (< (count names) 2)
    (str/join names)
    (str (str/join ", " (butlast names)) " and " (last names))))

(defn- button [id text event]
  {:fx/type :button :id id :text text :min-width :use-pref-size :style-class ["button-secondary"] :on-action event})

(defn- report-row
  "A ⚠ or ℹ line, and its buttons below it."
  [id mark text buttons]
  {:fx/type :v-box
   :spacing 4
   :children (cond-> [{:fx/type :label
                       :id id
                       :text (str mark " " text)
                       :wrap-text true
                       :min-height :use-pref-size
                       :style (if (= "⚠" mark) (str "-fx-text-fill: " (css/orange) ";") "-fx-text-fill: #C8C8C8;")}]
               (seq buttons) (conj {:fx/type :h-box :spacing 8 :padding {:left 18} :children buttons}))})

(defn- group-row [{:keys [group-id cues effect-cells same-name] group-name :name}]
  (let [g (id-text group-id)]
    (report-row (str "rig-report-group-" g) "⚠"
                (str group-name
                     (if effect-cells
                       (str " (referenced by " (owners-text cues effect-cells) ")")
                       (str " (used by " (count-text cues "cue" "cues") ")"))
                     " has no laser on this rig.")
                (cond-> []
                  same-name (conj (button (str "rig-report-use-group-" g)
                                          (str "Use this rig's \"" (:name same-name) "\" (" (count-text (:lasers same-name) "laser" "lasers") ")")
                                          {:event/type :rig/adopt-group :group-id group-id :from (:group-id same-name)}))
                  true (conj (button (str "rig-report-choose-" g) "Choose lasers…"
                                     {:event/type :rig/choose-lasers :group-id group-id}))))))

(defn- pin-row
  "A laser that isn't here: the cues pinned to it, or, when an effect cell
   names it too, the cues and effect cells that reference it (a pattern's
   reference is no cue playing there); Pin them to… and Send them to All
   re-point every reference."
  [lasers {:keys [ref cues effect-cells] laser-name :name}]
  (let [l (id-text (rig/laser-of ref))
        them (if (= 1 (+ cues (or effect-cells 0))) "it" "them")]
    (report-row (str "rig-report-pin-" l) "⚠"
                (if effect-cells
                  (str laser-name ", which isn't on this rig, is referenced by " (owners-text cues effect-cells) ".")
                  (str (count-text cues "cue" "cues") (if (= 1 cues) " is" " are") " pinned to " laser-name ", which isn't on this rig."))
                [{:fx/type :menu-button
                  :id (str "rig-report-pin-to-" l)
                  :text (str "Pin " them " to…")
                  :disable (empty? lasers)
                  :style-class ["button-secondary"]
                  :items (mapv (fn [{:keys [id] n :name}]
                                 {:fx/type :menu-item
                                  :id (str "rig-report-pin-" l "-to-" (id-text id))
                                  :text n
                                  :on-action {:event/type :rig/repin :from ref :to (rig/laser-ref id)}})
                               lasers)}
                 (button (str "rig-report-send-to-all-" l) (str "Send " them " to All")
                         {:event/type :rig/repin :from ref :to :all})])))

(defn- differs-row [{:keys [projector-id what unreadable?] laser-name :name}]
  (let [p (id-text projector-id)]
    (if unreadable?
      (report-row (str "rig-report-differs-" p) "ℹ" (str "The show's safety zone for " laser-name " can't be read.") [])
      (let [both? (= #{:safety :calibration} what)
            thing (cond both? "safety and calibration" (contains? what :safety) "safety zone" :else "calibration")]
        (report-row (str "rig-report-differs-" p) "ℹ"
                    (str "The show has its own " thing " for " laser-name ". This rig's " (if both? "are" "is") " in use.")
                    [(button (str "rig-report-use-show-" p) (str "Use the show's for " laser-name "…")
                             {:event/type :rig/request-use-show-setup :projector-id projector-id})])))))

(defn- report-rows
  [{:keys [file show-lasers rig-lasers groups-without-lasers missing-pins rig-groups-not-in-show
           setup-differs matched-by-ip]}
   lasers]
  (concat
   (map group-row groups-without-lasers)
   (map #(pin-row lasers %) missing-pins)
   (when (not= show-lasers rig-lasers)
     [(report-row "rig-report-counts" "ℹ"
                  (str file " was made on " (count-text show-lasers "laser" "lasers") "; this rig has " rig-lasers
                       ". Left, Right, Odd and Even follow this rig.")
                  [])])
   (when (seq rig-groups-not-in-show)
     (let [one? (= 1 (count rig-groups-not-in-show))]
       [(report-row "rig-report-rig-groups" "ℹ"
                    (str "This rig's " (if one? "group " "groups ") (names-text (map :name rig-groups-not-in-show))
                         (if one? " isn't" " aren't") " in this show.")
                    [(button "rig-report-add-groups" (if one? "Add it to this show" "Add them to this show")
                             {:event/type :rig/add-groups-to-show :group-ids (mapv :group-id rig-groups-not-in-show)})])]))
   (map differs-row setup-differs)
   (when (seq matched-by-ip)
     [(report-row "rig-report-by-ip" "ℹ"
                  (str "Matched by IP only, check them: " (str/join ", " (map #(str (:name %) " (" (:host %) ")") matched-by-ip)))
                  [])])))

(defn- report-content
  [{:keys [fx/context]}]
  (let [{:keys [file new-lasers] :as r} (fx/sub-ctx context report)
        rows (report-rows r (fx/sub-val context lasers-in-order))
        file (or (:file (fx/sub-ctx context subs/dialog-data :rig-report)) file)]
    {:fx/type :v-box
     :id "rig-report-dialog"
     :spacing 12
     :padding 20
     :pref-width 600
     :style-class ["container-primary"]
     :children [(text-line "rig-report-title" (str file " on this rig") "header-primary")
                (text-line "rig-report-kept" "Your rig's safety zones and calibration are kept.")
                {:fx/type :scroll-pane
                 :fit-to-width true
                 :max-height 420
                 :style "-fx-background-color: transparent; -fx-background: transparent;"
                 :content {:fx/type :v-box
                           :spacing 10
                           :children (if (seq rows)
                                       (vec rows)
                                       [(text-line "rig-report-fits" (str "Everything in " file " fits this rig."))])}}
                {:fx/type :h-box
                 :alignment :center-right
                 :spacing 8
                 :children (cond-> []
                             (seq new-lasers) (conj (button "rig-report-import"
                                                            (str "Import " (count-text (count new-lasers) "new laser" "new lasers") "…")
                                                            {:event/type :rig/import-new-lasers}))
                             true (conj {:fx/type :button
                                         :id "rig-report-close"
                                         :text "Close"
                                         :cancel-button true
                                         :default-button true
                                         :style-class ["button-primary"]
                                         :on-action {:event/type :ui/close-dialog :dialog-id :rig-report}}))}]}))


(defn rig-report-dialog
  "The set-up question or the report, in its own window. Application-modal,
   so nothing else can be clicked while it is open."
  [{:keys [fx/context]}]
  (let [{:keys [mode file]} (fx/sub-ctx context subs/dialog-data :rig-report)
        set-up? (= :set-up mode)]
    {:fx/type common/dialog-stage
     :dialog-id :rig-report
     :title (if set-up? "Set up rig" (str file " on this rig"))
     :modality :application-modal
     :resizable false
     :content {:fx/type (if set-up? set-up-content report-content)}}))
