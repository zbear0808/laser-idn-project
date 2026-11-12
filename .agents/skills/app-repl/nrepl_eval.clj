;; Evaluate Clojure in the nREPL of the running app. The port comes from
;; .nrepl-port in the current directory, so run it from the repo root:
;;
;;   clj -M:dev .agents/skills/app-repl/nrepl_eval.clj '(+ 1 2)'
;;   clj -M:dev .agents/skills/app-repl/nrepl_eval.clj @debug-output/form.clj laser-show.app
;;
;; The form is either the argument itself or, with a leading @, the contents
;; of that file. The optional second argument is the namespace to evaluate in.
;; Prints stdout, then each result. Exits 1 when the eval fails or no REPL answers.
(require '[clojure.string :as str]
         '[nrepl.core :as nrepl])

(let [[arg ns-name] *command-line-args*
      code (if (str/starts-with? arg "@")
             (slurp (subs arg 1))
             arg)
      port-file (java.io.File. ".nrepl-port")]
  (when-not (.exists port-file)
    (binding [*out* *err*]
      (println "No .nrepl-port in the current directory. Start the REPL first (see the app-repl skill).")
      (flush))
    (System/exit 1))
  (let [port (parse-long (str/trim (slurp port-file)))
        {:keys [out err value status]}
        (try
          (with-open [transport (nrepl/connect :port port)]
            (nrepl/combine-responses
              (nrepl/message (nrepl/client transport 120000)
                             (cond-> {:op "eval" :code code}
                               ns-name (assoc :ns ns-name)))))
          (catch java.net.ConnectException _
            (binding [*out* *err*]
              (println (str "Nothing listens on port " port ". The .nrepl-port file may be stale."))
              (flush))
            (System/exit 1)))
        failed? (not (every? #{"done"} status))]
    (when out
      (print out))
    (when err
      (binding [*out* *err*]
        (print err)
        (flush)))
    (doseq [v value]
      (println v))
    (flush)
    (System/exit (if failed? 1 0))))
