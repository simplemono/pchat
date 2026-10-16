(ns pchat.chats-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pchat.chats :as chats]))

(defn- temp-dir
  []
  (str (fs/create-temp-dir {:prefix "pchat-chats"})))

(deftest id-test
  (let [a (chats/new-id)
        b (do (Thread/sleep 2) (chats/new-id))]
    (testing "a UUID of version 7, one that sorts after the one before it"
      (is (chats/valid-id? a))
      (is (= \7 (nth a 14)))
      (is (contains? #{\8 \9 \a \b} (nth a 19)))
      (is (neg? (compare a b)))
      (is (not= a b)))
    (testing "what is no id names no folder"
      (is (not (chats/valid-id? "../x")))
      (is (not (chats/valid-id? "")))
      (is (not (chats/valid-id? nil)))
      (is (not (chats/valid-id? "0199dd7e-1f1a-7a2b-8b3c-4d5e6f70819G"))))))

(deftest folder-test
  (let [workspace (temp-dir)
        chats-dir (io/file workspace "chats")
        id (chats/new-id)]
    (spit (doto (io/file workspace "bin" "new") io/make-parents) "#!/bin/sh\n")
    (testing "the folder with its parts, linked to the shared ones"
      (is (= id (chats/create! workspace id)))
      (is (.isDirectory (chats/artifacts-dir chats-dir id)))
      (is (.isDirectory (chats/uploads-dir chats-dir id)))
      (is (.isDirectory (chats/tmp-dir chats-dir id)))
      (is (.isDirectory (chats/session-dir chats-dir id)))
      (is (.isFile (io/file (chats/dir chats-dir id) "bin" "new")) "bin/ of the workspace through the link")
      (is (fs/sym-link? (io/file (chats/dir chats-dir id) ".pchat")))
      (is (= [id] (chats/ids chats-dir)))
      (is (= id (chats/create! workspace id)) "again changes nothing")
      (is (= (str "/chat/" id) (chats/url id))))
    (testing "the name and the task in the folder"
      (is (= {} (chats/meta chats-dir id)))
      (chats/update-meta! chats-dir id #(assoc % :name "Pool card"))
      (chats/update-meta! chats-dir id #(assoc % :task :free))
      (is (= {:name "Pool card" :task :free} (chats/meta chats-dir id)))
      (chats/update-meta! chats-dir id #(assoc % :name nil))
      (is (= {:task :free} (chats/meta chats-dir id)))
      (chats/update-meta! chats-dir id #(dissoc % :task))
      (is (not (.exists (chats/meta-file chats-dir id))) "nothing left, no file"))
    (testing "gone with its folder; what the links pointed to stays"
      (spit (io/file (chats/artifacts-dir chats-dir id) "card.html") "<p>")
      (chats/delete! chats-dir id)
      (is (not (.exists (chats/dir chats-dir id))))
      (is (= [] (chats/ids chats-dir)))
      (is (.isFile (io/file workspace "bin" "new"))))
    (testing "only an id names a folder to delete"
      (.mkdirs (io/file chats-dir "notes"))
      (chats/delete! chats-dir "notes")
      (chats/delete! chats-dir "../bin")
      (is (.isDirectory (io/file chats-dir "notes")))
      (is (.isFile (io/file workspace "bin" "new"))))))

(deftest tmp-path-test
  (let [workspace (temp-dir)
        chats-dir (io/file workspace "chats")
        id (chats/new-id)
        link (str (chats/tmp-link id))]
    (spit (doto (io/file workspace "bin" "new") io/make-parents) "#!/bin/sh\n")
    (chats/create! workspace id)
    (testing "a short link to the chat's tmp/, short enough for a socket in it"
      (is (= link (chats/tmp-path chats-dir id)))
      (is (= (str (fs/absolutize (chats/tmp-dir chats-dir id))) (str (fs/read-link link))))
      (is (< (count (str link "/com.google.Chrome.XXXXXX/SingletonSocket")) 107)))
    (testing "a link that points elsewhere is mended"
      (fs/delete link)
      (fs/create-sym-link link (temp-dir))
      (is (= link (chats/tmp-path chats-dir id)))
      (is (= (str (fs/absolutize (chats/tmp-dir chats-dir id))) (str (fs/read-link link)))))
    (testing "deleting the chat removes the link"
      (chats/delete! chats-dir id)
      (is (not (fs/exists? link {:nofollow-links true}))))))

(deftest write-session-test
  (let [from (temp-dir)
        workspace (temp-dir)
        chats-dir (io/file workspace "chats")
        id (chats/new-id)
        file (io/file from "2026-10-13T14-41-30-309Z_45a549c4-5c50-4113-8d9e-a3114f8a81bb.jsonl")]
    (spit file (str (json/generate-string {:type "session" :version 3 :id "45a549c4-5c50-4113-8d9e-a3114f8a81bb" :cwd from}) "\n"
                    "{\"type\":\"message\",\"message\":{\"role\":\"user\",\"content\":\"ping\"}}\n"))
    (testing "the file under the chat's id in its session folder: the name, the header's id and cwd; the rest as it was"
      (let [written (chats/write-session! chats-dir id file)
            [header message] (str/split-lines (slurp written))]
        (is (= (str (io/file (chats/session-dir chats-dir id) (str "2026-10-13T14-41-30-309Z_" id ".jsonl"))) (str written)))
        (is (= {:type "session" :version 3 :id id :cwd (str (chats/dir chats-dir id))} (json/parse-string header true)))
        (is (str/includes? message "ping"))
        (is (.isFile file) "the source stays")))
    (testing "no session file of pi: nothing written"
      (let [other (io/file from "notes.jsonl")]
        (spit other "{}")
        (is (nil? (chats/write-session! chats-dir id other))))
      (let [no-header (io/file from "2026-10-13T14-41-30-309Z_45a549c4-5c50-4113-8d9e-a3114f8a81bc.jsonl")]
        (spit no-header "{\"type\":\"message\"}\n")
        (is (nil? (chats/write-session! chats-dir id no-header)))))))
