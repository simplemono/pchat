(ns pchat.export-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pchat.chats :as chats]
            [pchat.export :as export]))

(defn- temp-dir
  []
  (str (fs/create-temp-dir {:prefix "pchat-export"})))

(defn- chat-folder!
  "A chat folder like `pchat.chats/create!` makes it, with a file, a
  nested artifact, an empty folder, a session and the name, and with
  what the export leaves out: a tmp file, the share ids, a link to a
  folder outside and a job conversation's template link."
  [workspace id outside]
  (chats/create! workspace id)
  (let [dir (chats/dir (io/file workspace "chats") id)]
    (spit (io/file dir "notes.md") "notes")
    (spit (doto (io/file dir "artifacts" "card" "index.html") io/make-parents) "<h1>card</h1>")
    (.mkdirs (io/file dir "artifacts" "empty"))
    (spit (io/file dir ".session" "s.jsonl") "{}")
    (spit (io/file dir ".chat.edn") (pr-str {:name "Pool card"}))
    (spit (io/file dir "tmp" "scratch.txt") "scratch")
    (spit (io/file dir ".shares.edn") (pr-str {"card.mp4" "0123456789abcdef0123"}))
    (fs/create-sym-link (io/file dir "outside") outside)
    (fs/create-sym-link (io/file dir "template") "../../jobs/x/template")
    dir))

(defn- read-zip
  "The entries of the zip bytes, `{path {:directory? :time :text}}`."
  [bytes]
  (with-open [in (java.util.zip.ZipInputStream. (java.io.ByteArrayInputStream. bytes))]
    (loop [entries {}]
      (if-let [entry (.getNextEntry in)]
        (recur (assoc entries (.getName entry)
                      {:directory? (.isDirectory entry)
                       :time (.getTime entry)
                       :text (when-not (.isDirectory entry)
                               (String. (.readAllBytes in) "UTF-8"))}))
        entries))))

(deftest zip-test
  (let [workspace (temp-dir)
        outside (temp-dir)
        id (chats/new-id)
        _ (spit (io/file outside "secret.txt") "outside")
        _ (spit (doto (io/file workspace "bin" "new") io/make-parents) "#!/bin/sh\n")
        dir (chat-folder! workspace id outside)
        _ (.setLastModified (io/file dir "notes.md") 1577836800000)
        entries (read-zip (export/zip-bytes dir))
        paths (set (keys entries))]
    (testing "the chat's own files and folders, the paths relative to the folder"
      (is (= #{"notes.md" "artifacts" "uploads" ".session" ".chat.edn"}
             (set (map #(first (str/split % #"/")) paths))))
      (is (= "notes" (get-in entries ["notes.md" :text])))
      (is (contains? paths "artifacts/"))
      (is (contains? paths "artifacts/card/"))
      (is (= "<h1>card</h1>" (get-in entries ["artifacts/card/index.html" :text])))
      (is (contains? paths "uploads/"))
      (is (= "{}" (get-in entries [".session/s.jsonl" :text])))
      (is (= (pr-str {:name "Pool card"}) (get-in entries [".chat.edn" :text]))))
    (testing "an empty folder survives as an entry of its own"
      (is (true? (get-in entries ["artifacts/empty/" :directory?]))))
    (testing "the modification time comes along"
      (is (= 1577836800000 (get-in entries ["notes.md" :time]))))
    (testing "not the machine's: tmp, the share ids, and no link, wherever it points"
      (is (not (contains? paths "tmp/")))
      (is (not (contains? paths ".shares.edn")))
      (is (not-any? #(str/starts-with? % "bin") paths))
      (is (not-any? #(str/starts-with? % "outside") paths))
      (is (not-any? #(str/starts-with? % "template") paths))
      (is (not-any? #(= "outside" (:text %)) (vals entries)) "nothing read through the link"))
    (testing "the folder on disk is as it was"
      (is (.isFile (io/file dir "tmp" "scratch.txt")))
      (is (fs/sym-link? (io/file dir "outside"))))))

(deftest file-name-test
  (is (= "Pool card.zip" (export/file-name "Pool card" "id")))
  (is (= "Karte für Oma.zip" (export/file-name "Karte für Oma" "id")) "an umlaut stays")
  (is (= "a-b-c-d.zip" (export/file-name "a/b:c\"d" "id")))
  (is (= "one two.zip" (export/file-name "  one \n two  " "id")))
  (is (= "id.zip" (export/file-name "" "id")) "nothing left: the id")
  (is (= "id.zip" (export/file-name nil "id")))
  (is (= (+ export/max-name-chars 4) (count (export/file-name (apply str (repeat 200 "x")) "id")))))

(deftest response-test
  (let [workspace (temp-dir)
        id (chats/new-id)
        dir (chat-folder! workspace id (temp-dir))
        response (export/response dir (export/file-name "Karte für Oma" id))]
    (is (= 200 (:status response)))
    (is (= "application/zip" (get-in response [:headers "Content-Type"])))
    (is (= "attachment; filename=\"Karte f_r Oma.zip\"; filename*=UTF-8''Karte%20f%C3%BCr%20Oma.zip"
           (get-in response [:headers "Content-Disposition"])))
    (is (contains? (read-zip (.readAllBytes (:body response))) "notes.md"))))
