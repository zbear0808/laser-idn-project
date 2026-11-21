(ns laser-show.ui-test.projector-drivers-test
  "Projectors with another output driver than IDN (plans/2026-11-19-output-
   drivers D2): the Add Projector dialog's Type and driver fields, a
   projector's driver settings, one whose driver isn't installed, and what
   the output driver says on the projector's card, through the real output
   runtime. The LSPS tests stream to the loopback receiver
   (laser-show.output.lsps-receiver); the others to a recording driver
   (laser-show.output.recording-driver). An add-on's driver comes from a real
   jar (laser-show.output.jar-builder) in a temp add-ons folder. Nothing
   reaches a real device or ~/LaserShowProjects."
  {:clj-kondo/config '{:lint-as {laser-show.output.lsps-receiver/with-receiver clojure.core/let}}}
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [laser-show.app :as app]
            [laser-show.backend.output-runtime :as output-runtime]
            [laser-show.events.core :as events]
            [laser-show.output.driver :as driver]
            [laser-show.output.jar-builder :as jar]
            [laser-show.output.lsps-receiver :as rx]
            [laser-show.output.recording-driver :as rd]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui]))

(defn- drain-after [f]
  (try
    (f)
    (finally
      (output-runtime/drain! 2000)
      (output-runtime/open!))))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app drain-after)

(defn- app-state []
  (state/get-raw-state))

