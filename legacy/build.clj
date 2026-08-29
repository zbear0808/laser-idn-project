(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]))

(def lib 'laser-idn-project/laser-show)
(def version "0.1.0")
(def class-dir "target/classes")
(def jfr-class-dir "classes")  ;; For JFR event classes used during dev
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))

;; Base basis without platform - used for compile-java during dev
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn- make-platform-basis
  "Create a basis with platform-specific JavaFX dependencies.
   platform should be :win, :mac, or :linux"
  [platform]
  (b/create-basis {:project "deps.edn" :aliases [platform]}))

(defn clean [_]
  (b/delete {:path "target"}))

(defn compile-java
  "Compile Java JFR event classes for development.
   
   Run with: clj -T:build compile-java
   
   This compiles the Java source files in java/ to classes/ which is on
   the classpath. Required for JFR custom events to work during dev."
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

(defn uber
  "Build an uberjar with platform-specific JavaFX dependencies.
   
   Usage:
     clj -T:build uber :platform :win      ; Windows
     clj -T:build uber :platform :mac      ; macOS Intel (x86_64)
     clj -T:build uber :platform :mac-arm  ; macOS Apple Silicon (ARM64)
     clj -T:build uber :platform :linux    ; Linux
   
   The :platform parameter is REQUIRED because JavaFX is platform-specific.
   This builds a standalone JAR with all dependencies including JavaFX 26."
  [{:keys [platform]}]
  (when-not platform
    (throw (ex-info "Platform is required. Use :platform :win, :mac, :mac-arm, or :linux" {})))
  (when-not (#{:win :mac :mac-arm :linux} platform)
    (throw (ex-info (str "Invalid platform: " platform ". Must be :win, :mac, :mac-arm, or :linux") {})))
  
  (println (str "Building uberjar for platform: " (name platform)))
  (clean nil)
  
  (let [platform-basis (make-platform-basis platform)]
    ;; Compile Java JFR event classes first (required before Clojure compilation)
    (println "Compiling Java JFR event classes...")
    (b/javac {:src-dirs ["java"]
              :class-dir class-dir
              :basis platform-basis
              :javac-opts ["--release" "17"]})
    
    (b/copy-dir {:src-dirs ["src" "resources"]
                 :target-dir class-dir})
    
    (println "Compiling Clojure...")
    (b/compile-clj {:basis platform-basis
                    :ns-compile '[laser-show.app]
                    :class-dir class-dir})
    
    (println "Building uberjar...")
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis platform-basis
             :main 'laser-show.app
             :conflict-handlers {"META-INF/.*\\.SF$" :ignore
                                "META-INF/.*\\.DSA$" :ignore
                                "META-INF/.*\\.RSA$" :ignore}})
    
    (println (str "✅ Built: " uber-file))))
