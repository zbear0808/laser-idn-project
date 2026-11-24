;; Fresh headless JVM; same command as cljfx_reactivity_2026_11_23.clj with
;; this filename. Only in-memory data/ZIP streams and the headless test app.
(ns audit.cljfx-state-outcomes
  (:require [cljfx.api :as fx]
            [clojure.java.io :as io]
            [laser-show.app :as app]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.chain.params :as params]
            [laser-show.events.handlers.modulator :as modulator]
            [laser-show.events.handlers.timing :as timing]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.state.persistent :as persistent]
            [laser-show.state.serialization :as serialization]
            [laser-show.views.components.modulator-param-control :as control])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream File]
           [javafx.application Platform]
           [javafx.event ActionEvent Event]
           [javafx.scene Node]
           [javafx.scene.control TextField]
           [javafx.scene.input KeyCode KeyEvent]
           [javafx.stage Window]))

(defn- visible-node [selector]
  @(fx/on-fx-thread
    (some (fn [^Window w]
            (when (.isShowing w) (some-> w .getScene .getRoot (.lookup selector))))
          (Window/getWindows))))

(defn- wait-for [f]
  (let [end (+ (System/nanoTime) 5000000000)]
    (loop []
      (or (f) (when (< (System/nanoTime) end) (Thread/sleep 30) (recur))))))

(defn- open-editor [col]
  (events/dispatch! {:event/type :effect-chain/open-editor :col col :row 0})
  (assert (events/flush!))
  (or (wait-for #(visible-node (str "#list-editor-effect-chain-" col "-0")))
      (throw (ex-info "Effect list did not appear" {:col col :dialog (get-in (state/get-raw-state) [:ui :dialogs :effect-chain-editor])}))))

(defn- counts [] (mapv #(count (get-in (state/get-raw-state) [:chains :effect-chains [% 0] :items])) [0 1]))

(defn- numeric-round-trip []
  (let [amount {:type :sine :active? false :value 0.2 :min 0.0 :max 1.0}
        props {:current-value amount :param-key :amount :param-spec {:type :float :min 0.0 :max 1.0 :default 1.0}
               :modulator-event-base {:domain :effect-chains :entity-key [0 0] :effect-path [0]}}
        field (first (filter #(= :text-field (:fx/type %)) (tree-seq map? :children (control/modulator-param-control props))))
        node @(fx/on-fx-thread (TextField. "0.42"))
        change (:dispatch (modulator/handle (assoc (:on-action field) :fx/event (ActionEvent. node node))))
        items-path [:chains :effect-chains [0 0] :items]
        before (-> (domains/build-initial-state)
                   (assoc-in [:chains :cue-chains [0 0]] {:name "Example" :items [{:type :preset :preset-id :circle}]})
                   (assoc-in items-path [{:type :effect :effect-id :intensity :params {:amount amount}}]))
        after (params/handle-update-param before {:items-path items-path} change)
        stored (get-in after (into items-path [0 :params :amount :value]))
        zip (ByteArrayOutputStream.)
        saved (serialization/save-to-zip! zip (persistent/project-files after) :create-dirs? false)
        ;; load-from-zip checks File.exists before opening the stream. Both the
        ;; existence check and bytes are in memory; no project file is touched.
        exists (proxy [File] ["in-memory"] (exists [] true))
        entries (with-redefs [io/input-stream (fn [& _] (ByteArrayInputStream. (.toByteArray zip)))]
                  (serialization/load-from-zip exists))
        ;; After the strict-open repair an unreadable show throws instead of
        ;; opening empty; record that outcome so the later sections still run.
        reopened (try (persistent/apply-loaded-project before entries "in-memory.zip")
                      (catch Exception e {::reopen-error (ex-message e)}))]
    {:stored (some-> stored class .getSimpleName) :saved? saved
     :cue-count-before (count (get-in before [:chains :cue-chains]))
     :parsed-content? (map? (get entries (:content persistent/zip-filenames)))
     :reopen-error (::reopen-error reopened)
     :cue-count-reopened (when-not (::reopen-error reopened) (count (get-in reopened [:chains :cue-chains])))
     :dirty-on-open? (get-in reopened [:project :dirty?])}))

(defn- stopped-transport []
  (let [intents {[0 0] {:run :timeline :timeline? true} [1 0] {:run :ordinary}}
        stopped (:state (timing/handle {:event/type :transport/stop :state {:playback {:playing? true :active-cues intents}}}))
        ended (:state (timing/handle {:event/type :show-clock/cue-ended :cell [0 0] :run :timeline :state stopped}))]
    {:playing-after-stop? (get-in stopped [:playback :playing?]) :playing-after-report? (get-in ended [:playback :playing?])}))

(try
  (Platform/setImplicitExit false)
  ;; The repaired numeric field may no longer expose this :on-action template.
  (prn :numeric-round-trip (try (numeric-round-trip) (catch Exception e {:probe-error (ex-message e)})))
  (prn :transport (stopped-transport))
  (let [item (fn [] {:id (random-uuid) :type :effect :effect-id :intensity :enabled? true :params {:amount 0.75}})
        s (reduce (fn [s col] (assoc-in s [:chains :effect-chains [col 0]] {:name (str "Cell " col) :items [(item)]}))
                  (#'app/starter-state) [0 1])]
    (app/start-test! s)
    (let [a (open-editor 0) ^Node b (open-editor 1)]
      @(fx/on-fx-thread (.requestFocus (.getWindow (.getScene b))) (.requestFocus b))
      (assert (wait-for #(deref (fx/on-fx-thread (.isFocusWithin b)))))
      (let [before (counts) visible-id @(fx/on-fx-thread (.getId b))]
        @(fx/on-fx-thread (Event/fireEvent b (KeyEvent. KeyEvent/KEY_PRESSED "" "" KeyCode/DELETE false false false false)))
        (assert (events/flush!))
        (prn :same-node (identical? a b) :focused-list visible-id :items-in-A-and-B-before before :after (counts)))))
  (finally (app/stop!) (Platform/exit) (shutdown-agents)))