(defn- window-showing? [title]
  (boolean (some #{title} (ui/window-titles))))

(defn- loaded!
  "Put the registered drivers' descriptors into app state, as startup does
   (:addons/loaded), and wait for them."
  []
  (events/dispatch! {:event/type :addons/loaded :addons [] :drivers (driver/descriptors)})
  (ui/wait-for! #(= (set (keys (driver/descriptors)))
                    (set (keys (get-in (app-state) [:backend :output :drivers]))))))

(defn- with-driver
  "Run f with rec's driver registered and its descriptor in app state."
  [rec f]
  (driver/register! (:driver rec))
  (try
    (loaded!)
    (f)
    (finally
      (output-runtime/drain! 2000)
      (output-runtime/open!)
      (driver/unregister! (:id (:driver rec))))))

(def ^:private bridge
  {:name "Bridge" :driver :lsps :driver-config {:host "127.0.0.1" :port 7266 :max-packet-bytes 1400}
   :stable-id [:manual :lsps :bridge] :zone-groups [] :exclude-from-all? false :tags #{}
   :enabled? true :scan-rate 25000})

(defn- open-add-manual! [robot]
  (ui/click! robot "#projectors-tab")
  (ui/click! robot "#projectors-add-manual-button")
  (ui/wait-for! #(window-showing? "Add Projector")))

(defn- added-lsps []
  (some (fn [[id p]] (when (= :lsps (:driver p)) [id p])) (:projectors (app-state))))

(defn- id-text [id] (name id))

(defn- card-address
  "What a card says of where its laser is: its address line, and the line
   under it with another driver's settings."
  [id]
  (str/join " · " (keep #(ui/node-text (str % (id-text id))) ["#rig-card-address-" "#rig-card-address-detail-"])))


;; The Add Projector dialog

(deftest add-lsps-projector-test
  (let [robot (ui/robot)]
    (open-add-manual! robot)
    (ui/select! robot "#add-projector-driver-combo" ":lsps")
    (ui/wait-for! #(= :lsps (get-in (app-state) [:ui :dialogs :add-projector-manual :driver])))
    (ui/wait-for! #(some? (ui/query "#driver-field-host")))
    (ui/assert-text! "#driver-field-port" "7266")
    (ui/assert-text! "#driver-field-max-packet-bytes" "1400")
    (ui/fill! robot "#driver-field-host" "127.0.0.1")
    (ui/fill! robot "#add-projector-name-field" "Bridge")
    (ui/wait-for! #(ui/enabled? "#add-projector-confirm-button"))
    (ui/click! robot "#add-projector-confirm-button")
    (ui/wait-for! #(some? (added-lsps)))
    (let [[id p] (added-lsps)]
      (is (= {:name "Bridge" :driver :lsps :driver-config {:host "127.0.0.1" :port 7266 :max-packet-bytes 1400}
              :stable-id [:manual :lsps id] :enabled? true}
             (select-keys p [:name :driver :driver-config :stable-id :enabled?])))
      (ui/wait-for! #(not (window-showing? "Add Projector")))
      (ui/wait-for! #(= "LSPS · 127.0.0.1:7266" (card-address id)))
      (testing "its settings: the driver, read only, and its fields"
        (ui/assert-text! "#projector-driver-label" "LSPS point stream (UDP)")
        (ui/assert-text! "#driver-field-host" "127.0.0.1")
        (is (nil? (ui/query "#projector-host-field")) "no IDN host field")
        (ui/assert-text! "#projector-identity-label" "Identity: Added by hand: a scan doesn't look for it")))))

(deftest idn-fields-unchanged-test
  (let [robot (ui/robot)]
    (open-add-manual! robot)
    (ui/assert-text! "#add-projector-intro" "Choose the output type and enter its connection details.")
    (is (= :idn (ui/on-fx-thread #(.getValue ^javafx.scene.control.ComboBox (ui/query "#add-projector-driver-combo"))))
        "IDN first, as before")
    (is (= [:idn :lsps] (ui/on-fx-thread #(vec (.getItems ^javafx.scene.control.ComboBox (ui/query "#add-projector-driver-combo"))))))
    (ui/assert-visible! "#add-projector-host-field")
    (ui/assert-text! "#add-projector-port-field" "7255")
    (is (nil? (ui/query "#driver-field-host")))
    (ui/assert-disabled! "#add-projector-confirm-button")
    (testing "another type has its own fields; IDN's come back"
      (ui/select! robot "#add-projector-driver-combo" ":lsps")
      (ui/wait-for! #(some? (ui/query "#driver-field-host")))
      (is (nil? (ui/query "#add-projector-host-field")))
      (ui/select! robot "#add-projector-driver-combo" ":idn")
      (ui/wait-for! #(some? (ui/query "#add-projector-host-field"))))
    (ui/fill! robot "#add-projector-host-field" "10.0.0.5")
    (ui/fill! robot "#add-projector-port-field" "7300")
    (ui/wait-for! #(ui/enabled? "#add-projector-confirm-button"))
    (ui/click! robot "#add-projector-confirm-button")
    (ui/wait-for! #(some (fn [p] (= "10.0.0.5" (:host p))) (vals (:projectors (app-state)))))
    (let [p (some (fn [p] (when (= "10.0.0.5" (:host p)) p)) (vals (:projectors (app-state))))]
      (is (= {:host "10.0.0.5" :port 7300 :stable-id [:host "10.0.0.5" 0]} (select-keys p [:host :port :stable-id])))
      (is (not (contains? p :driver)) "an IDN projector as it always was"))))

(defn- combo-items []
  (ui/on-fx-thread #(vec (.getItems ^javafx.scene.control.ComboBox (ui/query "#add-projector-driver-combo")))))

(defn- left-x
  "A node's left edge in its scene, by its layout (a focus ring left out)."
  [selector]
  (let [^javafx.scene.Node n (ui/query selector)]
    (ui/on-fx-thread #(.getMinX (.localToScene n (.getLayoutBounds n))))))

(defn- grid-labels
  "{text left-edge} of the labels (\"Type:\", \"Name:\" …) in the dialog's grid."
  []
  (ui/on-fx-thread
   #(into {}
          (keep (fn [n]
                  (when (and (instance? javafx.scene.control.Label n)
                             (str/ends-with? (str (.getText ^javafx.scene.control.Label n)) ":"))
                    [(.getText ^javafx.scene.control.Label n)
                     (.getMinX (.localToScene ^javafx.scene.Node n (.getLayoutBounds ^javafx.scene.Node n)))])))
          (.getChildrenUnmodifiable ^javafx.scene.Parent (ui/query "#add-projector-grid")))))

(defn- right-x
  "A node's right edge in its scene, by its layout."
  [selector]
  (let [^javafx.scene.Node n (ui/query selector)]
    (ui/on-fx-thread #(.getMaxX (.localToScene n (.getLayoutBounds n))))))

(def ^:private choice-descriptor
  "A driver with a text field and a choice, for the grid's widths."
  {:id :test/choice :name "Choice test" :api 1 :transport :other
   :fields [{:key :host :label "Host" :type :host :required? true}
            {:key :order :label "Session tag byte order" :type :choice :options [[:be "BE"] [:le "LE"]] :default :be}]
   :open (fn [_ _] {}) :send-frame! (fn [_ _]) :close! (fn [_])})

(deftest add-manual-fields-share-one-grid-test
  (let [robot (ui/robot)]
    (open-add-manual! robot)
    (testing "IDN: Type, Name, Host and Port in one column"
      (ui/wait-for! #(some? (ui/query "#add-projector-grid")))
      (is (= 1 (count (set (map left-x ["#add-projector-driver-combo" "#add-projector-name-field"
                                        "#add-projector-host-field" "#add-projector-port-field"])))))
      (is (= #{"Type:" "Name:" "Host:" "Port:"} (set (keys (grid-labels)))))
      (is (= 1 (count (set (vals (grid-labels))))) "and their labels in one column"))
    (testing "another driver: its fields in the same column as Type and Name"
      (ui/select! robot "#add-projector-driver-combo" ":lsps")
      (ui/wait-for! #(some? (ui/query "#driver-field-max-packet-bytes")))
      (is (= 1 (count (set (map left-x ["#add-projector-driver-combo" "#add-projector-name-field"
                                        "#driver-field-host" "#driver-field-port" "#driver-field-max-packet-bytes"])))))
      (is (= #{"Type:" "Name:" "Host:" "Port:" "Max packet size (bytes):"} (set (keys (grid-labels)))))
      (is (= 1 (count (set (vals (grid-labels))))) "and their labels in one column"))
    (testing "a choice is as wide as the column, as the text fields and the Type combo are"
      (driver/register! choice-descriptor)
      (try
        (loaded!)
        (ui/select! robot "#add-projector-driver-combo" ":test/choice")
        (ui/wait-for! #(some? (ui/query "#driver-field-order")))
        (is (= 1 (count (set (map left-x ["#add-projector-driver-combo" "#add-projector-name-field"
                                          "#driver-field-host" "#driver-field-order"])))))
        (is (= 1 (count (set (map right-x ["#add-projector-driver-combo" "#add-projector-name-field"
                                           "#driver-field-host" "#driver-field-order"]))))
            "every control ends where the column does")
        (finally
          (driver/unregister! :test/choice))))))

(deftest unnamed-projector-gets-a-short-name-test
  (let [robot (ui/robot)]
    (open-add-manual! robot)
    (ui/select! robot "#add-projector-driver-combo" ":lsps")
    (ui/wait-for! #(some? (ui/query "#driver-field-host")))
    (ui/fill! robot "#driver-field-host" "127.0.0.1")
    (ui/fill! robot "#driver-field-port" "59933")
    (ui/wait-for! #(ui/enabled? "#add-projector-confirm-button"))
    (ui/click! robot "#add-projector-confirm-button")
    (ui/wait-for! #(some? (added-lsps)))
    (let [[id p] (added-lsps)]
      (is (= "LSPS 1" (:name p)) "its driver's short name and a number, not its settings again")
      (ui/assert-text! (str "#rig-card-name-" (id-text id)) "LSPS 1")
      (is (= "LSPS · 127.0.0.1:59933" (card-address id)) "the settings are said once, under it"))))

(deftest card-title-shows-the-whole-name-in-its-tooltip-test
  (let [long-name "Stage left upper truss, beam 3"]
    (state/swap-state! assoc :projectors {:bridge (assoc bridge :name long-name)})
    (ui/click! (ui/robot) "#projectors-tab")
    (ui/wait-for! #(some? (ui/query "#rig-card-name-bridge")))
    (let [title (ui/query "#rig-card-name-bridge")]
      (is (not= long-name (ui/shown-text title)) "cut off on the card")
      (is (= long-name (ui/on-fx-thread #(some-> (.getTooltip ^javafx.scene.control.Label title) .getText)))
          "and whole in its tooltip"))))

(deftest addon-driver-is-in-the-type-combo-test
  (let [dir (jar/temp-dir! "laser-show-d2-addons")
        addons-dir (io/file dir "addons")]
    (try
      (jar/build! addons-dir "udp")
      (jar/build! addons-dir "broken")
      (app/start-test! nil {:addons-dir addons-dir :addons-platform {:os :macos :arch :aarch64}})
      (ui/sync-fx!)
      (ui/wait-for! #(= #{:test/udp :test/broken}
                        (set (keep :id (get-in (app-state) [:backend :output :addons])))))
      (is (= {:test/udp :loaded :test/broken :failed}
             (into {} (map (juxt :id :state)) (get-in (app-state) [:backend :output :addons])))
          "one jar loaded, the other failed")
      (let [robot (ui/robot)]
        (open-add-manual! robot)
        (is (= [:idn :lsps :test/udp-stream] (combo-items))
            "the add-on's driver is offered, after IDN, by name; the broken jar's driver isn't")
        (is (nil? (driver/lookup :test/sample-stream)) "nothing of the broken add-on was registered")
        (ui/select! robot "#add-projector-driver-combo" ":test/udp-stream")
        (ui/wait-for! #(some? (ui/query "#driver-field-port")))
        (ui/assert-text! "#driver-field-host" "127.0.0.1")
        (ui/assert-text! "#driver-field-port" "")
        (ui/assert-disabled! "#add-projector-confirm-button")
        (is (= ["UDP test stream"]
               (ui/on-fx-thread #(vector (.getText (.getButtonCell ^javafx.scene.control.ComboBox (ui/query "#add-projector-driver-combo"))))))
            "shown by its name")
        (ui/fill! robot "#driver-field-port" "9")
        (ui/wait-for! #(ui/enabled? "#add-projector-confirm-button")))
      (finally
        (app/stop!)
        (jar/delete-tree! dir)))))

(deftest add-button-disabled-until-fields-valid-test
  (let [robot (ui/robot)]
    (open-add-manual! robot)
    (ui/select! robot "#add-projector-driver-combo" ":lsps")
    (ui/wait-for! #(some? (ui/query "#driver-field-host")))
    (ui/assert-disabled! "#add-projector-confirm-button")
    (is (nil? (ui/query "#driver-field-host-error")) "no complaint before anything is typed")
    (ui/fill! robot "#driver-field-host" "127.0.0.1")
    (ui/wait-for! #(ui/enabled? "#add-projector-confirm-button"))
    (ui/fill! robot "#driver-field-port" "99999")
    (ui/wait-for! #(not (ui/enabled? "#add-projector-confirm-button")))
    (ui/assert-text! "#driver-field-port-error" "Port must be 1–65535")
    (ui/fill! robot "#driver-field-port" "abc")
    (ui/assert-text! "#driver-field-port-error" "Port must be a whole number")
    (ui/fill! robot "#driver-field-port" "7300")
    (ui/wait-for! #(ui/enabled? "#add-projector-confirm-button"))
    (ui/wait-for! #(nil? (ui/query "#driver-field-port-error")))
    (ui/fill! robot "#driver-field-host" "  ")
    (ui/wait-for! #(not (ui/enabled? "#add-projector-confirm-button")))
    (is (nil? (added-lsps)) "nothing was added")))


;; A projector's driver settings

(deftest projector-settings-share-one-grid-test
  (let [robot (ui/robot)]
    (state/swap-state! #(-> % (assoc :projectors {:bridge bridge
                                                  :idn {:name "Laser" :host "10.0.0.5" :port 7255 :enabled? false
                                                        :stable-id [:host "10.0.0.5" 0] :zone-groups []}})
                            (assoc-in [:projector-ui :active-projector] :bridge)))
    (ui/click! robot "#projectors-tab")
    (testing "another driver: Name, Type, its fields and the scan rate in one column"
      (ui/wait-for! #(some? (ui/query "#driver-field-max-packet-bytes")))
      (is (= 1 (count (set (map left-x ["#projector-name-field" "#projector-driver-label" "#driver-field-host"
                                        "#driver-field-port" "#driver-field-max-packet-bytes" "#projector-scan-rate-field"]))))))
    (testing "IDN: Name, Type, Host, Port and the scan rate in one column"
      (events/dispatch! {:event/type :projectors/select-projector :projector-id :idn})
      (ui/wait-for! #(some? (ui/query "#projector-host-field")))
      (is (= 1 (count (set (map left-x ["#projector-name-field" "#projector-driver-label" "#projector-host-field"
                                        "#projector-port-field" "#projector-scan-rate-field"]))))))))
(deftest edit-lsps-port-test
  (let [robot (ui/robot)
        config #(get-in (app-state) [:projectors :bridge :driver-config])]
    (state/swap-state! #(-> % (assoc :projectors {:bridge bridge}) (assoc-in [:projector-ui :active-projector] :bridge)))
    (ui/click! robot "#projectors-tab")
    (ui/assert-text! "#projector-driver-label" "LSPS point stream (UDP)")
    (ui/assert-text! "#driver-field-port" "7266")
    (ui/wait-for! #(= "LSPS · 127.0.0.1:7266" (card-address :bridge)))
    (ui/fill! robot "#driver-field-port" "99999")
    (ui/submit! robot "#driver-field-port")
    (ui/assert-text! "#driver-field-port" "7266")
    (is (= 7266 (:port (config))) "a port out of range is put back, not applied")
    (ui/fill! robot "#driver-field-port" "7300")
    (ui/sync-fx!)
    (is (= 7266 (:port (config))) "typing alone applies nothing")
    (ui/submit! robot "#driver-field-port")
    (ui/wait-for! #(= 7300 (:port (config))))
    (ui/wait-for! #(= "LSPS · 127.0.0.1:7300" (card-address :bridge)))
    (testing "the host, on focus loss"
      (ui/fill! robot "#driver-field-host" "10.0.0.20")
      (ui/focus! robot "#driver-field-port")
      (ui/wait-for! #(= "10.0.0.20" (:host (config))))
      (ui/wait-for! #(= "LSPS · 10.0.0.20:7300" (card-address :bridge))))
    (testing "the scan rate keeps to the driver's range"
      (ui/fill! robot "#projector-scan-rate-field" "500")
      (ui/submit! robot "#projector-scan-rate-field")
      (ui/wait-for! #(= 1000 (get-in (app-state) [:projectors :bridge :scan-rate]))))
    (is (= :lsps (get-in (app-state) [:projectors :bridge :driver])))))

(deftest undo-shortcut-in-a-committed-field-undoes-the-edit-test
  ;; Enter applies the port and leaves the focus in the field, where ⌘Z (Ctrl+Z)
  ;; was the text field's own undo: the field went back, the projector didn't
  (let [robot (ui/robot)
        config #(get-in (app-state) [:projectors :bridge :driver-config])
        chord! (fn [& keys] (.push robot (into-array javafx.scene.input.KeyCode keys)) (ui/sync-fx!))]
    (state/swap-state! #(-> % (assoc :projectors {:bridge bridge}) (assoc-in [:projector-ui :active-projector] :bridge)))
    (ui/click! robot "#projectors-tab")
    (ui/assert-text! "#driver-field-port" "7266")
    (ui/fill! robot "#driver-field-port" "7300")
    (ui/submit! robot "#driver-field-port")
    (ui/wait-for! #(= 7300 (:port (config))))
    (ui/focus! robot "#driver-field-port")
    (chord! (ui/shortcut-key-code) javafx.scene.input.KeyCode/Z)
    (ui/wait-for! #(= 7266 (:port (config))))
    (ui/assert-text! "#driver-field-port" "7266")
    (testing "and the redo shortcut applies it again"
      (chord! (ui/shortcut-key-code) javafx.scene.input.KeyCode/SHIFT javafx.scene.input.KeyCode/Z)
      (ui/wait-for! #(= 7300 (:port (config))))
      (ui/assert-text! "#driver-field-port" "7300"))
    (testing "while typing (not yet applied), it is the field's own undo"
      (ui/type! robot "#driver-field-port" "12")
      (chord! (ui/shortcut-key-code) javafx.scene.input.KeyCode/Z)
      (ui/sync-fx!)
      (is (= 7300 (:port (config))) "nothing applied or undone in the show")
      (is (not= "12" (ui/node-text "#driver-field-port")) "the typing is undone in the field"))))

(defn- tooltip-of [selector]
  (ui/on-fx-thread #(some-> ^javafx.scene.control.Control (ui/query selector) .getTooltip .getText)))

(defn- fits-its-card?
  "The whole of the text of the label selector finds shows (no \"…\"),
   inside card id's width."
  [selector id]
  (let [node (ui/query selector)
        [cx0 _ cx1 _] (ui/scene-bounds (ui/query (str "#rig-card-" (id-text id))))
        [x0 _ x1 _] (ui/scene-bounds node)]
    (and (= (ui/node-text selector) (ui/shown-text node)) (<= cx0 x0) (<= x1 cx1))))

(defn- assert-fits! [selector id]
  (is (try (ui/wait-for! #(fits-its-card? selector id)) (catch Exception _ false))
      (str selector " " (pr-str (ui/node-text selector)) " shows as " (pr-str (some-> (ui/query selector) ui/shown-text))
           ", within its card")))

(deftest show-lock-refuses-a-driver-config-edit-test
  (let [robot (ui/robot)
        config #(get-in (app-state) [:projectors :bridge :driver-config])]
    (state/swap-state! #(-> % (assoc :projectors {:bridge bridge}) (assoc-in [:projector-ui :active-projector] :bridge)))
    (events/dispatch! {:event/type :vibe/set-show-lock :locked? true})
    (ui/wait-for! #(true? (get-in (app-state) [:ui :show-lock?])))
    (ui/click! robot "#projectors-tab")
    (ui/assert-text! "#driver-field-port" "7266")
    (ui/fill! robot "#driver-field-port" "7300")
    (ui/submit! robot "#driver-field-port")
    (ui/assert-text! "#status-notice-text" "Show lock is on: Change Output Settings refused. Unlock it on the Perform tab")
    (is (= 7266 (:port (config))) "refused")
    (ui/assert-text! "#driver-field-port" "7266")
    (testing "the field shows what applies, so leaving it sends nothing more"
      (let [notice (get-in (app-state) [:ui :notice])]
        (ui/focus! robot "#driver-field-host")
        (ui/sync-fx!)
        (Thread/sleep 300)
        (is (= notice (get-in (app-state) [:ui :notice])))))
    (testing "unlocked, the same edit applies"
      (events/dispatch! {:event/type :vibe/set-show-lock :locked? false})
      (ui/fill! robot "#driver-field-port" "7300")
      (ui/submit! robot "#driver-field-port")
      (ui/wait-for! #(= 7300 (:port (config))))
      (ui/assert-text! "#driver-field-port" "7300"))))

(deftest show-lock-refuses-a-driver-choice-or-check-box-test
  (let [robot (ui/robot)
        config #(get-in (app-state) [:projectors :choice :driver-config])
        combo-value #(ui/on-fx-thread (fn [] (.getValue ^javafx.scene.control.ComboBox (ui/query "#driver-field-order"))))
        checked? #(ui/selected? "#driver-field-fast")]
    (driver/register! (update choice-descriptor :fields conj {:key :fast :label "Fast" :type :boolean}))
    (try
      (loaded!)
      (state/swap-state! #(-> % (assoc :projectors {:choice {:name "Choice" :driver :test/choice :driver-config {:order :be}
                                                             :stable-id [:manual :test/choice :choice] :zone-groups []
                                                             :enabled? false :scan-rate 25000}})
                              (assoc-in [:projector-ui :active-projector] :choice)))
      (events/dispatch! {:event/type :vibe/set-show-lock :locked? true})
      (ui/wait-for! #(true? (get-in (app-state) [:ui :show-lock?])))
      (ui/click! robot "#projectors-tab")
      (ui/wait-for! #(some? (ui/query "#driver-field-order")))
      (ui/select! robot "#driver-field-order" ":le")
      (ui/assert-text! "#status-notice-text" "Show lock is on: Change Output Settings refused. Unlock it on the Perform tab")
      (ui/wait-for! #(= :be (combo-value)))
      (is (= {:order :be} (config)))
      (ui/click! robot "#driver-field-fast")
      (ui/wait-for! #(not (checked?)))
      (is (= {:order :be} (config)) "refused, and the box shows it isn't on")
      (testing "unlocked, they apply"
        (events/dispatch! {:event/type :vibe/set-show-lock :locked? false})
        (ui/select! robot "#driver-field-order" ":le")
        (ui/wait-for! #(= :le (:order (config))))
        (ui/click! robot "#driver-field-fast")
        (ui/wait-for! #(true? (:fast (config))))
        (Thread/sleep 400)
        (is (= :le (combo-value)))
        (is (checked?)))
      (finally
        (driver/unregister! :test/choice)))))

(deftest missing-driver-shows-warning-test
  (let [robot (ui/robot)
        nova {:name "Nova" :driver :acme/nova :driver-config {:serial "NV-00123" :output 0}
             :stable-id [:device :acme/nova "NV-00123" 0] :zone-groups [] :exclude-from-all? false
             :enabled? true :scan-rate 30000}]
    (state/swap-state! #(-> % (assoc :projectors {:nova nova}) (assoc-in [:projector-ui :active-projector] :nova)))
    (ui/click! robot "#projectors-tab")
    (ui/assert-text! "#projector-driver-missing" "Output driver :acme/nova is not installed. Its settings are kept.")
    (ui/assert-text! "#projector-driver-config-text" "Serial: NV-00123\nOutput: 0")
    (is (nil? (ui/query "#driver-field-serial")) "nothing to edit")
    (ui/assert-text! "#projector-driver-label" "acme/nova")
    (ui/wait-for! #(= "acme/nova · not installed" (card-address :nova)))
    (ui/assert-text! "#projector-identity-label" "Identity: Device NV-00123 · output 0")
    (testing "streaming, its card says why it is dark"
      (ui/click! robot "#stream-btn")
      (ui/assert-text! "#projector-status-nova" "Driver not installed")
      (assert-fits! "#projector-status-nova" :nova)
      (is (= "Output driver :acme/nova is not installed" (tooltip-of "#projector-status-nova")) "the whole message in its tooltip")
      (ui/click! robot "#stream-btn")
      (ui/wait-for! #(nil? (ui/query "#projector-status-nova"))))
    (is (= nova (get-in (app-state) [:projectors :nova])) "kept as it was")))


;; What the output driver says, on the card (through the real output runtime)

(defn- driver-projector [driver-id]
  {:name (str "Driver " (name driver-id)) :driver driver-id :driver-config {}
   :stable-id [:manual driver-id :rec] :zone-groups [] :exclude-from-all? false :enabled? true :scan-rate 25000})

(defn- status-text [] (ui/node-text "#projector-status-rec"))

(deftest driver-status-shows-on-card-test
  (let [robot (ui/robot)
        says (atom nil)
        rec (rd/recording-driver {:id :test/status :status (fn [_] @says)})]
    (with-driver rec
      (fn []
        (state/swap-state! assoc :projectors {:rec (driver-projector :test/status)})
        (ui/click! robot "#projectors-tab")
        (ui/click! robot "#stream-btn")
        (ui/wait-for! #(str/starts-with? (str (status-text)) "Streaming"))
        (is (nil? (ui/query "#projector-driver-info-rec")) "a driver that says nothing shows nothing")
        (reset! says {:level :warning :message "Interlock open" :info {:scan-rate 30303 :buffer-ms 40 :fifo [1 2]}})
        (ui/wait-for! #(str/ends-with? (str (status-text)) " · Interlock open"))
        (assert-fits! "#projector-status-rec" :rec)
        (ui/assert-text! "#projector-driver-info-rec" "buffer-ms 40 · scan-rate 30303")
        (assert-fits! "#projector-driver-info-rec" :rec)
        (reset! says {:level :warning :message "Interlock open" :info {:scan-rate 30303 :buffer-ms 40 :firmware "4.1.2"}})
        (ui/assert-text! "#projector-driver-info-rec" "buffer-ms 40 · firmware 4.1.2 · scan-rate 30303")
        (assert-fits! "#projector-driver-info-rec" :rec)
        (reset! says {:level :error :message "E-stop active" :info {:estop? true}})
        (ui/assert-text! "#projector-status-rec" "Error: E-stop active")
        (assert-fits! "#projector-status-rec" :rec)
        (ui/assert-text! "#projector-driver-info-rec" "estop? true")
        (reset! says {:level :ok})
        (ui/wait-for! #(str/starts-with? (str (status-text)) "Streaming"))
        (ui/wait-for! #(nil? (ui/query "#projector-driver-info-rec")))
        (ui/click! robot "#stream-btn")
        (ui/wait-for! #(nil? (ui/query "#projector-status-rec")))))))

(deftest busy-driver-shows-in-use-test
  (let [robot (ui/robot)
        rec (rd/recording-driver {:id :test/busy :fail-open? (constantly {:busy? true :holder "ShowCtl on 10.0.0.3"})})]
    (with-driver rec
      (fn []
        (state/swap-state! assoc :projectors {:rec (driver-projector :test/busy)})
        (ui/click! robot "#projectors-tab")
        (ui/click! robot "#stream-btn")
        (ui/assert-text! "#projector-status-rec" "In use by ShowCtl on 10.0.0.3")
        (assert-fits! "#projector-status-rec" :rec)
        (is (zero? (count (filter #(= :send (first %)) @(:calls rec)))) "nothing is sent to a device it can't open")
        (ui/click! robot "#stream-btn")
        (ui/wait-for! #(nil? (ui/query "#projector-status-rec")))))))

(deftest reopen-error-shows-the-drivers-message-then-recovers-test
  (let [robot (ui/robot)
        failing (atom false)
        rec (rd/recording-driver {:id :test/reopen :fail-send? (fn [_] (when @failing {:reopen? true}))})
        opens #(count (filter (fn [c] (= :open (first c))) @(:calls rec)))]
    (with-driver rec
      (fn []
        (state/swap-state! assoc :projectors {:rec (driver-projector :test/reopen)})
        (ui/click! robot "#projectors-tab")
        (ui/click! robot "#stream-btn")
        (ui/wait-for! #(str/starts-with? (str (status-text)) "Streaming"))
        (reset! failing true)
        (ui/wait-for! #(str/starts-with? (str (status-text)) "Error: "))
        (is (re-matches #"Error: test: send \d+ failed" (str (status-text)))
            "the driver's own message, without the exception's class")
        (assert-fits! "#projector-status-rec" :rec)
        (let [n (opens)]
          (reset! failing false)
          (ui/wait-for! 5000 #(str/starts-with? (str (status-text)) "Streaming"))
          (is (< n (opens)) "a new session was opened"))
        (ui/click! robot "#stream-btn")
        (ui/wait-for! #(nil? (ui/query "#projector-status-rec")))))))

(deftest not-responding-driver-shows-on-card-test
  (let [robot (ui/robot)
        rec (rd/recording-driver {:id :test/stuck :block-send-ms {5 3000}})]
    (with-driver rec
      (fn []
        (state/swap-state! assoc :projectors {:rec (driver-projector :test/stuck)})
        (ui/click! robot "#projectors-tab")
        (ui/click! robot "#stream-btn")
        (ui/assert-text! "#projector-status-rec" "Driver not responding")
        (assert-fits! "#projector-status-rec" :rec)
        (is (= "The output driver is not responding" (tooltip-of "#projector-status-rec")))
        (let [t0 (System/nanoTime)]
          (ui/click! robot "#stream-btn")
          (ui/wait-for! #(and (empty? (output-runtime/registry)) (nil? (ui/query "#projector-status-rec"))))
          (let [ms (/ (- (System/nanoTime) t0) 1e6)]
            (is (< ms 2500) (str "Stop returned in " (long ms) " ms, while the send is still stuck"))))
        (ui/assert-text! "#stream-btn" "Stream")))))


;; Through the real output path to the LSPS receiver

(defn- circle-cue
  "A circle of n points, to All."
  [n]
  {:destination-zone {:zone-group-id :all}
   :items [{:id (random-uuid) :type :preset :preset-id :circle :enabled? true :effects []
            :params {:num-points (dec n)} :zone-group-id :all}]})

(deftest lsps-projector-streams-from-the-ui-test
  (rx/with-receiver [r (rx/start!)]
    (let [robot (ui/robot)
          lit-frames #(filter (fn [f] (pos? (:total f))) (rx/frames (rx/packets r)))]
      (open-add-manual! robot)
      (ui/select! robot "#add-projector-driver-combo" ":lsps")
      (ui/wait-for! #(some? (ui/query "#driver-field-host")))
      (ui/fill! robot "#driver-field-host" "127.0.0.1")
      (ui/fill! robot "#driver-field-port" (str (:port r)))
      (ui/fill! robot "#add-projector-name-field" "Bridge")
      (ui/wait-for! #(ui/enabled? "#add-projector-confirm-button"))
      (ui/click! robot "#add-projector-confirm-button")
      (ui/wait-for! #(some? (added-lsps)))
      (let [[id] (added-lsps)
            status-sel (str "#projector-status-" (id-text id))]
        (state/swap-state! assoc-in [:chains :cue-chains [7 3]] (circle-cue 300))
        (events/dispatch! {:event/type :grid/trigger-cell :col 7 :row 3})
        (ui/wait-for! #(some? (get-in (app-state) [:playback :active-cues [7 3]])))
        (fixtures/with-clock
          (fn [_]
            (ui/click! robot "#stream-btn")
            (ui/wait-for! #(<= 5 (count (lit-frames))))
            (ui/wait-for! #(str/starts-with? (str (ui/node-text status-sel)) "Streaming"))
            (is (= [] (rx/violations (rx/packets r) 1400)) "no L-1…L-3 violations")
            (is (every? #(< 100 (:total %)) (lit-frames)) "the circle's points arrive")
            (ui/click! robot "#stream-btn")
            (ui/wait-for! #(some :close? (rx/packets r)))
            (ui/wait-for! #(nil? (ui/query status-sel)))
            (let [n (count (rx/packets r))]
              (is (= 1 (count (filter :close? (rx/packets r)))) "one CLOSE")
              (Thread/sleep 300)
              (is (= n (count (rx/packets r))) "and nothing after it"))))))))
