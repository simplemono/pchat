(ns pchat.migrate-test
  "bin/migrate-chats on a workspace of the layout before 0.2.0, and
  the banner that offers it."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pchat.chats :as chats]
            [pchat.looks :as looks]
            [pchat.migrate :as migrate]
            [pchat.session :as session]
            [pchat.view :as view]))

(def png
  "A 1x1 PNG, base64."
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=")

(def id-a
  "42c8f147-72f8-4dcc-bbf8-6e5039770d34")

(def id-b
  "45a549c4-5c50-4113-8d9e-a3114f8a81bb")

(defn- session-lines
  "A session pi wrote: the header, a user message (with a picture when
  `image?`), the answer."
  [id image? text]
  (map json/generate-string
       [{:type "session" :version 3 :id id :timestamp "2026-10-10T08:32:22.664Z" :cwd "/w"}
        {:type "message" :id "m1" :parentId nil :timestamp 1760084000000
         :message {:role "user"
                   :content (cond-> [{:type "text" :text text}]
                              image? (conj {:type "image" :data png :mimeType "image/png"}))}}
        {:type "message" :id "m2" :parentId "m1" :timestamp 1760084001000
         :message {:role "assistant" :content [{:type "text" :text "Done."}] :stopReason "stop"
                   :usage {:cost {:total 0.001}}}}]))

(defn- write!
  [workspace path content]
  (let [file (io/file workspace path)]
    (io/make-parents file)
    (spit file content)
    file))

(defn- old-workspace!
  "Two old sessions, one with a picture and a clip; a stamped project
  with its video, an unstamped page; a name, a hook, a share."
  []
  (let [workspace (str (fs/create-temp-dir {:prefix "pchat-migrate"}))]
    (write! workspace (str ".pchat/sessions/2026-10-10T08-32-22-664Z_" id-a ".jsonl")
            (str/join "\n" (session-lines id-a true "Make a card\n(Attached video: uploads/ab12.mp4)")))
    (write! workspace (str ".pchat/sessions/2026-10-11T09-00-00-000Z_" id-b ".jsonl")
            (str/join "\n" (session-lines id-b false "Reply with pong")))
    (write! workspace "uploads/ab12.mp4" "clip")
    (write! workspace ".pchat/looks/other.png" "x")
    (write! workspace "artifacts/card/bb.edn" "{}")
    (write! workspace "artifacts/card/src/artifact/main.clj" "(ns artifact.main)")
    (write! workspace "artifacts/card.mp4" "video")
    (write! workspace "artifacts/notes.html" "<p>notes</p>")
    (write! workspace ".pchat/artifact-owners.edn" (pr-str {"card" {:chat id-a :at 1} "card.mp4" {:chat id-a :at 2}}))
    (write! workspace ".pchat/names.edn" (pr-str {id-a "Pool card"}))
    (write! workspace ".pchat/tasks.edn" (pr-str {id-b :free}))
    (write! workspace ".pchat/hooks.edn" (pr-str {id-a {:secret "abc" :created 1}}))
    (write! workspace ".pchat/runs/42c8f147-72f8-4dcc-bbf8-6e5039770d34.edn" (pr-str [{:n 1 :exit 0}]))
    (write! workspace "hooks/42c8f147-72f8-4dcc-bbf8-6e5039770d34.clj" "(println 1)")
    (write! workspace ".pchat/shares.edn" (pr-str {"notes.html" "0123456789abcdef0123" "card.mp4" "fedcba9876543210fedc"}))
    ;; what pchat installs at start: the script and the lib it loads
    (write! workspace "bin/migrate-chats" (slurp "resources/pchat/bin/migrate-chats"))
    (write! workspace ".pchat/lib/pchat/chats.clj" (slurp "src/pchat/chats.clj"))
    workspace))

(defn- run!
  [workspace]
  (let [{:keys [exit out err]} @(process/process ["bb" "bin/migrate-chats"]
                                                 {:dir workspace :out :string :err :string})]
    (is (zero? exit) (str out err))
    (str/split-lines (str out))))

