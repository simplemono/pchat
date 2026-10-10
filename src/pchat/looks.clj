(ns pchat.looks
  "The images of a chat as files. The browser uploads what the user pastes,
  drops or attaches to POST /upload (rchat's handler, content-addressed
  into the looks directory); a message names them, the server reads the
  files back as the base64 image blocks pi's `prompt` command takes.

  pi echoes a user message with the base64 inside, and so does its
  session file. `intern` turns such blocks into `{:type \"image\" :name
  :url}` by storing the bytes under their content hash, the same name the
  upload gave them, so the page shows the file and the db never carries
  the bytes."
  (:require [clojure.java.io :as io]
            [rchat.canvas :as canvas]
            [rchat.look :as look]
            [rchat.upload :as upload]))

(def path-prefix
  "/looks")

(defn- decode
  [data]
  (.decode (java.util.Base64/getDecoder) (str data)))

(defn- encode
  [^bytes bytes]
  (.encodeToString (java.util.Base64/getEncoder) bytes))

(defn intern
  "`content` blocks with every image block's data replaced by the name and
  URL of the stored file."
  [looks-dir content]
  (if (string? content)
    content
    (mapv (fn [{:keys [type data mimeType] :as block}]
            (if (and (= "image" type) data)
              (try
                (let [{:keys [name url]} (upload/store! looks-dir mimeType (decode data))]
                  {:type "image"
                   :name name
                   :url url})
                (catch Exception _
                  {:type "image"
                   :name nil
                   :url nil}))
              block))
          content)))

(defn intern-message
  "A pi message with its image blocks interned."
  [looks-dir message]
  (cond-> message
    (sequential? (:content message)) (update :content #(intern looks-dir %))))

(defn blocks
  "The image blocks of pi's `prompt` command for the files `names` in the
  looks directory; a name that is not there is skipped."
  [looks-dir names]
  (for [name names
        :let [file (io/file looks-dir (str name))]
        :when (and (seq (str name))
                   (not (.contains (str name) "/"))
                   (.isFile file))]
    {:type "image"
     :data (encode (.readAllBytes (io/input-stream file)))
     :mimeType (look/media-type file)}))

(defn register
  "POST /upload and GET /looks/*."
  [{:keys [db looks-dir]}]
  (into [{:ring/route [:post "/upload"]
          :ring/handler (fn [w] (upload/handler {:looks-dir looks-dir} w))}]
        (canvas/register (canvas/canvas {:db db
                                         :dir looks-dir
                                         :path path-prefix}))))
