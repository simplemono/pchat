(ns pchat.chats-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
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
