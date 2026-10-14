(ns pchat.looks
  "The pictures of a chat as files. The browser uploads what the person
  pastes, drops or attaches to POST /chat/<id>/upload (`pchat.uploads`,
  which stores an image content-addressed in the chat's `uploads/`
  folder); a message names them, the server reads the files back as the
  base64 image blocks pi's `prompt` command takes.

  pi echoes a user message with the base64 inside, and so does its
  session file. `intern` turns such blocks into `{:type \"image\" :name
  :url}` by storing the bytes under their content hash, the same name
  the upload gave them, so the page shows the file and the db never
  carries the bytes. A chat's pictures are in its own folder: a chat
  from before 0.2.0 gets them back from its session when it is opened."
  (:require [clojure.java.io :as io]
            [rchat.look :as look]
            [rchat.upload :as upload]))

(defn- decode
  [data]
  (.decode (java.util.Base64/getDecoder) (str data)))

(defn- encode
  [^bytes bytes]
  (.encodeToString (java.util.Base64/getEncoder) bytes))

(defn intern
  "`content` blocks with every image block's data replaced by the name
  and URL of the stored file, under `dir`, served under `url-prefix`."
  [dir url-prefix content]
  (if (string? content)
    content
    (mapv (fn [{:keys [type data mimeType] :as block}]
            (if (and (= "image" type) data)
              (try
                (let [{:keys [name]} (upload/store! dir mimeType (decode data))]
                  {:type "image"
                   :name name
                   :url (str url-prefix "/" name)})
                (catch Exception _
                  {:type "image"
                   :name nil
                   :url nil}))
              block))
          content)))

(defn intern-message
  "A pi message with its image blocks interned."
  [dir url-prefix message]
  (cond-> message
    (sequential? (:content message)) (update :content #(intern dir url-prefix %))))

(defn blocks
  "The image blocks of pi's `prompt` command for the files `names` in
  the chat's uploads folder; a name that is not there is skipped."
  [dir names]
  (for [name names
        :let [file (io/file dir (str name))]
        :when (and (seq (str name))
                   (not (.contains (str name) "/"))
                   (.isFile file))]
    {:type "image"
     :data (encode (.readAllBytes (io/input-stream file)))
     :mimeType (look/media-type file)}))
