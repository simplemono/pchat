(ns pchat.uploads-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [pchat.chat :as chat]
            [pchat.uploads :as uploads]
            [rframes.transit :as transit]))

(defn- temp-dir
  []
  (str (java.nio.file.Files/createTempDirectory
        "pchat-uploads"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- post
  "The answer of the handler to a POST of `bytes` as `type`, the status
  and the transit body read back."
  [opts type bytes & [query]]
  (let [{:keys [status body]} (:ring/response
                               (uploads/handler opts
                                                {:ring/request {:request-method :post
                                                                :uri "/upload"
                                                                :query-string query
                                                                :headers {"content-type" type}
                                                                :body (java.io.ByteArrayInputStream. bytes)}}))]
    [status (transit/read-str body)]))

(defn- bytes-of
  [n]
  (byte-array (map #(unchecked-byte (mod % 251)) (range n))))

(defn- visible-files
  [dir]
  (sort (remove #(.startsWith % ".") (map #(.getName %) (.listFiles (io/file dir))))))

(deftest whole-clip-test
  (let [dir (temp-dir)
        content (bytes-of 200000)
        [status {:keys [name url kind size]}] (post {:dir dir} "video/mp4" content)]
    (testing "a clip in one request lands on disk under the hash of its bytes"
      (is (= 200 status))
      (is (re-matches #"[0-9a-f]{32}\.mp4" name))
      (is (= (str "/uploads/" name) url))
      (is (= :video kind))
      (is (= 200000 size))
      (is (= (seq content) (seq (java.nio.file.Files/readAllBytes (.toPath (io/file dir name)))))))
    (testing "the same bytes again are the same file, no part file stays"
      (is (= name (:name (second (post {:dir dir} "video/mp4" content)))))
      (is (= [name] (visible-files dir)))
      (is (empty? (filter #(.endsWith (.getName %) ".part") (.listFiles (io/file dir))))))
    (testing "an audio type with parameters"
      (let [[status answer] (post {:dir dir} "audio/mpeg; charset=binary" (bytes-of 10))]
        (is (= 200 status))
        (is (= :audio (:kind answer)))
        (is (.endsWith (:name answer) ".mp3"))))))

(deftest rejected-test
  (let [dir (temp-dir)]
    (testing "a type that is neither an image nor a clip"
      (is (= [415 {:error :not-media}] (post {:dir dir} "application/zip" (bytes-of 10)))))
    (testing "a clip larger than the limit, nothing left on disk"
      (is (= [413 {:error :too-large}] (post {:dir dir :max-bytes 1000} "video/mp4" (bytes-of 1001))))
      (is (empty? (.listFiles (io/file dir)))))
    (testing "pieces announcing more than the limit"
      (is (= 413 (first (post {:dir dir :max-bytes 1000} "video/mp4" (bytes-of 10)
                              "id=00112233445566778899aabbccddeeff&offset=0&total=1001")))))
    (testing "an empty body"
      (is (= [400 {:error :empty}] (post {:dir dir} "video/mp4" (byte-array 0)))))))

(deftest pieces-test
  (let [dir (temp-dir)
        id "00112233445566778899aabbccddeeff"
        content (bytes-of 25)
        piece (fn [from to]
                (post {:dir dir} "video/quicktime" (java.util.Arrays/copyOfRange content from to)
                      (str "id=" id "&offset=" from "&total=25")))]
    (testing "a piece that does not start where the file ends"
      (is (= 409 (first (piece 10 20)))))
    (testing "pieces in order make the file, the last answer names it"
      (is (= [200 {:received 10}] (piece 0 10)))
      (is (= [200 {:received 20}] (piece 10 20)))
      (let [[status {:keys [name kind size]}] (piece 20 25)]
        (is (= 200 status))
        (is (= (str id ".mov") name))
        (is (= :video kind))
        (is (= 25 size))
        (is (= (seq content) (seq (java.nio.file.Files/readAllBytes (.toPath (io/file dir name))))))
        (is (= [name] (visible-files dir)))))
    (testing "a bad id"
      (is (= 400 (first (post {:dir dir} "video/mp4" (bytes-of 3) "id=../../x&offset=0&total=3")))))))

(deftest lines-test
  (let [text "Make a short of this\n(Attached video: uploads/ab12cd.mp4)\n(Angehängtes Audio: uploads/ff00.wav)"]
    (testing "the lines that name clips"
      (is (= ["uploads/ab12cd.mp4" "uploads/ff00.wav"] (uploads/paths text)))
      (is (= "Make a short of this" (uploads/without-lines text)))
      (is (= [{:kind :video :url "/uploads/ab12cd.mp4"}
              {:kind :audio :url "/uploads/ff00.wav"}]
             (uploads/attachments text))))
    (testing "the chat shows the text without them and the clips as media"
      (let [item (first (:items (chat/event chat/empty-chat
                                            {:type "message_start"
                                             :message {:role "user"
                                                       :content [{:type "text" :text text}]}})))]
        (is (= "Make a short of this" (:text item)))
        (is (= 2 (count (:media item))))))
    (testing "a message without clips is left as it is"
      (is (= [] (uploads/paths "(not a clip: here)")))
      (is (= "(not a clip: here)" (uploads/without-lines "(not a clip: here)"))))))
