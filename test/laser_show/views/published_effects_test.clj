(ns laser-show.views.published-effects-test
  "The editors show an effect's definition as :registries/published last put
   it in app state (reactivity plan 17, follow-up R1). They read the effect
   registry itself while rendering, which no subscription sees: an open
   editor kept a re-registered effect's old name, parameters and hints until
   something else made it render. Mounted with views.harness, on a context
   whose state has the registries published, as app/start! publishes them."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.animation.effects :as effects]
            ;; The effects the fixtures use, registered as the app's frame
            ;; service registers them
            [laser-show.animation.effects.calibration]
            [laser-show.animation.effects.color]
            [laser-show.animation.effects.intensity]
            [laser-show.animation.effects.shape]
            [laser-show.animation.effects.zone]
            [laser-show.animation.registries :as registries]
            [laser-show.animation.safety :as safety]
            [laser-show.events.handlers :as handlers]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.state.domains :as domains]
            [laser-show.timeline.model :as model]
            [laser-show.views.dialogs.cue-chain-editor :as cue-editor]
            [laser-show.views.dialogs.effect-chain-editor :as effect-editor]
            [laser-show.views.dialogs.timeline-cue-editor :as timeline-cue-editor]
            [laser-show.views.dialogs.zone-pattern-editor :as zone-pattern-editor]
            [laser-show.views.harness :as h]
            [laser-show.views.tabs.projectors :as projectors])
  (:import [javafx.scene Node Parent]
           [javafx.scene.control Labeled MenuButton MenuItem ScrollPane SplitPane]))


(defn- published
  "s after :registries/published, the registries as they are now."
  [s]
  (:state (handlers/handle-event (assoc (registries/published-event) :state s))))

(defn- nodes
  "node and every node under it, with what a skin would put under a scroll
   pane, a split pane and a label: mounted without a scene, they have none."
  [^Node node]
  (cons node
        (concat (when (instance? ScrollPane node) (some-> ^ScrollPane node .getContent nodes))
                (when (instance? SplitPane node) (mapcat nodes (.getItems ^SplitPane node)))
                (when (instance? Labeled node) (some-> ^Labeled node .getGraphic nodes))
                (when (instance? Parent node) (mapcat nodes (.getChildrenUnmodifiable ^Parent node))))))

