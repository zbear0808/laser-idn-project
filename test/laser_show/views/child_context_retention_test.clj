(ns laser-show.views.child-context-retention-test
  "A view's children get their :fx/context from cljfx, never from the view
   (reactivity plan 33). cljfx looks a function component's subscription up
   by the description the component gets, and only then puts the child's
   own context in. A parent's context in that description, at its top or in
   a props map inside it, makes every parent render a new child, and keeps
   the old context reachable from the newest cache: its state map, and its
   cache, which reaches the one before it. A callback or a node that keeps
   the whole props map a component got keeps its context too.

   Each test mounts what it checks on a context of its own, on the FX
   thread, with cljfx's context-aware lifecycle and the context in the
   options, as fx/create-app's renderer has them; no app, and
   events/dispatch! does nothing. The supplied probe's parent and child
   (plans/probes/cljfx_reactivity_2026_11_23.clj) show what the checks see.

   contexts-in and reachable-cache-atoms look into a context and its cache
   by cljfx 1.10.6's private keys (the deps.edn pin): a diagnostic for these
   tests only."
  (:require [cljfx.api :as fx]
            [cljfx.lifecycle :as lifecycle]
            [clojure.core.cache :as cache]
            [clojure.repl :as repl]
            [clojure.test :refer [deftest is testing]]
            [laser-show.animation.effects :as effects]
            ;; The effects the fixtures use, registered as the app's frame
            ;; service registers them
            [laser-show.animation.effects.calibration]
            [laser-show.animation.effects.intensity]
            [laser-show.animation.effects.zone]
            [laser-show.animation.safety :as safety]
            [laser-show.events.core :as events]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.state.domains :as domains]
            [laser-show.timeline.model :as model]
            [laser-show.views.components.effect-param-ui :as effect-param-ui]
            [laser-show.views.components.effect-parameter-editor :as param-editor]
            [laser-show.views.components.list :as list]
            [laser-show.views.components.modulator-param-control :as mod-param]
            [laser-show.views.components.safety-editor :as safety-editor]
            [laser-show.views.components.target-control :as target-control]
            [laser-show.views.components.visual-editors.canvas-interaction :as ci]
            [laser-show.views.components.visual-editors.custom-param-renderers :as custom-renderers]
            [laser-show.views.components.visual-editors.keyframe-modulator-panel :as keyframe-panel]
            [laser-show.views.components.visual-editors.timeline-editor :as timeline-editor]
            [laser-show.views.components.visual-editors.timeline.device-chain :as device-chain]
            [laser-show.views.components.visual-editors.timeline.header :as header]
            [laser-show.views.components.zone-pattern-editor :as zone-pattern-editor]
            [laser-show.views.dialogs.cue-chain-editor :as cue-editor]
            [laser-show.views.dialogs.effect-chain-editor :as effect-editor]
            [laser-show.views.dialogs.timeline-cue-editor :as timeline-cue-editor]
            [laser-show.views.tabs.projectors :as projectors])
  (:import [java.lang.reflect Field Modifier]
           [java.util IdentityHashMap]
           [javafx.application Platform]
           [javafx.scene Node Parent Scene]
           [javafx.scene.control Button ComboBox Label]
           [javafx.stage Stage]))


;; The FX thread, cljfx's options


(defn- on-fx
  "(f) on the FX thread: its value, or what it threw, thrown here."
  [f]
  (let [result (promise)]
    (Platform/runLater (bound-fn [] (deliver result (try [(f) nil] (catch Throwable t [nil t])))))
    (let [[v t] (deref result 60000 [nil (ex-info "No result from the FX thread in 60 s" {})])]
      (if t (throw t) v))))

(defn- opts
  "The options a component on ctx gets from fx/create-app's renderer: ctx,
   cljfx's context-aware function lifecycle and a map event handler (one
   that does nothing)."
  [ctx]
  {:fx/context ctx
   :fx.opt/type->lifecycle #(or (fx/keyword->lifecycle %) (fx/fn->lifecycle-with-context %))
   :fx.opt/map-event-handler (fn [_])})