(deftest script-test
  (let [workspace (old-workspace!)
        chats-dir (io/file workspace "chats")
        handle (migrate/handle {:db (atom {}) :workspace workspace})]
    (is (migrate/needed? handle))
    (let [lines (run! workspace)
          ids (chats/ids chats-dir)
          earlier (some #(when (= "Earlier artifacts" (:name (chats/meta chats-dir %))) %) ids)]
      (is (= "3 chats brought back" (last lines)))
      (is (= 3 (count ids)))
      (testing "a session is a chat with its file, name, task, picture, clip and hook"
        (let [file (first (session/files (chats/session-dir chats-dir id-a)))]
          (is (= id-a (session/id-of file)) "pi's file name kept")
          (is (= id-a (:id (session/summary file))))
          (is (empty? (fs/list-dir (io/file workspace ".pchat" "sessions"))) "the old place stays, empty")
          (is (= {:name "Pool card"} (chats/meta chats-dir id-a)))
          (is (= {:task :free} (chats/meta chats-dir id-b)))
          (let [[picture] (map fs/file-name (fs/list-dir (chats/uploads-dir chats-dir id-a) "*.png"))
                chat (session/load-chat file #(looks/intern-message (chats/uploads-dir chats-dir id-a) (str "/chat/" id-a "/uploads") %))]
            (is (re-matches #"[0-9a-f]{32}\.png" picture) "the picture of the session, named by its content")
            (is (= [(str "/chat/" id-a "/uploads/" picture)] (:images (first (:items chat))))
                "the page finds it under the chat"))
          (is (.isFile (io/file (chats/uploads-dir chats-dir id-a) "ab12.mp4")) "the clip the message names")
          (is (not (.exists (io/file workspace "uploads" "ab12.mp4"))))
          (is (= {:secret "abc" :created 1} (edn/read-string (slurp (io/file chats-dir id-a ".hook.edn")))))
          (is (= [{:n 1 :exit 0}] (edn/read-string (slurp (io/file chats-dir id-a ".runs.edn")))))
          (is (= "(println 1)" (slurp (io/file chats-dir id-a "hook.clj"))))
          (is (.isFile (io/file workspace ".pchat" "looks" "other.png")) "nothing deleted")))
      (testing "the stamped artifacts are the chat's, the rest one chat's"
        (is (.isFile (io/file (chats/artifacts-dir chats-dir id-a) "card" "bb.edn")))
        (is (.isFile (io/file (chats/artifacts-dir chats-dir id-a) "card.mp4")))
        (is (some? earlier))
        (is (chats/valid-id? earlier))
        (is (.isFile (io/file (chats/artifacts-dir chats-dir earlier) "notes.html")))
        (is (empty? (remove #(str/starts-with? (fs/file-name %) ".") (fs/list-dir (io/file workspace "artifacts"))))
            "the old folder stays, empty"))
      (testing "the shares follow the files"
        (is (= {"card.mp4" "fedcba9876543210fedc"} (edn/read-string (slurp (io/file chats-dir id-a ".shares.edn")))))
        (is (= {"notes.html" "0123456789abcdef0123"} (edn/read-string (slurp (io/file chats-dir earlier ".shares.edn"))))))
      (testing "done, and a second run finds nothing to do"
        (is (.isFile (io/file workspace ".pchat" "migrated-at")))
        (is (not (migrate/needed? handle)))
        (is (= "0 chats brought back" (last (run! workspace))))
        (is (= ids (chats/ids chats-dir)))))))

(defn- strings
  [hiccup]
  (filter string? (tree-seq coll? seq hiccup)))

(deftest banner-test
  (testing "nothing to bring back, no banner"
    (is (nil? (view/migrate-banner nil)))
    (is (nil? (view/migrate-banner {:needed? false}))))
  (testing "the offer, the run, the result, the failure"
    (is (= :migrate/found (get-in (view/migrate-banner {:needed? true}) [1 1])))
    (is (= [:i18n :migrate/running {:progress "picture x of y"}]
           (get-in (view/migrate-banner {:needed? true :state :running :progress "picture x of y"}) [1])))
    (is (= [:i18n :migrate/done {:count 3}]
           (get-in (view/migrate-banner {:state :done :count 3}) [1])))
    (is (= [:i18n :migrate/failed {:error "boom"}]
           (get-in (view/migrate-banner {:needed? true :state :failed :error "boom"}) [1])))
    (is (= :div.banner.migrate.failed (first (view/migrate-banner {:state :failed :error "boom"}))))))

(deftest needed-test
  (let [workspace (str (fs/create-temp-dir {:prefix "pchat-migrate"}))
        handle (migrate/handle {:db (atom {}) :workspace workspace})]
    (is (not (migrate/needed? handle)) "a fresh workspace")
    (write! workspace "artifacts/.keep" "")
    (is (not (migrate/needed? handle)) "hidden files are nothing")
    (write! workspace "artifacts/x.html" "")
    (is (migrate/needed? handle))
    (write! workspace ".pchat/migrated-at" "now")
    (is (not (migrate/needed? handle)) "done once is done")
    (migrate/check! handle)
    (is (false? (get-in @(:db handle) [:migration :needed?])))))