(defn- shown
  "What root shows: {:texts every label's and button's text, :rows its list
   rows' names (.chain-item-name), :by-id {id text} of its labels and
   buttons with an id, a menu button's its items' texts}."
  [root]
  (let [ns (nodes root)
        text #(when (instance? Labeled %) (.getText ^Labeled %))]
    {:texts (into #{} (keep text) ns)
     :rows (into #{} (comp (filter #(.contains (.getStyleClass ^Node %) "chain-item-name")) (keep text)) ns)
     :by-id (into {} (keep (fn [^Node n]
                             (when-let [id (.getId n)]
                               (cond
                                 (instance? MenuButton n) [id (mapv #(.getText ^MenuItem %) (.getItems ^MenuButton n))]
                                 (instance? Labeled n) [id (.getText ^Labeled n)]))))
                  ns)}))

(defn- before-and-after
  "What desc shows mounted on state, the registries published, then after
   effect-id is re-registered named new-name (its id and parameters the
   same) and the registries published again. The effect as it was after."
  [state desc effect-id new-name]
  (let [registered (effects/get-effect effect-id)]
    (try
      (h/each-render (published state) h/lru-512 desc
                     [(fn [s]
                        (effects/register-effect! (assoc registered :name new-name))
                        (published s))]
                     (comp shown :root))
      (finally
        (effects/register-effect! registered)))))


;; Fixtures


(defn- scale [id]
  {:id id :type :effect :effect-id :scale :enabled? true :params (effects/get-default-params :scale)})

(defn- selected [state component-id ids]
  (assoc-in state [:list-ui component-id :selected-ids] ids))

(defn- projector [n]
  {:name (str "Projector " n) :host (str "127.0.0." n) :port 7255 :service-id n :enabled? true
   :zone-groups [:all] :safety safety/default-safety :output-config {:color-bit-depth 8 :xy-bit-depth 16}})


(defn- headers
  "Which of the parameter editor headers in names each of shown's results
   shows."
  [results names]
  (mapv #(into #{} (filter (:texts %)) names) results))

(deftest open-editors-show-a-re-registered-effect-once-published-test
  (testing "the Effects editor: the list's row and the parameter editor of the selected effect"
    (let [u (random-uuid)
          steps (before-and-after
                 (-> (domains/build-initial-state)
                     (assoc-in [:ui :dialogs :effect-chain-editor] {:open? true :col 0 :row 0 :active-bank-tab :shape :editing-name? false})
                     (assoc-in [:chains :effect-chains [0 0]] {:items [(scale u)]})
                     (selected [:effect-chain 0 0] #{u}))
                 {:fx/type @#'effect-editor/effect-chain-editor-content}
                 :scale "Size")]
      (is (= [#{"Scale"} #{"Size"}] (map :rows steps)))
      (is (= [#{"PARAMETERS: Scale"} #{"PARAMETERS: Size"}] (headers steps ["PARAMETERS: Scale" "PARAMETERS: Size"])))))

  (testing "the Cue Chain Editor: the item effects list's row and the selected effect's parameter editor"
    (let [[p e] (repeatedly 2 random-uuid)
          steps (before-and-after
                 (-> (domains/build-initial-state)
                     (assoc-in [:ui :dialogs :cue-chain-editor] {:open? true :col 0 :row 0 :editing-name? false
                                                                  :active-preset-tab :geometric})
                     (assoc-in [:chains :cue-chains [0 0]]
                               {:items [{:id p :type :preset :preset-id :circle :enabled? true :params {}
                                         :effects [(scale e)]}]})
                     (selected [:cue-chain 0 0] #{p})
                     (selected [:item-effects 0 0] #{e}))
                 {:fx/type @#'cue-editor/cue-chain-editor-content}
                 :scale "Size")]
      (is (= [#{"Circle [All]" "Scale"} #{"Circle [All]" "Size"}] (map :rows steps)) "the cue's row, then its effect's")
      (is (= [#{"PARAMETERS: Scale"} #{"PARAMETERS: Size"}] (headers steps ["PARAMETERS: Scale" "PARAMETERS: Size"])))))

  (testing "the pattern window: its parameter editor"
    (let [steps (before-and-after
                 (-> (domains/build-initial-state)
                     (assoc-in [:ui :dialogs :zone-pattern-editor] {:open? true :cue-key [0 0]})
                     (assoc-in [:chains :cue-chains [0 0]]
                               {:items [{:id (random-uuid) :type :preset :preset-id :circle :enabled? true :params {}}]
                                :zone-patterns [(assoc (zone-patterns/preset-instance :alternate :cue) :id (random-uuid))]}))
                 {:fx/type @#'zone-pattern-editor/content}
                 :zone-pattern "Moves")]
      (is (= [#{"PARAMETERS: Zone pattern"} #{"PARAMETERS: Moves"}]
             (headers steps ["PARAMETERS: Zone pattern" "PARAMETERS: Moves"])))))

  (testing "the Timeline Editor: the selected clip's effect card"
    (let [clip (random-uuid)
          steps (before-and-after
                 (-> (domains/build-initial-state)
                     (assoc-in [:ui :dialogs :timeline-cue-editor] {:open? true :col 0 :row 0})
                     (assoc-in [:chains :cue-chains [0 0]]
                               (model/convert-to-timeline {:items [{:id clip :type :preset :preset-id :circle :enabled? true
                                                                    :effects [(scale (random-uuid))]}]}
                                                          {}))
                     (assoc-in [:ui :timeline-editor [0 0] :selection] #{clip}))
                 {:fx/type @#'timeline-cue-editor/timeline-cue-editor-content}
                 :scale "Size")]
      (is (= ["Scale" "Size"] (map #(get-in % [:by-id "timeline-device-name-0"]) steps)) "the card's name")
      (is (= [#{"PARAMETERS: Scale"} #{"PARAMETERS: Size"}] (headers steps ["PARAMETERS: Scale" "PARAMETERS: Size"]))
          "and its parameter editor")))

  (testing "the projector inspector: its calibration chain's row, the + Add Effect menu and the selected effect's editor"
    (let [c (random-uuid)
          steps (before-and-after
                 (-> (domains/build-initial-state)
                     (assoc :projectors {:p1 (projector 1)})
                     (assoc-in [:projector-ui :active-projector] :p1)
                     (assoc-in [:chains :projector-effects :p1 :items]
                               [{:id c :type :effect :effect-id :axis-transform :enabled? true
                                 :params {:mirror-x? false :mirror-y? false :swap-axes? false}}])
                     (selected [:projector-effects :p1] #{c}))
                 {:fx/type @#'projectors/projector-config-panel}
                 :axis-transform "Mirror")
          offered (fn [step name] (boolean (some #{name} (get-in step [:by-id "projector-add-effect-menu"]))))]
      (is (= [#{"Axis Transform"} #{"Mirror"}] (map :rows steps)))
      (is (= [[true false] [false true]] (map (fn [step] [(offered step "Axis Transform") (offered step "Mirror")]) steps))
          "the menu offers it by its new name")
      (is (= [#{"Edit: Axis Transform"} #{"Edit: Mirror"}] (headers steps ["Edit: Axis Transform" "Edit: Mirror"]))))))