(defn- mount!
  "desc created on a context of state with cache-factory's cache, then
   advanced once for each of changes (fns of the state), each on a context
   derived from the one before, as the app's commits are; (f {:ctx the
   context :root the component's node}) after the last render, or with
   each? after each one; then the component deleted. All on the FX thread,
   with events/dispatch! doing nothing."
  [state cache-factory desc changes f each?]
  (with-redefs [events/dispatch! (fn [& _])]
    (on-fx
     (fn []
       (let [ctx (volatile! (fx/create-context state cache-factory))
             component (volatile! (fx/create-component desc (opts @ctx)))
             now #(f {:ctx @ctx :root (fx/instance @component)})]
         (try
           (let [seen (volatile! (if each? [(now)] []))]
             (doseq [change changes]
               (vswap! ctx fx/swap-context change)
               (vswap! component fx/advance-component desc (opts @ctx))
               (when each? (vswap! seen conj (now))))
             (if each? @seen (now)))
           (finally (fx/delete-component @component (opts @ctx)))))))))

(defn- mounted
  "mount!: f's value after the last render."
  [state cache-factory desc changes f]
  (mount! state cache-factory desc changes f false))

(defn- each-render
  "mount!: f's values after the first render and after each change."
  [state cache-factory desc changes f]
  (mount! state cache-factory desc changes f true))

(def ^:private lru-512
  "The app's cache (state.core/cache-factory)."
  #(cache/lru-cache-factory % :threshold 512))

(def ^:private unbounded
  "A cache that keeps every entry: every description a function component
   got stays in it."
  cache/basic-cache-factory)

(defn- unrelated
  "A change no view reads."
  [state]
  (update state ::unrelated (fnil inc 0)))


;; What a context's cache holds


(def ^:private call-context-fn
  "What cljfx subscribes a function component with: the component's cache
   key is [call-context-fn the-description-it-got]."
  @#'lifecycle/call-context-fn)

(defn- context? [x]
  (and (map? x) (contains? x :cljfx.context/*cache)))

(defn- closed-over
  "What function f closes over."
  [f]
  (for [^Field field (.getDeclaredFields (class f))
        :when (not (Modifier/isStatic (.getModifiers field)))]
    (.get (doto field (.setAccessible true)) f)))

(defn- contexts-in
  "The contexts in x: x itself, or one in its maps (keys and values),
   vectors, sets and realized sequences, a context ending the walk. With
   fns?, also one a function in it closes over."
  ([x] (contexts-in x false))
  ([x fns?]
   (let [seen (IdentityHashMap.)
         found (volatile! [])]
     (letfn [(walk [x]
               (cond
                 (context? x) (vswap! found conj x)
                 (or (nil? x) (.containsKey seen x)) nil
                 :else (do (.put seen x true)
                           (cond
                             (map? x) (run! (fn [[k v]] (walk k) (walk v)) x)
                             (or (vector? x) (set? x)) (run! walk x)
                             (instance? clojure.lang.LazySeq x) (when (realized? x) (run! walk x))
                             (sequential? x) (run! walk x)
                             (and fns? (fn? x)) (run! walk (closed-over x))))))]
       (walk x)
       @found))))

(defn- component-key? [k]
  (and (vector? k) (identical? call-context-fn (first k)) (map? (second k))))

(defn- function-components
  "The function components in ctx's cache: {:desc the description each one
   got (before cljfx put its context in), :value the one it returned}."
  [ctx]
  (for [[k entry] (seq @(:cljfx.context/*cache ctx))
        :when (component-key? k)]
    {:desc (second k) :value (:cljfx.context/value entry)}))

(defn- reachable-cache-atoms
  "How many caches ctx's cache leads to: itself, then the cache of each
   context in the description a function component in a cache reached got,
   each cache once."
  [ctx]
  (loop [todo [(:cljfx.context/*cache ctx)] seen #{}]
    (if-let [a (peek todo)]
      (if (contains? seen a)
        (recur (pop todo) seen)
        (recur (into (pop todo)
                     (for [[k _] (seq @a)
                           :when (component-key? k)
                           c (contexts-in (second k))]
                       (:cljfx.context/*cache c)))
               (conj seen a)))
      (count seen))))

(defn- type-name [t]
  (if (fn? t) (repl/demunge (.getName (class t))) t))

(defn- with-context
  "Which of ctx's function components have a context: given one (in the
   description it got), giving one (in the one it returned), or keeping one
   in a function of either. Each a set of their types."
  [ctx]
  (let [types (fn [pred]
                (into (sorted-set) (comp (filter pred) (map (comp type-name :fx/type :desc))) (function-components ctx)))]
    {:given (types #(seq (contexts-in (:desc %))))
     :returned (types #(seq (contexts-in (:value %))))
     :closed-over (types #(seq (contexts-in [(:desc %) (:value %)] true)))}))

(def ^:private none {:given #{} :returned #{} :closed-over #{}})

(defn- given
  "The descriptions function components of type f got, in ctx's cache."
  [ctx f]
  (keep #(when (identical? f (:fx/type (:desc %))) (:desc %)) (function-components ctx)))

(defn- returned
  "The descriptions function components of type f returned."
  [ctx f]
  (keep #(when (identical? f (:fx/type (:desc %))) (:value %)) (function-components ctx)))

(defn- descs-in
  "Every map in desc's tree."
  [desc]
  (filter map? (tree-seq coll? seq desc)))

(defn- nodes
  "node and every node under it (a control's skin's too)."
  [^Node node]
  (cons node (when (instance? Parent node) (mapcat nodes (.getChildrenUnmodifiable ^Parent node)))))

(defn- node-by-id [root id]
  (some #(when (= id (.getId ^Node %)) %) (nodes root)))

(defn- kept-by-node
  "What node keeps for itself: its user data and its properties."
  [^Node node]
  [(.getUserData node) (into {} (.getProperties node))])


;; The probe's parent and child


(defn- parent-child
  "The probe's parent and child, the child's label showing what it read.
   The parent reads :n and gives its child, by mode, its own context as
   :fx/context (:direct), that only inside a :props map (:nested), or none
   (:injected); the child reads :stable. From {:n 0 :stable 1}: updates
   changes of :n, then :stable 2 alone, on an LRU of capacity. {:calls the
   child's runs [after its first render, after the updates, after :stable]
   :reachable cache atoms after the updates :text the label's at the end}."
  [mode updates capacity]
  (let [calls (atom 0)
        child (fn [{:keys [fx/context]}]
                (swap! calls inc)
                {:fx/type :label :text (str (fx/sub-val context :stable))})
        parent (fn [{:keys [fx/context]}]
                 (fx/sub-val context :n)
                 (case mode
                   :direct {:fx/type child :fx/context context}
                   :nested {:fx/type child :props {:fx/context context}}
                   :injected {:fx/type child}))
        desc {:fx/type parent}]
    (on-fx
     (fn []
       (let [ctx (volatile! (fx/create-context {:n 0 :stable 1} #(cache/lru-cache-factory % :threshold capacity)))
             component (volatile! (fx/create-component desc (opts @ctx)))
             advance! (fn [f & args]
                        (vswap! ctx #(apply fx/swap-context % f args))
                        (vswap! component fx/advance-component desc (opts @ctx)))]
         (try
           (let [first-render @calls
                 _ (doseq [n (range 1 (inc updates))] (advance! assoc :n n))
                 after-updates @calls
                 reachable (reachable-cache-atoms @ctx)]
             (advance! assoc :stable 2)
             {:calls [first-render after-updates @calls]
              :reachable reachable
              :text (.getText ^Label (fx/instance @component))})
           (finally (fx/delete-component @component (opts @ctx)))))))))

(deftest explicit-nested-and-injected-controls-test
  (let [modes [:direct :nested :injected]]
    (testing "100 renders of the parent, an 8-entry LRU: the caches the newest one leads to when the child's description has the parent's context, at its top or nested, or none"
      (is (= [101 101 1] (mapv #(:reachable (parent-child % 100 8)) modes))))
    (testing "20 renders on the app's 512 entries"
      (let [runs (mapv #(parent-child % 20 512) modes)]
        (testing ": a child given its parent's context renders with each parent render"
          (is (= [21 21 1] (mapv #(get-in % [:calls 1]) runs))))
        (testing "; a child cljfx gives its context renders once, then when what it reads changes, and shows that"
          (is (= [1 1 2] (:calls (peek runs))))
          (is (= ["2" "2" "2"] (mapv :text runs))))))))


;; The Effects editor


(def ^:private effects-editor
  "The Effects editor's content, as its window shows it."
  {:fx/type @#'effect-editor/effect-chain-editor-content})

(defn- intensity [id]
  {:id id :type :effect :effect-id :intensity :enabled? true :params {:amount 0.75}})

(defn- effects-editor-state
  "The Effects editor open at [0 0], whose chain is items."
  [items]
  (-> (domains/build-initial-state)
      (assoc-in [:ui :dialogs :effect-chain-editor] {:open? true :col 0 :row 0 :active-bank-tab :shape :editing-name? false})
      (assoc-in [:chains :effect-chains [0 0]] {:items items})))

(defn- selected [state component-id ids]
  (assoc-in state [:list-ui component-id :selected-ids] ids))

(deftest effect-editor-does-not-link-old-caches-test
  (let [u (random-uuid)
        state (effects-editor-state [(intensity u)])]
    (testing "600 names of cell [1 0], each rendered while the editor shows [0 0] and its effect: the newest cache leads to no older one"
      (is (= 1 (mounted state lru-512 effects-editor
                        (for [i (range 600)] #(assoc-in % [:chains :effect-chains [1 0] :name] (str "Cell " i)))
                        (comp reachable-cache-atoms :ctx)))))
    (testing "its effect selected and not, by turns, so the list renders its rows each time"
      (is (= {:reachable 1 :shows "1 selected"}
             (mounted state lru-512 effects-editor
                      (for [i (range 201)] #(selected % [:effect-chain 0 0] (if (even? i) #{u} #{})))
                      (fn [{:keys [ctx root]}]
                        {:reachable (reachable-cache-atoms ctx)
                         :shows (some-> ^Label (.lookup ^Node root ".chain-selection-count") .getText)})))))))


;; Every child description of the plan's inventory


(defn- cue-editor-state
  "The Cue Chain Editor open at [0 0]: an open group g holding preset p,
   p selected and being renamed, and its effect e selected."
  [g p e]
  (-> (domains/build-initial-state)
      (assoc-in [:ui :dialogs :cue-chain-editor] {:open? true :col 0 :row 0 :editing-name? false})
      (assoc-in [:chains :cue-chains [0 0]]
                {:items [{:id g :type :group :name "Group" :collapsed? false :enabled? true
                          :items [{:id p :type :preset :preset-id :circle :enabled? true
                                   :effects [(intensity e)]}]}]})
      (assoc-in [:list-ui [:cue-chain 0 0]] {:selected-ids #{p} :last-selected-id p :renaming-id p})
      (selected [:item-effects 0 0] #{e})))

(defn- keyframed-zone-selector
  "A Zone Selector (a custom renderer) with keyframes on, at 0 and 1, both
   All, the first selected."
  [id]
  {:id id :type :effect :effect-id :zone-selector :enabled? true :params {:target-zone :all}
   :keyframe-modulator {:enabled? true :driver :time :period 4.0 :selected-keyframe 0
                        :keyframes [{:position 0.0 :params {:target-zone :all}}
                                    {:position 1.0 :params {:target-zone :all}}]}})

(defn- projector [n]
  {:name (str "Projector " n) :host (str "127.0.0." n) :port 7255 :service-id n :enabled? true
   :zone-groups [:all] :safety safety/default-safety :output-config {:color-bit-depth 8 :xy-bit-depth 16}})

(defn- projectors-state
  "Projector :p1 selected, in memory (and :p2, to copy its zone to), its
   calibration chain one Axis Transform c, selected."
  [c]
  (-> (domains/build-initial-state)
      (assoc :projectors {:p1 (projector 1) :p2 (projector 2)})
      (assoc-in [:projector-ui :active-projector] :p1)
      (assoc-in [:chains :projector-effects :p1 :items]
                [{:id c :type :effect :effect-id :axis-transform :enabled? true
                  :params {:mirror-x? false :mirror-y? false :swap-axes? false}}])
      (selected [:projector-effects :p1] #{c})))

(defn- timeline-state
  "The Timeline Editor open at [0 0]: its one clip, selected, has an
   Intensity effect."
  [clip effect]
  (-> (domains/build-initial-state)
      (assoc-in [:ui :dialogs :timeline-cue-editor] {:open? true :col 0 :row 0})
      (assoc-in [:chains :cue-chains [0 0]]
                (model/convert-to-timeline {:items [{:id clip :type :preset :preset-id :circle :enabled? true
                                                     :effects [(intensity effect)]}]}
                                           {}))
      (assoc-in [:ui :timeline-editor [0 0] :selection] #{clip})))

(def ^:private modulatable-choice
  "A choice that may follow a modulator (zones plan Z5), in the controls
   list of the parameter editors."
  {:fx/type mod-param/modulatable-param-controls-list
   :params-map {:style {:key :style :label "Style" :type :choice :modulatable? true
                        :choices [:alternate :chase] :default :alternate}}
   :current-params {:style :alternate}
   :on-change-event {:event/type :chain/update-param :domain :effect-chains :entity-key [0 0] :effect-path [0]}
   :on-text-event {:event/type :chain/update-param-from-text :domain :effect-chains :entity-key [0 0] :effect-path [0]}
   :modulator-event-base {:domain :effect-chains :entity-key [0 0] :effect-path [0]}})

(defn- address-on
  "The command address on root's list editor node id (reactivity plan 04)."
  [root id]
  (::list/address (.getUserData ^Node (node-by-id root id))))

(deftest outgoing-props-are-context-free-test
  (testing "the Cue Chain Editor: a preset in an open group, selected and being renamed, its effect selected"
    (let [[g p e] (repeatedly 3 random-uuid)
          {:keys [found rows headers cards renames toolbars chips middle item-effect editors panels addresses]}
          (mounted (cue-editor-state g p e) unbounded {:fx/type @#'cue-editor/cue-chain-editor-content} [unrelated]
                   (fn [{:keys [ctx root]}]
                     {:found (with-context ctx)
                      :rows (given ctx @#'list/render-list-item)
                      :headers (given ctx @#'list/group-header)
                      :cards (given ctx @#'list/list-item-card)
                      :renames (given ctx @#'list/rename-text-field)
                      :toolbars (given ctx @#'list/group-toolbar)
                      :chips (given ctx target-control/target-chip)
                      :middle (given ctx @#'cue-editor/middle-section)
                      :item-effect (given ctx @#'cue-editor/item-effect-parameter-editor)
                      :editors (given ctx param-editor/effect-parameter-editor)
                      :panels (given ctx keyframe-panel/keyframe-modulator-panel)
                      :addresses (mapv #(address-on root %) ["list-editor-cue-chain-0-0" "list-editor-item-effects-0-0"])}))]
      (testing "every branch is there"
        (let [cue-rows (filter #(= [:cue-chain 0 0] (:component-id %)) rows)]
          (is (= #{g p} (set (map (comp :id :item) cue-rows))) "the group's row, and its preset's inside it")
          (is (= #{e} (set (map (comp :id :item) (remove (set cue-rows) rows)))) "the effect's, in the item's list"))
        (is (every? #(= (:component-id %) (get-in % [:props :component-id])) rows) "each with its list's props")
        (is (= [g] (map (comp :id :group) headers)))
        (is (= [p] (map (comp :id :item) (filter :renaming? cards))))
        (is (= [[p [:cue-chain 0 0]]] (map (juxt :item-id (comp :component-id :props)) renames)))
        (is (= #{[:cue-chain 0 0] [:item-effects 0 0]} (set (map (comp :component-id :props) toolbars))))
        (is (= #{:item :cue} (set (map :scope chips))) "the preset's Target and the cue's")
        (is (seq middle))
        (is (seq item-effect))
        (is (= #{:cue-chains} (set (map :domain editors))))
        (is (seq panels))
        (is (= [[:cue-chain 0 0] [:item-effects 0 0]] (map :component-id addresses)) "each list's command address, on its node"))
      (is (= none found))
      (is (zero? (count (contexts-in addresses true))) "the addresses hold no context")))

  (testing "the Effects editor, its effect custom (a Zone Selector) and keyframed, selected"
    (let [k (random-uuid)
          {:keys [found wrappers editors internal renderers selectors panels actions address]}
          (mounted (selected (effects-editor-state [(keyframed-zone-selector k)]) [:effect-chain 0 0] #{k})
                   unbounded effects-editor [unrelated]
                   (fn [{:keys [ctx root]}]
                     {:found (with-context ctx)
                      :wrappers (given ctx @#'effect-editor/parameter-editor)
                      :editors (given ctx param-editor/effect-parameter-editor)
                      :internal (given ctx @#'param-editor/custom-param-renderer-internal)
                      :renderers (given ctx effect-param-ui/custom-param-renderer)
                      :selectors (given ctx custom-renderers/zone-selector-visual-editor)
                      :panels (given ctx keyframe-panel/keyframe-modulator-panel)
                      :actions (given ctx @#'keyframe-panel/actions-row)
                      :address (address-on root "list-editor-effect-chain-0-0")}))]
      (testing "every branch is there"
        (is (seq wrappers))
        (is (= #{[0]} (set (map :effect-path editors))))
        (is (seq internal))
        (is (seq renderers))
        (is (seq selectors))
        (is (= #{true} (set (map (comp :enabled? :keyframe-modulator) panels))))
        (is (= #{:zone-group-id} (set (map :param-type actions))))
        (is (= [:effect-chain 0 0] (:component-id address))))
      (is (= none found))
      (is (zero? (count (contexts-in address true))) "the address holds no context")))

  (testing "the Effects editor, a zone pattern selected (its visual editor)"
    (let [zp (random-uuid)
          {:keys [found visuals]}
          (mounted (selected (effects-editor-state [(assoc (zone-patterns/preset-instance :alternate :cell) :id zp)])
                             [:effect-chain 0 0] #{zp})
                   unbounded effects-editor [unrelated]
                   (fn [{:keys [ctx]}]
                     {:found (with-context ctx)
                      :visuals (given ctx zone-pattern-editor/pattern-visual)}))]
      (is (seq visuals))
      (is (= none found))))

  (testing "the projector inspector: a projector in memory selected, then renamed, then its test pattern on"
    (let [{:keys [found columns calibration-descs calibration lists canvases]}
          (mounted (projectors-state (random-uuid)) unbounded {:fx/type @#'projectors/projector-config-panel}
                   [#(assoc-in % [:projectors :p1 :name] "Renamed")
                    #(assoc-in % [:projector-ui :calibrating-projector-id] :p1)]
                   (fn [{:keys [ctx]}]
                     {:found (with-context ctx)
                      :columns (into {} (map (juxt :id :children)) (given ctx @#'projectors/inspector-column))
                      :calibration-descs (given ctx @#'projectors/calibration-controls)
                      :calibration (returned ctx @#'projectors/calibration-controls)
                      :lists (given ctx list/list-editor)
                      :canvases (given ctx ci/interactive-canvas)}))
          types (fn [id] (set (map :fx/type (get columns id))))]
      (testing "every branch is there"
        (is (= #{"projector-column-left" "projector-column-middle" "projector-column-right"} (set (keys columns))))
        (is (every? (types "projector-column-middle") [@#'projectors/calibration-controls @#'projectors/projector-effects-sidebar]))
        (is (contains? (types "projector-column-right") safety-editor/safety-section))
        (is (= #{[:projector-effects :p1]} (set (map :component-id lists))))
        (is (= #{"safety-canvas"} (set (map :id canvases)))))
      (testing "both renders of the inspector give the test pattern controls the projector, and only that; they still show its change"
        (is (= [#{:fx/type :projector-id}] (map (comp set keys) calibration-descs)))
        (is (= [:p1] (map :projector-id calibration-descs)))
        (is (= ["Hide test pattern"]
               (keep #(when (= "projector-calibration-button" (:id %)) (:text %)) (mapcat descs-in calibration)))))
      (is (= none found))))

  (testing "the safety zone's canvas keeps no context on its node"
    (let [kept (mounted (projectors-state (random-uuid)) unbounded {:fx/type safety-editor/safety-section :projector-id :p1}
                        [unrelated]
                        (fn [{:keys [root]}] (kept-by-node (node-by-id root "safety-canvas"))))]
      (is (seq (second kept)) "it keeps what it draws from")
      (is (zero? (count (contexts-in kept true))))))

  (testing "the Timeline Editor: its clip selected, which has an effect"
    (let [[clip effect] (repeatedly 2 random-uuid)
          {:keys [found inner bodies headers chips cards editors canvases kept]}
          (mounted (timeline-state clip effect) unbounded {:fx/type @#'timeline-cue-editor/timeline-cue-editor-content}
                   [unrelated]
                   (fn [{:keys [ctx root]}]
                     {:found (with-context ctx)
                      :inner (map :desc (returned ctx timeline-editor/timeline-editor))
                      :bodies (given ctx @#'timeline-editor/editor-body)
                      :headers (given ctx header/header-column)
                      :chips (given ctx target-control/target-chip)
                      :cards (given ctx @#'device-chain/effect-card)
                      :editors (given ctx param-editor/effect-parameter-editor)
                      :canvases (given ctx ci/interactive-canvas)
                      :kept (kept-by-node (node-by-id root "timeline-canvas"))}))]
      (testing "every branch is there"
        (is (= #{@#'timeline-editor/editor-body} (set (map :fx/type inner))) "the local state's inner description")
        (is (= #{[0 0]} (set (map (juxt :col :row) bodies))))
        (is (seq headers))
        (is (every? (set (map :scope chips)) [:track :clip :cue]) "a track's Target, the clip's and the cue's")
        (is (= #{effect} (set (map (comp :id :effect) cards))))
        (is (= #{:timeline-items} (set (map :domain editors))))
        (is (= #{"timeline-canvas"} (set (map :id canvases))))
        (is (seq (second kept)) "its canvas keeps what it draws from"))
      (is (= none found))
      (is (zero? (count (contexts-in kept true))) "its canvas keeps no context")))

  (testing "a choice that may follow a modulator"
    (let [{:keys [found choices]}
          (mounted (domains/build-initial-state) unbounded modulatable-choice [unrelated]
                   (fn [{:keys [ctx]}]
                     {:found (with-context ctx)
                      :choices (given ctx mod-param/modulator-choice-control)}))]
      (is (= #{:style} (set (map :param-key choices))))
      (is (= none found)))))


;; A child whose description stays the same still follows what it reads


(defn- zone-pickers
  "What each zone picker under root offers (ids) and shows (the id): the
   Zone Selector's, beside its \"Target Zone:\", and the keyframe Value's."
  [root]
  (let [shown (fn [^ComboBox c] (when c {:ids (set (map :id (.getItems c))) :value (:id (.getValue c))}))
        selector (some (fn [n]
                         (when (and (instance? Label n) (= "Target Zone:" (.getText ^Label n)))
                           (some #(when (instance? ComboBox %) %) (.getChildrenUnmodifiable (.getParent ^Node n)))))
                       (nodes root))]
    {:selector (shown selector)
     :keyframe (shown (node-by-id root "keyframe-zone-value"))}))

(deftest unchanged-child-props-still-track-selection-and-groups-test
  (testing "the same list description through a selection and a clipboard change: its sidebar shows both"
    (let [u (random-uuid)
          desc {:fx/type list/list-editor
                :items [(intensity u)]
                :component-id [:effect-chain 0 0]
                :items-path [:chains :effect-chains [0 0] :items]
                :item-id-key :effect-id
                :item-registry-fn effects/get-effect
                :fallback-label "Unknown Effect"
                :on-change-event :chain/set-items
                :on-change-params {:domain :effect-chains :entity-key [0 0]}
                :header-label "CHAIN"
                :empty-text "No effects"}
          state (assoc-in (effects-editor-state [(intensity u)]) [:ui :clipboard]
                          {:type :item-effects :effects [(intensity (random-uuid))] :copied-at 0})]
      (is (= [{:selection nil :paste-disabled? false}
              {:selection "1 selected" :paste-disabled? false}
              {:selection "1 selected" :paste-disabled? true}]
             (each-render state lru-512 desc
                          [#(selected % [:effect-chain 0 0] #{u})
                           #(assoc-in % [:ui :clipboard] {:type :cue-chain-items :copied-at 1
                                                          :items [{:id (random-uuid) :type :preset :preset-id :circle}]})]
                          (fn [{:keys [root]}]
                            {:selection (some-> ^Label (.lookup ^Node root ".chain-selection-count") .getText)
                             :paste-disabled? (.isDisable ^Button (.lookup ^Node root "#chain-paste-items"))}))))))
  (testing "a keyframed Zone Selector in the shared parameter editor, shown in a window: a new group reaches both its pickers"
    (let [effect (keyframed-zone-selector (random-uuid))
          desc {:fx/type param-editor/effect-parameter-editor
                :domain :effect-chains
                :entity-key [0 0]
                :effect-path [0]
                :effect effect
                :effect-def (effects/get-effect :zone-selector)
                :dialog-data {}}
          stage (volatile! nil)]
      (try
        (let [[before after]
              (each-render (assoc-in (domains/build-initial-state) [:chains :effect-chains [0 0]] {:items [effect]})
                           lru-512 desc
                           [#(assoc-in % [:zone-groups :probe-group] {:name "Probe group"})]
                           (fn [{:keys [^Parent root]}]
                             (when-not @stage
                               (vreset! stage (doto (Stage.) (.setScene (Scene. root 900 700)) (.show))))
                             (.applyCss root)
                             (.layout root)
                             (zone-pickers root)))]
          (is (every? some? (vals before)) "both pickers are there")
          (is (= [:all :all] (map :value (vals before))))
          (is (not-any? #(contains? (:ids %) :probe-group) (vals before)))
          (is (every? #(contains? (:ids %) :probe-group) (vals after)) "each one offers the new group")
          (is (= [:all :all] (map :value (vals after))) "and still shows All"))
        (finally (on-fx #(some-> ^Stage @stage .close)))))))
