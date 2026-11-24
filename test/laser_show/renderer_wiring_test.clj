(ns laser-show.renderer-wiring-test
  "The app's renderer wiring (reactivity plan 31): app/create-app gives
   fx/create-app options it reads. The renderer error handler gets what a
   render throws, the app's one handler is the renderer's map event
   handler, and in dev mode (the :dev alias: -Dlaser-show.dev=true, dev/
   and the cljfx.dev jar) laser-show.dev-renderer/middleware puts cljfx.dev's
   validation around the app's own lifecycles and changes nothing else; out
   of dev mode nothing optional is looked up. Each test checks what its
   JVM's mode asks for: run the namespace with and without :dev. Each one
   builds the app on a context atom of its own, so app state and a mounted
   app are left alone."
  (:require [cljfx.api :as fx]
            [cljfx.event-handler :as event-handler]
            [cljfx.lifecycle :as lifecycle]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.tools.logging.test :as log-test]
            [laser-show.app :as app]
            [laser-show.dev-config :as dev-config]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.views.root :as root])
  (:import [javafx.scene.control Label]))

;; app/create-app makes the JVM's live app instance: retire it, as
;; app/stop! does
(use-fixtures :each (fn [f] (try (f) (finally (events/retire!)))))

(defn- with-dev-property
  "Run f with the laser-show.dev system property set to value (nil clears it),
   then restore it."
  [value f]
  (let [old (System/getProperty "laser-show.dev")]
    (if value
      (System/setProperty "laser-show.dev" value)
      (System/clearProperty "laser-show.dev"))
    (try (f)
         (finally (if old
                    (System/setProperty "laser-show.dev" old)
                    (System/clearProperty "laser-show.dev"))))))

