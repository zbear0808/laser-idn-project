(ns build
  "Build tasks for the Laser Show application.
   
   Tasks:
   - clean       : Remove target directory
   - uber        : Build uber JAR with all dependencies
   - native-prep : Prepare for GraalVM native-image build
   - trace-config: Run with GraalVM tracing agent to generate reflection configs
   
   Usage:
   clojure -T:build clean
   clojure -T:build uber
   clojure -T:build-mac uber    ; macOS-specific JavaFX
   clojure -T:build-linux uber  ; Linux-specific JavaFX
   clojure -T:build-windows uber ; Windows-specific JavaFX
   clojure -T:build native-prep ; Prepare native image config"
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]))

(def lib 'laser-idn-project/laser-show)
(def version "0.1.0")
(def class-dir "target/classes")
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))
(def basis (delay (b/create-basis {:project "deps.edn"})))

;; Native image configuration paths
(def native-config-src "META-INF/native-image/laser-show")
(def native-config-dest (str class-dir "/META-INF/native-image/laser-show"))

(defn clean
  "Remove target directory."
  [_]
  (println "Cleaning target directory...")
  (b/delete {:path "target"}))

(defn- copy-native-config
  "Copy GraalVM native-image configuration files to the class directory.
   This ensures the configs are included in the uber JAR."
  []
  (when (.exists (io/file native-config-src))
    (println "Copying native-image configuration files...")
    (b/copy-dir {:src-dirs [native-config-src]
                 :target-dir native-config-dest})
    (println "  - reflect-config.json")
    (println "  - resource-config.json")
    (println "  - jni-config.json")
    (println "  - serialization-config.json")
    (println "  - native-image.properties")))

(defn uber
  "Build uber JAR with all dependencies and native-image configs.
   
   The JAR includes:
   - Compiled Clojure namespaces (AOT)
   - All source files and resources
   - GraalVM native-image configuration files
   - Platform-specific JavaFX natives (based on build alias used)"
  [_]
  (clean nil)
  (println (format "Building uber JAR: %s" uber-file))
  
  ;; Copy source and resources
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  
  ;; Copy native-image configuration
  (copy-native-config)
  
  ;; AOT compile the main namespace
  (println "AOT compiling laser-show.app...")
  (b/compile-clj {:basis @basis
                  :ns-compile '[laser-show.app]
                  :class-dir class-dir})
  
  ;; Create uber JAR
  (println "Creating uber JAR...")
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main 'laser-show.app
           :conflict-handlers {"META-INF/.*\\.SF$" :ignore
                              "META-INF/.*\\.DSA$" :ignore
                              "META-INF/.*\\.RSA$" :ignore}})
  
  (println (format "✓ Built: %s" uber-file))
  (let [size (/ (.length (io/file uber-file)) 1024.0 1024.0)]
    (println (format "  Size: %.1f MB" size))))

(defn native-prep
  "Prepare configuration for GraalVM native-image build.
   
   This task:
   1. Builds the uber JAR
   2. Validates native-image configuration files exist
   3. Prints instructions for running the native build
   
   After running this, use Maven with GluonFX:
   mvn gluonfx:build"
  [_]
  (uber nil)
  
  (println "\n=== Native Image Build Preparation ===")
  
  ;; Validate native config files exist
  (let [config-files ["reflect-config.json"
                      "resource-config.json"
                      "jni-config.json"
                      "serialization-config.json"
                      "native-image.properties"]]
    (println "\nChecking native-image configuration:")
    (doseq [f config-files]
      (let [path (str native-config-src "/" f)
            exists? (.exists (io/file path))]
        (println (format "  %s %s" (if exists? "✓" "✗") f)))))
  
  (println "\n=== Next Steps ===")
  (println "1. Ensure GraalVM is installed and GRAALVM_HOME is set")
  (println "2. Run the GluonFX Maven build:")
  (println "   mvn gluonfx:build")
  (println "")
  (println "Or use the GitHub Action:")
  (println "   .github/workflows/native-build.yml")
  (println ""))

(defn trace-config
  "Run the application with GraalVM tracing agent to generate/update reflection configs.
   
   Prerequisites:
   - GraalVM must be installed
   - JAVA_HOME should point to GraalVM
   
   The agent will output configuration to: META-INF/native-image/laser-show/
   
   Usage: clojure -T:build trace-config
   
   Note: Run the app, exercise all features, then close it. The agent will
   capture all reflection/JNI/resource access patterns."
  [_]
  ;; First build the uber JAR
  (uber nil)
  
  (println "\n=== Running with GraalVM Tracing Agent ===")
  (println "The application will start with native-image-agent enabled.")
  (println "Exercise all features of the app, then close it normally.")
  (println "Configuration files will be written to: " native-config-src)
  (println "")
  
  ;; Ensure output directory exists
  (io/make-parents (str native-config-src "/dummy"))
  
  (let [java-home (System/getenv "JAVA_HOME")
        java-cmd (if java-home
                   (str java-home "/bin/java")
                   "java")
        agent-opts (str "-agentlib:native-image-agent="
                        "config-merge-dir=" native-config-src ","
                        "experimental-class-define-support")]
    
    (println (format "Running: %s %s -jar %s" java-cmd agent-opts uber-file))
    (println "")
    
    ;; Run the application with the tracing agent
    (let [{:keys [exit out err]}
          (shell/sh java-cmd
                    agent-opts
                    "-jar" uber-file)]
      (when (not= exit 0)
        (println "Error output:" err))
      (println out)))
  
  (println "\n=== Tracing Complete ===")
  (println "Configuration files have been updated in:" native-config-src)
  (println "Review the generated configs and commit them to version control."))

(defn print-help
  "Print available build tasks."
  [_]
  (println "Laser Show Build Tasks")
  (println "======================")
  (println "")
  (println "  clojure -T:build clean        - Remove target directory")
  (println "  clojure -T:build uber         - Build uber JAR")
  (println "  clojure -T:build native-prep  - Prepare for native-image build")
  (println "  clojure -T:build trace-config - Run with tracing agent")
  (println "  clojure -T:build print-help   - Show this help")
  (println "")
  (println "Platform-specific builds:")
  (println "  clojure -T:build-mac uber     - macOS JavaFX")
  (println "  clojure -T:build-linux uber   - Linux JavaFX")
  (println "  clojure -T:build-windows uber - Windows JavaFX")
  (println ""))
