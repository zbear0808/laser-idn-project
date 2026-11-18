;; Helpers for the plorer skill. They are defined in the user namespace.
;; Load them once per REPL session, from the repo root:
;;
;;   clj -M:dev .agents/skills/app-repl/nrepl_eval.clj @.agents/skills/plorer/helpers.clj
;;
;; Then, for example: (windows), (ids), (click! "#effects-tab"), (shot!).
(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[cljfx.plorer :as p]
         '[laser-show.state.core])

(import '[com.sun.javafx.tk TKSceneListener]
        '[java.lang.reflect Constructor]
        '[javafx.application Platform]
        '[javafx.scene Node Scene]
        '[javafx.scene.control Tooltip]
        '[javafx.stage PopupWindow Window])

(def shortcut
  "The shortcut modifier key: :meta (Command) on macOS, :control elsewhere."
  (if (str/includes? (System/getProperty "os.name") "Mac") :meta :control))

(defn- class-name [x]
  (let [c (class x)
        n (.getSimpleName c)]
    (if (str/blank? n) (.getSimpleName (.getSuperclass c)) n)))

(declare describe)

(defn- one-match [selector]
  (let [matches (p/all selector)]
    (if (= 1 (count matches))
      (first matches)
      (throw (ex-info (str (count matches) " elements in the open windows match " (pr-str selector)
                           (when (< 1 (count matches))
                             (str ": " (str/join ", " (map describe (take 6 matches))))))
                      {})))))

(defn- node-of ^Node [target]
  (if (instance? Node target) target (one-match target)))

(defn- el-of [target]
  (if (or (instance? Node target) (instance? Scene target) (instance? Window target))
    target
    (one-match target)))

(defn- scene-of ^Scene [target]
  (cond
    (instance? Scene target) target
    (instance? Window target) (.getScene ^Window target)
    :else (.getScene (node-of target))))

(defn- on-fx
  "Run f on the JavaFX thread and return its result."
  [f]
  (if (Platform/isFxApplicationThread)
    (f)
    (let [result (promise)]
      (Platform/runLater #(deliver result (try [:ok (f)] (catch Throwable t [:error t]))))
      (let [[status value] (deref result 5000 [:error (ex-info "Timed out waiting for the JavaFX thread" {})])]
        (if (= :ok status) value (throw value))))))

(defn describe
  "A short description of an element: its class, #id and text, plus (hidden)
   or (disabled) when that applies."
  [el]
  (let [{:keys [id text visible disabled]} (p/props el :only [:id :text :visible :disabled])]
    (str (class-name el)
         (when id (str " #" id))
         (when-not (str/blank? text) (str " " (pr-str text)))
         (when (false? visible) " (hidden)")
         (when disabled " (disabled)"))))

(defn windows
  "Describe the open windows: class, title (stages only) and how many there are."
  []
  (mapv (fn [w]
          (let [{:keys [title]} (p/props w :only [:title])]
            (cond-> {:class (class-name w)}
              title (assoc :title title))))
        (p/all Window)))

(defn main-window
  "The app's main window, the one that holds #main-layout."
  []
  (or (first (filter #(seq (p/all % "#main-layout")) (p/all Window)))
      (throw (ex-info (str "No open window holds #main-layout. Is the app running? Try (laser-show.app/start-test!). Open windows: "
                           (pr-str (windows)))
                      {}))))

(defn window-titled
  "The one open window whose title contains s."
  [s]
  (let [matches (filterv #(some-> (:title (p/props % :only [:title])) (str/includes? s))
                         (p/all Window))]
    (if (= 1 (count matches))
      (first matches)
      (throw (ex-info (str (count matches) " open windows have a title containing " (pr-str s)
                           ". Open windows: " (pr-str (windows)))
                      {})))))

(defn popup
  "The open popup: a menu bar menu, a right-click menu or a combo box list.
   Tooltips don't count."
  []
  (let [popups (filterv #(and (instance? PopupWindow %) (not (instance? Tooltip %)))
                        (p/all Window))]
    (if (= 1 (count popups))
      (first popups)
      (throw (ex-info (str (count popups) " popups are open. Open windows: " (pr-str (windows)))
                      {})))))

(defn by-text
  "The one control (button, label, menu item, check box, ...) whose text is
   exactly text, in root (a window, scene or node; default: every open window)."
  ([text] (by-text nil text))
  ([root text]
   (let [selector {:fx.plorer/class javafx.scene.control.Labeled :text text}
         matches (if root (p/all (el-of root) selector) (p/all selector))]
     (if (= 1 (count matches))
       (first matches)
       (throw (ex-info (str (count matches) " controls have the text " (pr-str text)
                            (when (seq matches) (str ": " (str/join ", " (map describe matches)))))
                       {}))))))

(defn outline
  "Print the scene graph under target (a window, scene, node or selector), one
   element per line, indented by depth. :depth limits the levels printed
   (default 3; nil prints everything)."
  [target & {:keys [depth] :or {depth 3}}]
  (letfn [(walk [{:keys [el children]} level]
            (println (str (apply str (repeat level "  ")) (describe el)))
            (run! #(walk % (inc level)) children))]
    (walk (p/tree (el-of target) :depth depth) 0)))

(def ^:private skin-ids
  "Ids that JavaFX gives the parts inside controls such as combo boxes."
  #{"arrow" "arrow-button" "list-view" "virtual-flow"})

(defn ids
  "Describe every node that has an #id under target (a window, scene, node or
   selector; default: the main window), in scene-graph order, which is roughly
   top to bottom. Leaves out the ids of JavaFX's parts inside combo boxes."
  ([] (ids (main-window)))
  ([target] (mapv describe (p/all (el-of target) {:id #(and % (not (skin-ids %)))}))))

(defn text-of
  "The :text of a node or of the one node a selector matches."
  [target]
  (:text (p/props (node-of target) :only [:text])))

(defn click!
  "Click the centre of target, a node or a selector that matches one node in
   the open windows. The click goes to the node's own window. button is
   :primary (default), :secondary or :middle; a :secondary click opens no
   context menu, so use right-click! for that. Returns a description of the
   node."
  ([target] (click! target :primary))
  ([target button]
   (let [node (node-of target)]
     (p/mouse-click! (.getScene node) (p/point node 0.5 0.5) button)
     (describe node))))

(def ^:private scene-listener
  (delay (doto (.getDeclaredConstructor (Class/forName "javafx.scene.Scene$ScenePeerListener")
                                        (into-array Class [Scene]))
           (.setAccessible true))))

(defn right-click!
  "Right-click the centre of target and request its context menu, as a real
   right-click does. Then use choose! to pick an item."
  [target]
  (let [node (node-of target)
        scene (.getScene node)
        [x y] (p/point node 0.5 0.5)]
    (p/mouse-click! scene [x y] :secondary)
    (on-fx (fn []
             (let [window (.getWindow scene)
                   ^TKSceneListener listener (.newInstance ^Constructor @scene-listener (object-array [scene]))]
               (.menuEvent listener (double x) (double y)
                           (+ (.getX window) (.getX scene) x)
                           (+ (.getY window) (.getY scene) y)
                           false))))
    (describe node)))

(defn double-click!
  "Double-click the centre of target: two clicks in a row at one point, which
   the scene counts as a double-click."
  [target]
  (let [node (node-of target)
        scene (.getScene node)
        point (p/point node 0.5 0.5)]
    (p/mouse-click! scene point :primary)
    (p/mouse-click! scene point :primary)
    (describe node)))

(defn choose!
  "Click the item with exactly this text in the open popup: a menu item, or
   an entry in an open combo box list."
  [text]
  (let [menu (popup)
        items (p/all menu {:fx.plorer/class javafx.scene.control.Labeled :text #(not (str/blank? %))})
        item (or (first (filter #(= text (text-of %)) items))
                 (throw (ex-info (str "The open menu has no item " (pr-str text) ". Its items: "
                                      (str/join ", " (map #(pr-str (text-of %)) items)))
                                 {})))]
    (click! item)))

(def ^:private us-keys
  "Character -> [key shift?] on a US keyboard, as plorer types it."
  (merge
    (into {} (for [c "abcdefghijklmnopqrstuvwxyz"] [c [(keyword (str c)) false]]))
    (into {} (for [c "ABCDEFGHIJKLMNOPQRSTUVWXYZ"] [c [(keyword (str/lower-case c)) true]]))
    (into {} (for [c "0123456789"] [c [(keyword (str "digit" c)) false]]))
    {\space [:space false] \- [:minus false] \= [:equals false] \[ [:open-bracket false]
     \] [:close-bracket false] \\ [:back-slash false] \; [:semicolon false] \' [:quote false]
     \, [:comma false] \. [:period false] \/ [:slash false] \` [:back-quote false]
     \! [:digit1 true] \@ [:digit2 true] \# [:digit3 true] \$ [:digit4 true] \% [:digit5 true]
     \^ [:digit6 true] \& [:digit7 true] \* [:digit8 true] \( [:digit9 true] \) [:digit0 true]
     \_ [:minus true] \+ [:equals true] \{ [:open-bracket true] \} [:close-bracket true]
     \| [:back-slash true] \: [:semicolon true] \" [:quote true] \< [:comma true]
     \> [:period true] \? [:slash true] \~ [:back-quote true]}))

(defn type!
  "Type text into the focused node of target's window (target is a node,
   selector, scene or window). Click a field first to focus it. Handles
   letters, digits, space and US keyboard punctuation; use p/key-tap! for
   :enter, :tab, :escape, :back-space and other keys."
  [target text]
  (let [scene (scene-of target)]
    (doseq [c text]
      (let [[key shift?] (or (us-keys c)
                             (throw (ex-info (str "type! can't type " (pr-str c)) {:char c})))]
        (if shift?
          (p/key-chord! scene [:shift key])
          (p/key-tap! scene key))))))

(defn fill!
  "Replace the text in a text field and press Enter, which applies the value
   in this app's fields: click the field, select all, type, Enter."
  [target text]
  (let [node (node-of target)
        scene (.getScene node)]
    (click! node)
    (p/key-chord! scene [shortcut :a])
    (type! scene text)
    (p/key-tap! scene :enter)
    (describe node)))

(defn wait-for
  "Call f every 50 ms until it returns a truthy value without throwing, then
   return that value. Throws after timeout-ms (default 3000)."
  ([f] (wait-for f 3000))
  ([f timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (let [[value error] (try [(f) nil] (catch Exception e [nil e]))]
         (cond
           value value
           (< (System/currentTimeMillis) deadline) (do (Thread/sleep 50) (recur))
           :else (throw (ex-info (str "Timed out after " timeout-ms " ms: "
                                      (if error
                                        (.getMessage ^Exception error)
                                        (str "the condition stayed false. Open windows: " (pr-str (windows)))))
                                 {}))))))))

(defn state
  "The app state, or the value at path in it."
  ([] (laser-show.state.core/get-raw-state))
  ([path] (get-in (state) path)))

(defn shot!
  "Save a screenshot of target (a window, node or selector; default: the main
   window) to debug-output/<base>.png and return that path, for the read tool
   to open. A window's screenshot leaves out its open popups."
  ([] (shot! (main-window) "screenshot"))
  ([target] (shot! target "screenshot"))
  ([target base]
   (let [src (io/file (p/screenshot! (el-of target)))
         dest (io/file "debug-output" (str base ".png"))]
     (io/make-parents dest)
     (io/copy src dest)
     (.delete src)
     (.getPath dest))))
