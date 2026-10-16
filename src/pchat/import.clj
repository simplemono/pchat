(ns pchat.import
  "A chat from the zip of `pchat.export`, as a new chat of this
  machine: its folder made like `pchat.chats/create!` makes one, the
  entries of the zip into it with their modification times, the
  history under the new id (`pchat.chats/write-session!`: pi's session
  file named after it, its header rewritten), the name and the task
  from `.chat.edn`; a job's run or conversation becomes a plain chat.
  Not taken: an entry whose path would leave the folder, and at the
  top what is this machine's (`pchat.chats/local-only`) and the names
  of the links (`pchat.chats/links`), which the folder gets fresh. A
  zip without a trace of a chat at the top (`artifacts/`, `uploads/`,
  `.session/` or `.chat.edn`) is refused, and so is a file that is no
  zip; nothing is left behind then.

  POST /import (`pchat.main`) takes the zip in pieces (`pchat.pieces`),
  the part file under `chats/.imports/`, and answers `{:id}` when the
  chat is there."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [pchat.chats :as chats]
            [pchat.pieces :as pieces]
            [pchat.session :as session]
            [rframes.transit :as transit]))

(def skipped
  "The names at the top of the zip that are not taken."
  (into chats/local-only chats/links))

(defn- parts
  "The parts of an entry's path, nil when the path would leave the
  folder (absolute, a `..` or an empty part in it) or when its top is
  skipped."
  [name]
  (let [parts (str/split (str name) #"/")]
    (when (and (seq parts)
               (not (str/starts-with? (str name) "/"))
               (not-any? #(contains? #{"" "." ".."} %) parts)
               (not (contains? skipped (first parts))))
      parts)))

(defn unzip!
  "The entries of the zip stream `in` into the folder `dir`: a directory
  entry as a folder, a file entry as a file, the modification times as
  in the zip (a folder's set at the end, after its files went in).
  Answers the names at the top of the zip, the skipped ones as well."
  [dir ^java.util.zip.ZipInputStream in]
  (loop [tops #{}
         folders []]
    (if-let [entry (.getNextEntry in)]
      (let [name (.getName entry)
            parts (parts name)
            file (when parts
                   (apply io/file dir parts))
            time (.getLastModifiedTime entry)]
        (cond
          (nil? file) nil
          (.isDirectory entry) (.mkdirs file)
          :else (do (io/make-parents file)
                    (io/copy in file)
                    (when time
                      (java.nio.file.Files/setLastModifiedTime (.toPath file) time))))
        (.closeEntry in)
        (recur (conj tops (first (str/split name #"/")))
               (cond-> folders
                 (and file (.isDirectory entry) time) (conj [file time]))))
      (do (doseq [[folder time] folders]
            (java.nio.file.Files/setLastModifiedTime (.toPath folder) time))
          tops))))

(defn- chat-zip?
  "Whether the names at the top of the zip are a chat's."
  [tops]
  (boolean (some tops ["artifacts" "uploads" ".session" ".chat.edn"])))

(defn- adopt-sessions!
  "The session files the zip brought under the chat's `id`: each
  written anew with the id, the old one gone."
  [chats-dir id]
  (doseq [file (session/files (chats/session-dir chats-dir id))]
    (when-let [written (chats/write-session! chats-dir id file)]
      (when-not (= (.getCanonicalPath written) (.getCanonicalPath file))
        (io/delete-file file)))))

(defn import!
  "The zip file as a new chat of the workspace (see the namespace).
  Answers the chat's id; throws an ex-info with `:error` `:not-a-zip`
  or `:not-a-chat`, and the folder is gone again."
  [workspace zip-file]
  (let [id (chats/new-id)
        chats-dir (io/file workspace "chats")
        dir (chats/dir chats-dir id)]
    (chats/create! workspace id)
    (try
      (let [tops (with-open [in (java.util.zip.ZipInputStream. (io/input-stream zip-file))]
                   (unzip! dir in))]
        (when-not (chat-zip? tops)
          (throw (ex-info "No chat in the zip." {:error :not-a-chat})))
        (adopt-sessions! chats-dir id)
        (chats/update-meta! chats-dir id #(dissoc % :run :job))
        id)
      (catch java.util.zip.ZipException e
        (chats/delete! chats-dir id)
        (throw (ex-info (str "Not a zip: " (ex-message e)) {:error :not-a-zip})))
      (catch Exception e
        (chats/delete! chats-dir id)
        (throw e)))))

(defn- respond
  [w status value]
  (assoc w
         :ring/response
         {:status status
          :headers {"Content-Type" "application/transit+json"}
          :body (transit/write-str value)}))

(defn handler
  "POST /import for `{:workspace, :max-bytes (the most a zip may have),
  :add-chat! (a function of the id, called when the chat is there,
  before the answer)}`: a piece of the zip (`pchat.pieces`, the part
  file under `chats/.imports/`) answered with `{:received n}`, the
  last one with `{:id}` of the chat; 400 `{:error :not-a-zip}` or
  `{:error :not-a-chat}` for a file that makes no chat, the statuses
  of `pchat.pieces/receive!` for a piece that is refused."
  [{:keys [workspace max-bytes add-chat!]} w]
  (let [{:keys [status file] :as result} (pieces/receive! {:dir (io/file workspace "chats" ".imports")
                                                           :extension "zip"
                                                           :max-bytes max-bytes}
                                                          w)]
    (cond
      status (respond w status (dissoc result :status))
      (nil? file) (respond w 200 result)
      :else (try
              (let [id (import! workspace file)]
                (when add-chat!
                  (add-chat! id))
                (respond w 200 {:id id}))
              (catch clojure.lang.ExceptionInfo e
                (if-let [error (:error (ex-data e))]
                  (respond w 400 {:error error})
                  (throw e)))
              (finally
                (io/delete-file file true))))))

(comment
  (import! "workspace" "Karte für Oma.zip")
  )
