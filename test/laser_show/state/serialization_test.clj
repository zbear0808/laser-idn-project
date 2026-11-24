(ns laser-show.state.serialization-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [laser-show.output.jar-builder :as jar]
   [laser-show.state.serialization :as ser])
  (:import [clojure.lang ExceptionInfo]
           [java.io ByteArrayOutputStream File]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.file Files]
           [java.util Arrays]
           [java.util.zip CRC32]))


;; Basic Round-trip Tests


(deftest basic-round-trip-test
  (testing "Map round-trip"
    (let [data {:type :cue :data {:name "test"}}]
      (is (= data (-> data ser/serialize ser/deserialize)))))
  
  (testing "Vector round-trip"
    (let [data [:item1 :item2 :item3]]
      (is (= data (-> data ser/serialize ser/deserialize))))))


;; Data Structure Validation Tests


(deftest deserialize-returns-valid-structures-test
  (testing "Deserialize returns map"
    (let [result (ser/deserialize "{:type :cue}")]
      (is (map? result))
      (is (= :cue (:type result)))))
  
  (testing "Deserialize returns vector"
    (let [result (ser/deserialize "[1 2 3]")]
      (is (sequential? result))
      (is (= [1 2 3] result)))))


;; Type Validation Tests (Application-specific)


(deftest type-check-validation-test
  (testing "Accept correct type"
    (let [cue-data {:type :cue :data {}}
          serialized (ser/serialize cue-data)
          result (ser/deserialize-with-schema serialized
                   :schema-fn (ser/with-type-check :cue))]
      (is (some? result))
      (is (= :cue (:type result)))))
  
  (testing "Reject wrong type"
    (let [effect-data {:type :effect :data {}}
          serialized (ser/serialize effect-data)
          result (ser/deserialize-with-schema serialized
                   :schema-fn (ser/with-type-check :cue)
                   :on-invalid (constantly nil))]
      (is (nil? result))))
  
  (testing "Reject data without type"
    (let [no-type-data {:data {}}
          serialized (ser/serialize no-type-data)
          result (ser/deserialize-with-schema serialized
                   :schema-fn (ser/with-type-check :cue)
                   :on-invalid (constantly nil))]
      (is (nil? result)))))

(deftest clipboard-type-safety-test
  (testing "Can't paste effect into cue grid"
    (let [effect {:type :effect-assignment :effect-id :color-cycle}
          serialized (ser/serialize-for-clipboard effect)
          result (ser/deserialize-from-clipboard serialized
                   :schema-fn (ser/with-type-check :cell-assignment)
                   :on-invalid (constantly nil))]
      (is (nil? result))
      (is (not= effect result))))
  
  (testing "Can paste cue into cue grid"
    (let [cue {:type :cell-assignment :preset-id :circle}
          serialized (ser/serialize-for-clipboard cue)
          result (ser/deserialize-from-clipboard serialized
                   :schema-fn (ser/with-type-check :cell-assignment))]
      (is (some? result))
      (is (= cue result))))
  
  (testing "Can't paste cue into effect grid"
    (let [cue {:type :cell-assignment :preset-id :circle}
          serialized (ser/serialize-for-clipboard cue)
          result (ser/deserialize-from-clipboard serialized
                   :schema-fn (ser/with-type-check :effect-assignment)
                   :on-invalid (constantly nil))]
      (is (nil? result))
      (is (not= cue result))))
  
  (testing "Can paste effect into effect grid"
    (let [effect {:type :effect-assignment :effect-id :color-cycle}
          serialized (ser/serialize-for-clipboard effect)
          result (ser/deserialize-from-clipboard serialized
                   :schema-fn (ser/with-type-check :effect-assignment))]
      (is (some? result))
      (is (= effect result)))))


;; File Operations (Basic Smoke Tests)


(deftest file-operations-test
  (let [test-file "test-serialization.edn"
        test-data {:type :projector :id :proj-1 :host "192.168.1.100"}]
    
    (testing "Save and load file"
      (is (ser/save-to-file! test-file test-data))
      (is (= test-data (ser/load-from-file test-file))))
    
    ;; Cleanup
    (when (.exists (io/file test-file))
      (.delete (io/file test-file)))))


;; A project zip, read strictly (plans/2026-11-23-cljfx-reactivity/02)


(defn- little-endian
  "n as size (2 or 4) little-endian bytes."
  ^bytes [n size]
  (let [buffer (.order (ByteBuffer/allocate size) ByteOrder/LITTLE_ENDIAN)]
    (if (= 2 size)
      (.putShort buffer (unchecked-short n))
      (.putInt buffer (unchecked-int n)))
    (.array buffer)))

(defn- stored-zip
  "The bytes of a zip of entries, each [name text] or [name text crc],
   stored (not compressed) and written field by field: unlike
   java.util.zip's writer, it takes a name twice and a CRC-32 that doesn't
   match the text."
  ^bytes [entries]
  (let [out (ByteArrayOutputStream.)
        fields! (fn [& values-and-sizes]
                  (doseq [[n size] (partition 2 values-and-sizes)]
                    (.writeBytes out (little-endian n size))))
        locals (reduce (fn [written [entry-name ^String text crc]]
                         (let [data (.getBytes text "UTF-8")
                               name-bytes (.getBytes ^String entry-name "UTF-8")
                               crc (or crc (.getValue (doto (CRC32.) (.update data))))
                               offset (.size out)]
                           ;; Local header: version 1.0, no flags, stored, no time
                           (fields! 0x04034b50 4, 10 2, 0 2, 0 2, 0 2, 0 2, crc 4, (alength data) 4,
                                    (alength data) 4, (alength name-bytes) 2, 0 2)
                           (.writeBytes out name-bytes)
                           (.writeBytes out data)
                           (conj written [name-bytes crc (alength data) offset])))
                       []
                       entries)
        directory (.size out)]
    (doseq [[^bytes name-bytes crc size offset] locals]
      (fields! 0x02014b50 4, 20 2, 10 2, 0 2, 0 2, 0 2, 0 2, crc 4, size 4, size 4,
               (alength name-bytes) 2, 0 2, 0 2, 0 2, 0 2, 0 4, offset 4)
      (.writeBytes out name-bytes))
    (fields! 0x06054b50 4, 0 2, 0 2, (count locals) 2, (count locals) 2,
             (- (.size out) directory) 4, directory 4, 0 2)
    (.toByteArray out)))

