;; Helpers for the plorer skill. Load them once per REPL session, from the repo root:
;;
;;   clj -M:dev .agents/skills/app-repl/nrepl_eval.clj @.agents/skills/plorer/helpers.clj
;;
;; Then, for example: (outline window :depth 4) and (shot! window "main").
(require '[clojure.java.io :as io]
         '[cljfx.plorer :as p])

(defn outline
  "Print the scene graph under el (a window, scene or node), one node per line,
   with its #id and text. :depth limits how many levels are printed."
  [el & {:keys [depth] :or {depth 3}}]
  (letfn [(walk [{:keys [el props children]} level]
            (println (str (apply str (repeat level "  "))
                          (.getSimpleName (class el))
                          (when-let [id (:id props)] (str " #" id))
                          (when-let [text (:text props)] (str " " (pr-str text)))))
            (run! #(walk % (inc level)) children))]
    (walk (p/tree el :depth depth :props [:id :text]) 0)))

(defn shot!
  "Save a screenshot of el (a window or node) to debug-output/<base>.png and
   return that path relative to the repo root, so the read tool can show it."
  ([el]
   (shot! el "screenshot"))
  ([el base]
   (let [src (io/file (p/screenshot! el))
         dest (io/file "debug-output" (str base ".png"))]
     (io/make-parents dest)
     (io/copy src dest)
     (.delete src)
     (.getPath dest))))
