(ns pchat.chats
  "A chat's folder is its world: `chats/<id>/` under the workspace is the
  working directory of the chat's pi process, with everything the chat
  makes and gets, and nothing of any other chat:

      artifacts/   what the agent makes, shown beside the chat
      uploads/     the pictures and clips the person attached
      tmp/         the temporary files of the agent and its tools, TMPDIR
                   by the short link /tmp/pchat/<id> (`tmp-path`)
      .session/    pi's session file, the history of the chat
      .chat.edn    the name the person gave the chat, and its task
      bin .pchat .pi   links to the shared scripts, templates and lib,
                   and to pi's connections, the same for every chat

  Deleting a chat deletes its folder. The id of a chat is the name of
  its folder, the id of its pi session and the id in its URL,
  `/chat/<id>`. Needs nothing beyond babashka, so that the scripts can
  load it from `.pchat/lib`."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn new-id
  "A UUID of version 7: the time in milliseconds first, random bits
  after, so that the ids of the chats sort by the time they were made.
  No library: the 48 bits of the time, the version and variant bits,
  74 random bits."
  []
  (let [now (System/currentTimeMillis)
        random (java.security.SecureRandom.)
        high (bit-or (bit-shift-left now 16)
                     0x7000
                     (.nextInt random 0x1000))
        low (bit-or Long/MIN_VALUE
                    (bit-and (.nextLong random) 0x3FFFFFFFFFFFFFFF))]
    (str (java.util.UUID. high low))))

(defn valid-id?
  "A chat id is a UUID; nothing else names a folder under chats/, so
  that an id from a URL cannot leave it."
  [id]
  (boolean (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" (str id))))

(defn dir
  "The folder of the chat."
  [chats-dir id]
  (io/file chats-dir (str id)))

(defn artifacts-dir
  [chats-dir id]
  (io/file chats-dir (str id) "artifacts"))

(defn uploads-dir
  [chats-dir id]
  (io/file chats-dir (str id) "uploads"))

(defn tmp-dir
  [chats-dir id]
  (io/file chats-dir (str id) "tmp"))

(def short-tmp-root
  "Where a chat's tmp/ is linked from by a short path. A program that
  puts a socket into TMPDIR fails when the socket's path is longer than
  107 bytes: Chrome's `<TMPDIR>/com.google.Chrome.XXXXXX/SingletonSocket`
  under chats/<id>/tmp/ of a sprite's workspace is 116, so no render and
  no tool that starts a Chrome would run."
  "/tmp/pchat")

(defn tmp-link
  [id]
  (fs/path short-tmp-root (str id)))

(defn tmp-path
  "TMPDIR for the chat's processes: /tmp/pchat/<id>, a link to its tmp/
  folder, made or mended now; the folder itself when no link can be
  made there."
  [chats-dir id]
  (let [target (fs/absolutize (tmp-dir chats-dir id))
        link (tmp-link id)]
    (try
      (fs/create-dirs target)
      (fs/create-dirs short-tmp-root)
      (when-not (and (fs/sym-link? link)
                     (= (str (fs/read-link link)) (str target)))
        (fs/delete-if-exists link)
        (fs/create-sym-link link target))
      (str link)
      (catch Exception _
        (str target)))))

(defn session-dir
  [chats-dir id]
  (io/file chats-dir (str id) ".session"))

(defn meta-file
  [chats-dir id]
  (io/file chats-dir (str id) ".chat.edn"))

(defn url
  "The page of the chat."
  [id]
  (str "/chat/" id))

(def links
  "What a chat's folder links to in the workspace above: the scripts
  (`bin/new`, `bin/render`, ...), the templates and the lib under
  `.pchat/`, and `.pi/` with the connections pi reads when the chat's
  process starts. One change there reaches every chat."
  ["bin" ".pchat" ".pi"])

(def local-only
  "What a chat's folder holds that is this machine's, not the chat's:
  the temporary files of its processes and the ids of its share links
  (`pchat.share`), which resolve on this server alone. With the links
  (`links`, and every other symlink) it is what a copy of the chat for
  another place leaves behind: the template of a job (`pchat.jobs`),
  the export (`pchat.export`)."
  #{"tmp" ".shares.edn"})

(defn create!
  "The folder of the chat with its subfolders and links, the parts that
  are missing. Answers the id."
  [workspace id]
  (let [chat (dir (io/file workspace "chats") id)]
    (doseq [sub ["artifacts" "uploads" "tmp" ".session"]]
      (.mkdirs (io/file chat sub)))
    (doseq [name links]
      (let [link (io/file chat name)]
        (when-not (fs/exists? link {:nofollow-links true})
          (fs/create-sym-link link (str "../../" name)))))
    id))

(defn ids
  "The chats that have a folder."
  [chats-dir]
  (->> (.listFiles (io/file chats-dir))
       (filter #(.isDirectory %))
       (map #(.getName %))
       (filter valid-id?)
       (sort)
       (vec)))

(defn meta
  "What `.chat.edn` holds: `:name`, `:task`."
  [chats-dir id]
  (let [file (meta-file chats-dir id)]
    (if (.isFile file)
      (try
        (edn/read-string (slurp file))
        (catch Exception _
          {}))
      {})))

(defn update-meta!
  "`.chat.edn` after `f`; the file goes when nothing is left."
  [chats-dir id f]
  (let [file (meta-file chats-dir id)
        value (into {} (remove (comp nil? val)) (f (meta chats-dir id)))]
    (if (seq value)
      (do (io/make-parents file)
          (spit file (pr-str value)))
      (io/delete-file file true))
    value))

(defn write-session!
  "pi's session file `file` as the session of the chat `id`: written
  into the chat's `.session/` as `<timestamp>_<id>.jsonl`, the way pi
  names it, with the id and the chat's folder as cwd in its header,
  the rest as it is. For a chat made from another one: a job's
  conversation (`pchat.jobs`), an import (`pchat.import`). Answers the
  file written, nil when `file` is no session file of pi (not named
  like one, or without the header)."
  [chats-dir id file]
  (let [file (io/file file)
        name (.getName file)
        [header & lines] (str/split-lines (slurp file))
        parsed (try (json/parse-string header true) (catch Exception _ nil))]
    (when (and (re-find #"_[0-9a-f-]{36}\.jsonl$" name)
               (= "session" (:type parsed)))
      (let [target (io/file (session-dir chats-dir id) (str (subs name 0 (- (count name) 43)) "_" id ".jsonl"))
            header (json/generate-string (assoc parsed :id id :cwd (str (dir chats-dir id))))]
        (io/make-parents target)
        (spit target (str (str/join "\n" (cons header lines)) "\n"))
        target))))

(defn delete!
  "The folder of the chat, gone with everything in it; the links are
  removed as links, what they point to stays."
  [chats-dir id]
  (let [chat (dir chats-dir id)]
    (when (and (valid-id? id) (.isDirectory chat))
      (fs/delete-tree chat)
      (when (fs/sym-link? (tmp-link id))
        (fs/delete-if-exists (tmp-link id))))))

(comment
  (new-id)
  (create! "/tmp/pchat-ws" (new-id))
  (ids "/tmp/pchat-ws/chats")
  )
