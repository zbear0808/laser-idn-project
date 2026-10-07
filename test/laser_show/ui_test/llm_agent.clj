(ns laser-show.ui-test.llm-agent
  "LLM integration for visual UI verification.
   Supports Google Gemini and Ollama as stateless backends.
   Configuration (API keys, provider selection) is loaded from a .env file."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.util Base64]))

;; ---------------------------------------------------------------------------
;; .env Configuration
;; ---------------------------------------------------------------------------

(defn- parse-env-file
  "Parses a .env file into a map of string keys to string values.
   Ignores blank lines and comments (lines starting with #)."
  [file-path]
  (let [file (io/file file-path)]
    (if (.exists file)
      (into {}
            (comp
              (remove #(or (str/blank? %) (str/starts-with? (str/trim %) "#")))
              (map #(str/split % #"=" 2))
              (filter #(= 2 (count %)))
              (map (fn [[k v]] [(str/trim k) (str/trim v)])))
            (str/split-lines (slurp file)))
      {})))

(defn load-config
  "Loads LLM test configuration from a .env file at the project root.
   Returns a config map with :provider, :api-key, :model, :base-url.
   
   Supported .env keys:
     LLM_PROVIDER   - 'gemini' or 'ollama' (default: ollama)
     GEMINI_API_KEY  - API key for Google Gemini
     LLM_MODEL       - Model name (default: llava for ollama, gemini-3.5-flash for gemini)
     OLLAMA_BASE_URL - Ollama server URL (default: http://localhost:11434)"
  ([] (load-config ".env"))
  ([env-path]
   (let [env      (parse-env-file env-path)
         provider (keyword (get env "LLM_PROVIDER" "ollama"))]
     {:provider provider
      :api-key  (get env "GEMINI_API_KEY")
      :model    (get env "LLM_MODEL"
                     (case provider :gemini "gemini-3.5-flash-lite" "llava"))
      :base-url (get env "OLLAMA_BASE_URL" "http://localhost:11434")})))

;; ---------------------------------------------------------------------------
;; Image Encoding
;; ---------------------------------------------------------------------------

(defn- encode-image-base64
  "Reads an image file and returns its base64-encoded string."
  [^String image-path]
  (let [bytes (.readAllBytes (io/input-stream (io/file image-path)))]
    (.encodeToString (Base64/getEncoder) bytes)))

(defn- image-mime-type
  "Returns the MIME type for an image file based on its extension."
  [^String path]
  (cond
    (str/ends-with? path ".png")  "image/png"
    (str/ends-with? path ".jpg")  "image/jpeg"
    (str/ends-with? path ".jpeg") "image/jpeg"
    :else                         "image/png"))

;; ---------------------------------------------------------------------------
;; HTTP Client
;; ---------------------------------------------------------------------------

(def ^:private system-prompt
  "You are a UI testing assistant. You will be shown a screenshot of an application UI.
Answer the user's question about what you see in the screenshot.
You MUST respond with valid JSON in exactly this format:
{\"pass\": true, \"reasoning\": \"explanation of what you see\"}
Set \"pass\" to true if the UI matches what the user describes, false otherwise.
Respond ONLY with the JSON object, no other text.")

(defn- http-post!
  "Performs an HTTP POST request and returns the response body as a string."
  [{:keys [url body headers]}]
  (let [client  (-> (HttpClient/newBuilder)
                    (.connectTimeout (Duration/ofSeconds 30))
                    (.build))
        builder (-> (HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.POST (HttpRequest$BodyPublishers/ofString body))
                    (.timeout (Duration/ofSeconds 120)))]
    (doseq [[k v] (merge {"Content-Type" "application/json"} headers)]
      (.header builder k v))
    (let [response (.send client (.build builder) (HttpResponse$BodyHandlers/ofString))]
      (.body response))))

;; ---------------------------------------------------------------------------
;; Provider Implementations (Stateless - no chat history)
;; ---------------------------------------------------------------------------

(defn- call-gemini
  "Calls the Google Gemini generateContent endpoint (stateless, no chat history).
   Returns the raw text response from the model."
  [{:keys [api-key model]} image-path prompt]
  (let [url     (str "https://generativelanguage.googleapis.com/v1beta/models/"
                     model ":generateContent?key=" api-key)
        b64     (encode-image-base64 image-path)
        mime    (image-mime-type image-path)
        payload (json/write-str
                  {:contents [{:parts [{:text system-prompt}
                                       {:inline_data {:mime_type mime :data b64}}
                                       {:text prompt}]}]})
        response (http-post! {:url url :body payload})]
    (-> (json/read-str response :key-fn keyword)
        :candidates first :content :parts first :text)))

(defn- call-ollama
  "Calls the Ollama /api/generate endpoint (stateless, no chat history).
   Returns the raw text response from the model."
  [{:keys [base-url model]} image-path prompt]
  (let [url     (str base-url "/api/generate")
        b64     (encode-image-base64 image-path)
        payload (json/write-str
                  {:model  model
                   :prompt (str system-prompt "\n\nUser question: " prompt)
                   :images [b64]
                   :stream false})
        response (http-post! {:url url :body payload})]
    (:response (json/read-str response :key-fn keyword))))

;; ---------------------------------------------------------------------------
;; Response Parsing
;; ---------------------------------------------------------------------------

(defn- parse-llm-response
  "Parses the raw LLM text response into a structured result map.
   Handles potential markdown code fencing around the JSON."
  [raw-text]
  (try
    (let [cleaned (-> raw-text
                      str/trim
                      (str/replace #"```json\s*" "")
                      (str/replace #"```\s*$" "")
                      str/trim)
          parsed  (json/read-str cleaned :key-fn keyword)]
      {:pass?     (boolean (:pass parsed))
       :reasoning (or (:reasoning parsed) "No reasoning provided.")})
    (catch Exception _
      {:pass?     false
       :reasoning (str "Failed to parse LLM response: " raw-text)})))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn verify-ui
  "Sends a screenshot to the configured LLM provider for visual verification.
   Each call is completely stateless — no chat history is maintained.
   Returns {:pass? boolean :reasoning string}."
  [config image-path prompt]
  (let [raw-response (case (:provider config)
                       :gemini (call-gemini config image-path prompt)
                       :ollama (call-ollama config image-path prompt)
                       (throw (ex-info (str "Unknown LLM provider: " (:provider config))
                                       {:provider (:provider config)})))]
    (parse-llm-response raw-response)))
