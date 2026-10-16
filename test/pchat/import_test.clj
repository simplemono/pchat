(ns pchat.import-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pchat.chats :as chats]
            [pchat.export :as export]
            [pchat.import :as import]
            [rframes.transit :as transit]))

(defn- temp-dir
  []
  (str (fs/create-temp-dir {:prefix "pchat-import"})))

(def session-prefix
  "2026-10-13T14-41-30-309Z")

(defn- chat-folder!
  "A chat folder like `pchat.chats/create!` makes it, as a run of a job
  with a name and a task: a file, a nested artifact, an empty folder,
  an upload, pi's session file with two entries, and what the export
  leaves out: a tmp file, the share ids and a link to a folder outside."
  [workspace id]
  (chats/create! workspace id)
  (let [dir (chats/dir (io/file workspace "chats") id)]
    (spit (io/file dir "notes.md") "notes")
    (spit (doto (io/file dir "artifacts" "card" "index.html") io/make-parents) "<h1>card</h1>")
    (.mkdirs (io/file dir "artifacts" "empty"))
    (spit (io/file dir "uploads" "ab12.mp4") "clip")
    (spit (io/file dir ".session" (str session-prefix "_" id ".jsonl"))
          (str (json/generate-string {:type "session" :version 3 :id id :timestamp "2026-10-13T14:41:30.309Z" :cwd (str dir)}) "\n"
               (json/generate-string {:type "message" :id "m1" :message {:role "user" :content "Make a card"}}) "\n"))
    (spit (io/file dir ".chat.edn") (pr-str {:name "Pool card" :task "motion" :run {:job "x" :state :done} :job "x"}))
    (spit (io/file dir "tmp" "scratch.txt") "scratch")
    (spit (io/file dir ".shares.edn") (pr-str {"card.mp4" "0123456789abcdef0123"}))
    (fs/create-sym-link (io/file dir "outside") (temp-dir))
    (.setLastModified (io/file dir "notes.md") 1577836800000)
    dir))

(defn- zip-file!
  "A zip of the chat folder on disk."
  [dir]
  (let [file (io/file (temp-dir) "chat.zip")]
    (io/copy (export/zip-bytes dir) file)
    file))