(defn- built-app
  "Run app/create-app on ctx-atom with cljfx making its renderer and handler
   as usual, but without mounting: {:app what create-app returned,
   :renderers [{:args the options fx/create-renderer got, :renderer what it
   made}], :mounts [[ref renderer]] (fx/mount-renderer's calls), :async
   [{:options :handler}] (cljfx.event-handler/wrap-async's agent options and
   the handler it made)}."
  [ctx-atom]
  (let [renderers (atom [])
        mounts (atom [])
        async (atom [])
        create-renderer fx/create-renderer
        wrap-async event-handler/wrap-async]
    (with-redefs [state/get-context-atom (constantly ctx-atom)
                  fx/create-renderer (fn [& kvs]
                                       (let [renderer (apply create-renderer kvs)]
                                         (swap! renderers conj {:args (apply hash-map kvs) :renderer renderer})
                                         renderer))
                  fx/mount-renderer (fn [ref renderer] (swap! mounts conj [ref renderer]) nil)
                  event-handler/wrap-async (fn [f options]
                                             (let [handler (wrap-async f options)]
                                               (swap! async conj {:options options :handler handler})
                                               handler))]
      {:app (#'app/create-app) :renderers @renderers :mounts @mounts :async @async})))

(defn- through-middleware
  "What the lifecycle under a renderer's middleware gets, args being the
   options fx/create-renderer got: a create with context c1, an advance to
   c2 and a delete, each with the renderer's own options plus ::sentinel.
   {:calls [[:create desc opts] [:advance component desc opts] [:delete
   component opts]], :results what the three calls returned, :opts the
   options given}."
  [args c1 c2]
  (let [calls (atom [])
        delegate (reify lifecycle/Lifecycle
                   (create [_ desc opts] (swap! calls conj [:create desc opts]) ::created)
                   (advance [_ component desc opts] (swap! calls conj [:advance component desc opts]) ::advanced)
                   (delete [_ component opts] (swap! calls conj [:delete component opts]) ::deleted))
        lc ((:middleware args) delegate)
        opts (assoc (:opts args) ::sentinel :kept)
        created (lifecycle/create lc c1 opts)
        advanced (lifecycle/advance lc created c2 opts)
        deleted (lifecycle/delete lc advanced opts)]
    {:calls @calls :results [created advanced deleted] :opts opts}))

(defn- watching-dev-factory
  "Call f. In dev mode, with cljfx.dev/wrap-type->lifecycle recording into
   made each call's options and the type->lifecycle it returned."
  [made f]
  (if (dev-config/dev-mode?)
    (let [factory (requiring-resolve 'cljfx.dev/wrap-type->lifecycle)
          original @factory]
      (with-redefs-fn {factory (fn [& kvs]
                                 (let [wrapped (apply original kvs)]
                                   (swap! made conj [(apply hash-map kvs) wrapped])
                                   wrapped))}
        f))
    (f)))

(defn- watching-lookups
  "Call f with requiring-resolve recording into looked-up each symbol of
   laser-show.dev-renderer or cljfx.dev it is asked for, then resolving it
   with resolve! (default: as usual)."
  ([looked-up f] (watching-lookups looked-up requiring-resolve f))
  ([looked-up resolve! f]
   (with-redefs [requiring-resolve (fn [sym]
                                     (when (#{"laser-show.dev-renderer" "cljfx.dev"} (namespace sym))
                                       (swap! looked-up conj sym))
                                     (resolve! sym))]
     (f))))

(defn- lifecycles-given
  "The :fx.opt/type->lifecycle each call in calls (through-middleware's) got."
  [calls]
  (map (comp :fx.opt/type->lifecycle peek) calls))

(defn- app-log
  "laser-show.app's log entries (with-log) at one of levels."
  [& levels]
  (filter #(and (= "laser-show.app" (str (:logger-ns %))) (some #{(:level %)} levels))
          (log-test/the-log)))

(deftest create-app-delivers-effective-options-test
  (let [ctx-atom (atom (fx/create-context {}))
        {:keys [app renderers mounts async]} (built-app ctx-atom)
        [{:keys [args renderer]}] renderers]
    (testing "one renderer, mounted once, on the context atom it was given"
      (is (= 1 (count renderers)))
      (is (= 1 (count mounts)))
      (is (identical? ctx-atom (ffirst mounts)))
      (is (identical? renderer (second (first mounts))))
      (is (identical? renderer (:renderer app))))
    (testing "what a render throws goes to the app's renderer error handler"
      (is (identical? @#'app/default-error-handler (:error-handler args))))
    (testing "the app's one handler: the renderer's map event handler, on its own agent on the event thread"
      (is (identical? (:handler app) (get-in args [:opts :fx.opt/map-event-handler])))
      (is (= 1 (count async)))
      (is (identical? (:handler app) (:handler (first async))))
      (is (identical? @#'app/agent-error-handler (get-in async [0 :options :error-handler])))
      (is (identical? events/event-executor (get-in async [0 :options :fx/executor]))))
    (testing "under cljfx's context and desc-fn middleware, the app's"
      (let [b (get-in args [:opts :fx.opt/type->lifecycle])
            c1 (fx/create-context {:n 1})
            c2 (fx/swap-context c1 assoc :n 2)
            made (atom [])
            {:keys [calls results opts]} (watching-dev-factory made #(through-middleware args c1 c2))
            [[_ create-desc create-opts] [_ advanced advance-desc advance-opts] [_ deleted delete-opts]] calls
            [created-result advanced-result deleted-result] results]
        (is (= [:create :advance :delete] (map first calls)))
        (is (= {:fx/type root/root-view} create-desc advance-desc) "the root view, from the app's desc-fn")
        (testing "the delegate's results come back, and it advances and deletes what it made"
          (is (= ::created (:child created-result) advanced))
          (is (= ::advanced (:child advanced-result) deleted))
          (is (= ::deleted deleted-result)))
        (testing "the context of the call, where cljfx gives one"
          (is (identical? c1 (:fx/context create-opts)))
          (is (identical? c2 (:fx/context advance-opts)))
          (is (not (contains? delete-opts :fx/context)) "cljfx gives a delete none; none is kept to add"))
        (testing "every other option as the renderer has it"
          (doseq [given [create-opts advance-opts delete-opts]]
            (is (= :kept (::sentinel given)))
            (is (identical? (:handler app) (:fx.opt/map-event-handler given)))
            (is (= (dissoc opts :fx.opt/type->lifecycle)
                   (dissoc given :fx/context :fx.opt/type->lifecycle)))))
        (if (dev-config/dev-mode?)
          (testing "dev mode: cljfx.dev's wrapper around cljfx's own type->lifecycle, made once"
            (let [[[factory-opts d]] @made]
              (is (= 1 (count @made)))
              (is (identical? b (:type->lifecycle factory-opts)))
              (is (= [:f12] (:inspector-shortcut factory-opts)))
              (is (not (identical? b d)))
              (is (every? #(identical? d %) (lifecycles-given calls)))))
          (testing "out of dev mode: cljfx's own type->lifecycle"
            (is (every? #(identical? b %) (lifecycles-given calls)))))))))

(deftest optional-tools-load-only-in-dev-test
  (let [c1 (fx/create-context {:n 1})
        c2 (fx/swap-context c1 assoc :n 2)
        built (fn []
                (let [{:keys [renderers]} (built-app (atom (fx/create-context {})))
                      args (:args (first renderers))]
                  {:b (get-in args [:opts :fx.opt/type->lifecycle])
                   :calls (:calls (through-middleware args c1 c2))}))]
    (if (dev-config/dev-mode?)
      (testing "the :dev JVM"
        (testing "the dev middleware loads, and cljfx.dev with the help in dev/"
          (is (var? (requiring-resolve 'laser-show.dev-renderer/middleware)))
          (let [help (io/resource "cljfx/dev/help.clj")]
            (is (= "file" (.getProtocol help)))
            (is (str/ends-with? (str help) "/dev/cljfx/dev/help.clj")))
          (is (str/includes? ((requiring-resolve 'cljfx.dev/long-prop-help-syntax) {:type :color}) "- :red")
              "the help names JavaFX's colors"))
        (testing "and create-app uses it"
          (log-test/with-log
            (let [looked-up (atom [])
                  {:keys [b calls]} (watching-lookups looked-up built)]
              (is (= ['laser-show.dev-renderer/middleware] @looked-up))
              (is (not-any? #(identical? b %) (lifecycles-given calls)))
              (is (empty? (app-log :warn :error)) "no fallback")
              (is (some #(str/includes? (:message %) "laser-show.dev-renderer/middleware") (app-log :info))
                  "the log names the middleware in use")))))
      (testing "the ordinary JVM"
        (testing "dev mode off: nothing optional looked up, cljfx's own lifecycle"
          (with-dev-property nil
            (fn []
              (log-test/with-log
                (let [looked-up (atom [])
                      {:keys [b calls]} (watching-lookups looked-up built)]
                  (is (= [] @looked-up))
                  (is (every? #(identical? b %) (lifecycles-given calls)))
                  (is (nil? (find-ns 'laser-show.dev-renderer)))
                  (is (nil? (find-ns 'cljfx.dev)))
                  (is (empty? (app-log :info :warn :error))))))))
        (testing "dev mode asked for without dev/ on the classpath: one warning with the cause, cljfx's own lifecycle"
          (with-dev-property "true"
            (fn []
              (log-test/with-log
                (let [looked-up (atom [])
                      {:keys [b calls]} (watching-lookups looked-up built)
                      warnings (app-log :warn)]
                  (is (= ['laser-show.dev-renderer/middleware] @looked-up) "looked up once, for this mount")
                  (is (= 1 (count warnings)))
                  (is (some? (:throwable (first warnings))))
                  (is (empty? (app-log :info)) "nothing claims the inspector is on")
                  (is (every? #(identical? b %) (lifecycles-given calls))))))))
        (testing "a dev namespace without the middleware: one warning, cljfx's own lifecycle"
          (with-dev-property "true"
            (fn []
              (log-test/with-log
                (let [looked-up (atom [])
                      {:keys [b calls]} (watching-lookups looked-up (constantly nil) built)]
                  (is (= ['laser-show.dev-renderer/middleware] @looked-up))
                  (is (= 1 (count (app-log :warn))))
                  (is (empty? (app-log :info)))
                  (is (every? #(identical? b %) (lifecycles-given calls))))))))))))


;; A real renderer from app/create-app, with fixture-parent for the root view


(def ^:private view-calls (atom {}))

(defn- fixture-child
  "The label showing :n. Its description, from fixture-parent, has no props:
   cljfx gives it its context."
  [{:keys [fx/context]}]
  (swap! view-calls update :child (fnil inc 0))
  {:fx/type :label :text (fx/sub-val context :n)})

(defn- fixture-parent
  "The fixture's root view: it reads :other, and shows fixture-child."
  [{:keys [fx/context]}]
  (swap! view-calls update :parent (fnil inc 0))
  (fx/sub-val context :other)
  {:fx/type fixture-child})

(deftest child-context-and-logger-survive-advance-test
  (reset! view-calls {})
  (let [renderer-errors (atom [])
        agent-errors (atom [])
        c0 (fx/create-context {:n "a" :other 0})
        ctx-atom (atom c0)]
    (with-redefs [state/get-context-atom (constantly ctx-atom)
                  root/root-view fixture-parent
                  app/default-error-handler #(swap! renderer-errors conj %)
                  app/agent-error-handler (fn [_ e] (swap! agent-errors conj e))]
      (let [{:keys [renderer]} (#'app/create-app)
            render! (fn [ctx]
                      (let [component (deref (renderer ctx) 5000 ::timed-out)]
                        (is (not= ::timed-out component) "rendered within 5 s")
                        component))
            shown (fn [component]
                    @(fx/on-fx-thread
                      (let [^Label label (fx/instance component)]
                        {:label label :text (.getText label)})))
            child-calls #(:child @view-calls 0)]
        (try
          ;; Each context derived after the render before, as the app's
          ;; writes are: a derived context starts from its parent's cache
          (let [a (shown (render! c0))
                calls-a (child-calls)
                c1 (fx/swap-context c0 assoc :other 1)
                a' (shown (render! c1))
                calls-a' (child-calls)
                c2 (fx/swap-context c1 assoc :n "b")
                b (shown (render! c2))
                calls-b (child-calls)]
            (testing "the child runs when what it reads changes, not for its parent's"
              (is (= [1 1 2] [calls-a calls-a' calls-b]))
              (is (= 2 (:parent @view-calls)) "the parent ran for :other"))
            (is (= ["a" "a" "b"] (map :text [a a' b])))
            (is (identical? (:label a) (:label b)) "the label is advanced, not made again")
            (testing "a render that throws: the app's renderer error handler gets it"
              (render! (fx/swap-context c2 assoc :n 17))
              (is (= 1 (count @renderer-errors)))
              (is (= [] @agent-errors))
              (when (dev-config/dev-mode?)
                (testing "dev mode: cljfx.dev says what is wrong, and where"
                  (let [message (str (some-> (first @renderer-errors) ex-message))]
                    (is (str/includes? message "Invalid cljfx description of :label"))
                    (is (str/includes? message "in [:text]"))
                    (is (str/includes? message "Cljfx component stack:"))
                    (is (str/includes? message "laser-show.renderer-wiring-test/fixture-child")))))))
          (finally
            (is (not= ::timed-out (deref (fx/unmount-renderer ctx-atom renderer) 5000 ::timed-out))
                "unmounted")))))
    (when (dev-config/dev-mode?)
      (testing "cljfx.dev's specs as laser-show.dev-renderer corrects them"
        (testing "an ext-many child's :fx/key is no prop; its props are still checked"
          (is (s/valid? :cljfx.ext-many/desc [{:fx/type :label :fx/key :a :text "x"}]))
          (is (not (s/valid? :cljfx.ext-many/desc [{:fx/type :label :fx/key :a :text 17}])))
          (is (not (s/valid? :cljfx.ext-many/desc [{:fx/type :label :fx/key :a :txt "x"}])))
          (is (not (s/valid? :cljfx.ext-many/desc [:label]))))
        (testing "a menu item's and a split menu button's classes: a string or strings"
          (doseq [[spec type classes] [[:cljfx.composite.menu-item/style-class :menu-item
                                        ["menu-item" "target-menu-header"]]
                                       [:cljfx.composite.split-menu-button/style-class :split-menu-button
                                        ["split-menu-button" "step-grid-add-row"]]]]
            (is (s/valid? spec (first classes)))
            (is (s/valid? spec classes))
            (is (not (s/valid? spec [(first classes) 2])))
            (is (not (s/valid? spec 2)))
            (is (s/valid? :cljfx/desc {:fx/type type :text "Add" :style-class classes}))
            (is (not (s/valid? :cljfx/desc {:fx/type type :text 7 :style-class classes}))
                "its other props are still checked")))))))
