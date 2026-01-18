(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]))

(def lib 'laser-idn-project/laser-show)
(def version "0.1.0")
(def class-dir "target/classes")
(def jfr-class-dir "classes")  ;; For JFR event classes used during dev
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn compile-java
  "Compile Java JFR event classes.
   
   Run with: clj -T:build compile-java
   
   This compiles the Java source files in java/ to classes/ which is on
   the classpath. Required for JFR custom events to work."
  [_]
  (println "Compiling Java JFR event classes...")
  (let [java-src "java"
        out-dir jfr-class-dir]
    ;; Create output directory if needed
    (.mkdirs (io/file out-dir))
    
    ;; Use javac to compile
    (b/javac {:src-dirs [java-src]
              :class-dir out-dir
              :basis @basis
              :javac-opts ["--release" "17"]})
    
    (println (str "✅ Java classes compiled to " out-dir "/"))))

(defn clean-java
  "Clean compiled Java classes."
  [_]
  (b/delete {:path jfr-class-dir})
  (println "✅ Cleaned Java classes"))

(defn uber [_]
  (clean nil)
  ;; Compile Java JFR event classes first (required before Clojure compilation)
  (println "Compiling Java JFR event classes...")
  (b/javac {:src-dirs ["java"]
            :class-dir class-dir
            :basis @basis
            :javac-opts ["--release" "17"]})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis @basis
                  :ns-compile '[laser-show.app]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main 'laser-show.app
           :conflict-handlers {"META-INF/.*\\.SF$" :ignore
                              "META-INF/.*\\.DSA$" :ignore
                              "META-INF/.*\\.RSA$" :ignore}}))