(defn- session-files
  [dir]
  (sort (map #(.getName %) (.listFiles (io/file dir ".session")))))

(deftest import-test
  (let [source (temp-dir)
        workspace (temp-dir)
        old-id (chats/new-id)
        _ (spit (doto (io/file workspace "bin" "new") io/make-parents) "#!/bin/sh\n")
        zip (zip-file! (chat-folder! source old-id))
        id (import/import! workspace zip)
        chats-dir (io/file workspace "chats")
        dir (chats/dir chats-dir id)]
    (testing "a new chat of the workspace, with an id of its own"
      (is (chats/valid-id? id))
      (is (not= old-id id))
      (is (= [id] (chats/ids chats-dir))))
    (testing "the folder as the export had it, the modification times with it"
      (is (= "notes" (slurp (io/file dir "notes.md"))))
      (is (= 1577836800000 (.lastModified (io/file dir "notes.md"))))
      (is (= "<h1>card</h1>" (slurp (io/file dir "artifacts" "card" "index.html"))))
      (is (.isDirectory (io/file dir "artifacts" "empty")) "an empty folder came along")
      (is (= "clip" (slurp (io/file dir "uploads" "ab12.mp4")))))
    (testing "the parts every chat folder has, made fresh: the links and tmp"
      (is (.isFile (io/file dir "bin" "new")) "bin/ of this workspace through the link")
      (is (fs/sym-link? (io/file dir ".pchat")))
      (is (fs/sym-link? (io/file dir ".pi")))
      (is (.isDirectory (io/file dir "tmp")))
      (is (not (.exists (io/file dir "tmp" "scratch.txt"))))
      (is (not (.exists (io/file dir ".shares.edn"))))
      (is (not (.exists (io/file dir "outside")))))
    (testing "the history under the new id: pi's file renamed, its header rewritten, the rest as it was"
      (is (= [(str session-prefix "_" id ".jsonl")] (session-files dir)))
      (let [[header message] (str/split-lines (slurp (io/file dir ".session" (str session-prefix "_" id ".jsonl"))))
            parsed (json/parse-string header true)]
        (is (= id (:id parsed)))
        (is (= (str dir) (:cwd parsed)))
        (is (= "2026-10-13T14:41:30.309Z" (:timestamp parsed)))
        (is (str/includes? message "Make a card"))))
    (testing "the name and the task stay, a job's run becomes a plain chat"
      (is (= {:name "Pool card" :task "motion"} (chats/meta chats-dir id))))
    (testing "the source is untouched"
      (is (= [(str session-prefix "_" old-id ".jsonl")] (session-files (chats/dir (io/file source "chats") old-id)))))))

(defn- zip-of
  "A zip with the entries `[[name text-or-nil]]`, nil for a directory."
  [entries]
  (let [file (io/file (temp-dir) "made.zip")]
    (with-open [zip (java.util.zip.ZipOutputStream. (io/output-stream file))]
      (doseq [[name text] entries]
        (.putNextEntry zip (java.util.zip.ZipEntry. name))
        (when text
          (.write zip (.getBytes (str text) "UTF-8")))
        (.closeEntry zip)))
    file))

(deftest guarded-test
  (let [workspace (temp-dir)
        chats-dir (io/file workspace "chats")]
    (testing "an entry cannot leave the folder, and the links and the machine's own are not taken"
      (let [id (import/import! workspace (zip-of [["artifacts/" nil]
                                                   ["../evil.txt" "evil"]
                                                   ["/etc/evil.txt" "evil"]
                                                   ["a/../../evil2.txt" "evil"]
                                                   ["bin/new" "#!/bin/sh\nrm -rf /\n"]
                                                   [".pi/mcp.json" "{}"]
                                                   ["tmp/x" "x"]
                                                   [".shares.edn" "{}"]
                                                   ["ok.txt" "ok"]]))
            dir (chats/dir chats-dir id)]
        (is (= "ok" (slurp (io/file dir "ok.txt"))))
        (is (not (.exists (io/file chats-dir "evil.txt"))))
        (is (not (.exists (io/file chats-dir "evil2.txt"))))
        (is (not (.exists (io/file workspace "evil.txt"))))
        (is (not (.exists (io/file "/etc/evil.txt"))))
        (is (fs/sym-link? (io/file dir "bin")) "the link, not the zip's bin")
        (is (fs/sym-link? (io/file dir ".pi")))
        (is (not (.exists (io/file dir "tmp" "x"))))
        (is (not (.exists (io/file dir ".shares.edn"))))))
    (testing "a zip without a trace of a chat, and a file that is no zip: refused, no folder left"
      (let [before (chats/ids chats-dir)]
        (is (= :not-a-chat (:error (ex-data (try (import/import! workspace (zip-of [["readme.txt" "hi"]]))
                                                 (catch clojure.lang.ExceptionInfo e e))))))
        (is (contains? #{:not-a-zip :not-a-chat}
                       (:error (ex-data (try (import/import! workspace (doto (io/file (temp-dir) "junk.zip") (spit "this is no zip at all")))
                                             (catch clojure.lang.ExceptionInfo e e))))))
        (is (= before (chats/ids chats-dir)))))))

(defn- post
  "The answer of the handler to a POST of `bytes`: the status and the
  transit body read back."
  [opts bytes query]
  (let [{:keys [status body]} (:ring/response
                               (import/handler opts
                                               {:ring/request {:request-method :post
                                                               :uri "/import"
                                                               :query-string query
                                                               :headers {"content-type" "application/zip"}
                                                               :body (java.io.ByteArrayInputStream. bytes)}}))]
    [status (transit/read-str body)]))

(deftest handler-test
  (let [source (temp-dir)
        workspace (temp-dir)
        added (atom [])
        opts {:workspace workspace
              :max-bytes 1000000
              :add-chat! #(swap! added conj %)}
        bytes (java.nio.file.Files/readAllBytes (.toPath (zip-file! (chat-folder! source (chats/new-id)))))
        total (count bytes)
        cut (quot total 2)
        id "00112233445566778899aabbccddeeff"
        piece (fn [from to]
                (post opts (java.util.Arrays/copyOfRange bytes from to)
                      (str "id=" id "&offset=" from "&total=" total)))]
    (testing "the pieces, then the chat: told to the server, named in the answer, nothing left of the zip"
      (is (= [200 {:received cut}] (piece 0 cut)))
      (is (empty? (chats/ids (io/file workspace "chats"))) "no chat before the last piece")
      (let [[status {:keys [id]}] (piece cut total)]
        (is (= 200 status))
        (is (chats/valid-id? id))
        (is (= [id] @added))
        (is (= [id] (chats/ids (io/file workspace "chats"))))
        (is (empty? (.listFiles (io/file workspace "chats" ".imports"))))))
    (testing "a piece that is refused answers as pieces do"
      (is (= 400 (first (post opts (byte-array 3) "id=../x&offset=0&total=3"))))
      (is (= 413 (first (post {:workspace workspace :max-bytes 10} (byte-array 3) (str "id=" id "&offset=0&total=11"))))))
    (testing "a file that makes no chat: 400 with the reason, nothing left"
      (let [[status answer] (post opts (.getBytes "no zip" "UTF-8") (str "id=" id "&offset=0&total=6"))]
        (is (= 400 status))
        (is (contains? #{:not-a-zip :not-a-chat} (:error answer)))
        (is (= 1 (count (chats/ids (io/file workspace "chats")))))
        (is (empty? (.listFiles (io/file workspace "chats" ".imports"))))))))