(def ^:private empty-show
  "content.edn's text of a show with both grids empty."
  "{:chains {:cue-chains {} :effect-chains {}}}")

(def ^:private duplicate-content-zip
  "A zip with content.edn twice, a different show in each: which one is the
   project's is ambiguous. A map of entries can't hold the name twice, and
   java.util.zip refuses to write it."
  (stored-zip [["content.edn" empty-show]
               ["content.edn" "{:chains {:cue-chains {[0 0] {:items []}} :effect-chains {}}}"]]))

(def ^:private png-bytes
  "A thumbnail's first bytes: binary, and not UTF-8."
  (byte-array (map unchecked-byte [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A 0xFF 0xFE 0x00])))

(defn- read-strict [path]
  (ser/load-from-zip path :strict? true :filenames #{"content.edn" "hardware.edn"}))

(defn- refusal
  "The ex-data of what read-strict throws for the zip at path, or ::read
   when it reads."
  [path]
  (try
    (read-strict path)
    ::read
    (catch ExceptionInfo e
      (ex-data e))))

(deftest strict-zip-requires-one-complete-form-test
  (let [^File dir (jar/temp-dir! "strict-zip")
        zip! (fn [file-name entries] (str (jar/write-jar! (io/file dir file-name) entries)))
        bytes! (fn [file-name ^bytes bytes] (let [f (io/file dir file-name)] (io/copy bytes f) (str f)))]
    (try
      (testing "a member that isn't one complete EDN form is refused, and named"
        (doseq [[i [label text]] (map-indexed vector
                                              [["empty" ""]
                                               ["a comment only" "  ;; nothing here\n"]
                                               ["cut short" "{:chains {:cue-chains {}"]
                                               ["a Java object, as it prints" "{:chains #object[java.lang.Object 1 \"x\"]}"]
                                               ["an unknown tag" "{:chains {:cue-chains {} :effect-chains #laser/thing [1 2]}}"]
                                               ["a second form after it" (str empty-show "\n{:chains {}}")]
                                               ["a keyword after it" (str empty-show " :x")]])]
          (testing label
            (let [path (zip! (str "member-" i ".zip") {"content.edn" text})]
              (is (= {:zip-path path :entry "content.edn"} (refusal path)))))))
      (testing "bytes that aren't UTF-8, in any member read"
        (let [bad (byte-array (concat (.getBytes "{:zone-groups {} :name \"") (map unchecked-byte [0xC3 0x28]) (.getBytes "\"}")))
              path (zip! "utf-8.zip" {"content.edn" empty-show "hardware.edn" bad})]
          (is (= "hardware.edn" (:entry (refusal path))))))
      (testing "an archive cut short"
        (let [whole (Files/readAllBytes (.toPath (io/file (zip! "whole.zip" {"content.edn" empty-show}))))
              path (bytes! "cut.zip" (Arrays/copyOf whole (quot (alength whole) 2)))]
          (is (= {:zip-path path} (refusal path)))))
      (testing "not a zip at all"
        (let [path (bytes! "show.zip" (.getBytes empty-show "UTF-8"))]
          (is (= {:zip-path path} (refusal path)))))
      (testing "a member whose CRC-32 doesn't match it"
        (let [path (bytes! "crc.zip" (stored-zip [["content.edn" empty-show 12345]]))]
          (is (= "content.edn" (:entry (refusal path)))))
        (testing "while the same member with its own reads"
          (is (= {"content.edn" {:chains {:cue-chains {} :effect-chains {}}}}
                 (read-strict (bytes! "stored.zip" (stored-zip [["content.edn" empty-show]])))))))
      (testing "a name twice"
        (let [path (bytes! "twice.zip" duplicate-content-zip)]
          (is (= {:zip-path path :entry "content.edn"} (refusal path)))))
      (testing "one map with a #uuid and a comment after it reads; the manifest, a binary extra and a folder aren't read"
        (let [path (zip! "good.zip" {"content.edn" (str "{:chains {:cue-chains {[0 0] {:items [{:type :preset :preset-id :circle\n"
                                                        "  :id #uuid \"11111111-1111-1111-1111-111111111111\"}]}}\n"
                                                        "  :effect-chains {}}}\n;; saved by hand\n")
                                     "thumbnail.png" png-bytes
                                     "extras/" ""
                                     "extras/content.edn" "not EDN {"})]
          (is (= {"content.edn" {:chains {:cue-chains {[0 0] {:items [{:type :preset :preset-id :circle
                                                                       :id #uuid "11111111-1111-1111-1111-111111111111"}]}}
                                          :effect-chains {}}}}
                 (read-strict path)))))
      (finally
        (jar/delete-tree! dir)))))
