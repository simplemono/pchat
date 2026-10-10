(ns pchat.looks-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [pchat.chat :as chat]
            [pchat.looks :as looks]
            [pchat.session :as session]))

(defn- temp-dir
  []
  (str (java.nio.file.Files/createTempDirectory
        "pchat-looks"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(def png
  "A 1x1 PNG, base64."
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=")

(deftest intern-and-blocks-test
  (let [dir (temp-dir)
        content [{:type "text" :text "Like this"}
                 {:type "image" :data png :mimeType "image/png"}]
        interned (looks/intern dir content)
        {:keys [name url]} (second interned)]
    (testing "the bytes become a content-addressed file, the block its name and URL"
      (is (= {:type "text" :text "Like this"} (first interned)))
      (is (re-matches #"[0-9a-f]{32}\.png" name))
      (is (= (str "/looks/" name) url))
      (is (.isFile (io/file dir name))))
    (testing "the file reads back as the block pi takes"
      (is (= [{:type "image" :data png :mimeType "image/png"}]
             (looks/blocks dir [name]))))
    (testing "unknown or unsafe names are skipped"
      (is (= [] (looks/blocks dir ["missing.png" "../etc/passwd" ""]))))
    (testing "the chat shows the URL under the user's message"
      (let [chat (chat/event chat/empty-chat
                             {:type "message_start"
                              :message (looks/intern-message dir {:role "user" :content content})})]
        (is (= [url] (:images (first (:items chat)))))
        (is (= "Like this" (:text (first (:items chat)))))))
    (testing "the history does the same"
      (let [entries [{:type "message" :message {:role "user" :content content}}]]
        (is (= [url] (:images (first (session/items entries #(looks/intern-message dir %))))))
        (is (= [] (:images (first (session/items entries)))))))))
