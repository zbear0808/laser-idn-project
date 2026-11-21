(ns laser-show.events.handlers.addons
  "Add-on and output driver events (plans/2026-11-19-output-drivers/00 §6).
   Pure. Everything they write is under [:backend :output]: never saved and
   never undone ([:backend] isn't a document root).

   - :addons/loaded: what startup loaded, the drivers and add-ons as data
   - :addons/pick-install → the file chooser → :addons/install-picked →
     the :addons/install effect → :addons/installed or
     :addons/install-failed: Settings → Add-ons → Install add-on…
   - :addons/open-folder → the :system/open-folder effect, and
     :addons/open-folder-failed when the OS can't open it"
  (:require [clojure.string :as str]
            [laser-show.events.helpers :as h])
  (:import [java.io File]))

(defn- handle-loaded
  "Store what startup loaded: :addons, a status per add-on, :drivers,
   every registered driver's descriptor (laser-show.output.driver/descriptors),
   and :dir, the add-ons folder's path. Pending installs were swapped in by
   that load, so none is left."
  [{:keys [addons drivers dir state]}]
  {:state (update-in state [:backend :output] assoc
                     :addons (vec addons)
                     :drivers (or drivers {})
                     :dir dir
                     :pending-installs [])})

(defn- handle-pick-install
  "Install add-on…: a file chooser for a .jar, starting in the home folder."
  [_]
  {:fx/show-file-chooser {:title "Install add-on"
                          :mode :open
                          :initial-directory (System/getProperty "user.home")
                          :extension-filters [{:description "Laser Show add-ons (*.jar)"
                                               :extensions ["*.jar"]}]
                          :on-result {:event/type :addons/install-picked}}})

(defn- handle-install-picked
  "The chooser's result: install that jar into the add-ons folder, or
   nothing when it was cancelled (no :file-path)."
  [{:keys [file-path state]}]
  (if (str/blank? file-path)
    {}
    {:addons/install {:file-path file-path
                      :dir (get-in state [:backend :output :dir])}}))

(defn- addon-title
  "\"Acme Nova 0.3.0\": the descriptor's name (else its id) and version."
  [{:keys [id name version]}]
  (str/join " " (remove str/blank? [(or name (some-> id str)) version])))

(defn- handle-installed
  "The jar is in the folder as <name>.jar.new: say to restart, and list it as
   pending until then (one entry per file, the newest)."
  [{:keys [file replaced? descriptor state]}]
  (let [entry (assoc (select-keys descriptor [:id :name :version]) :file file :replaced? (boolean replaced?))
        text (str (if replaced? "Replaced " "Installed ") (addon-title descriptor)
                  ". Restart Laser Show to load it.")]
    (h/show-notice (update-in state [:backend :output :pending-installs]
                              (fn [pending] (conj (filterv #(not= file (:file %)) pending) entry)))
                   text :info)))

(defn- handle-install-failed
  "The jar wasn't installed: say why."
  [{:keys [file-path error state]}]
  (h/show-notice state (str "Could not install " (.getName (File. (str file-path))) ": " error) :error))

(defn- handle-open-folder
  "Open add-ons folder: open it in the OS's file manager."
  [{:keys [state]}]
  {:system/open-folder {:path (get-in state [:backend :output :dir])
                        :on-failed {:event/type :addons/open-folder-failed}}})

(defn- handle-open-folder-failed
  "The OS couldn't open the folder: say where it is."
  [{:keys [path state]}]
  (h/show-notice state (str "Couldn't open the add-ons folder. It is at " path) :info))

(defn handle
  "Dispatch :addons/* events to their handlers."
  [{:keys [event/type] :as event}]
  (case type
    :addons/loaded (handle-loaded event)
    :addons/pick-install (handle-pick-install event)
    :addons/install-picked (handle-install-picked event)
    :addons/installed (handle-installed event)
    :addons/install-failed (handle-install-failed event)
    :addons/open-folder (handle-open-folder event)
    :addons/open-folder-failed (handle-open-folder-failed event)
    {}))
