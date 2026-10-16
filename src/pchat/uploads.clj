(ns pchat.uploads
  "Video and audio the person attaches to a message: a podcast clip, a
  voice memo, a screen recording. Images stay rchat's, scaled in the
  browser and sent to the model as image blocks; a clip is too big for
  that and means nothing to the model as bytes. It is stored under
  `uploads/` in the chat's folder, and the message names its path, so the
  agent works with the file like with any other.

  POST /chat/<id>/upload takes both into the chat's `uploads/` folder:
  an image content-addressed, the way rchat stores it, a clip written
  to disk as it comes. The browser sends a large file in pieces
  (`pchat.pieces`), `?id=<hex>&offset=<n>&total=<n>` on each. A clip in
  one request (no `id`) is named by the hash of its bytes. GET
  /chat/<id>/uploads/* serves the files, with ranges for the players."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [pchat.pieces :as pieces]
            [rchat.upload :as upload]
            [rframes.http :as http]
            [rframes.transit :as transit]))

(def max-bytes
  "The largest clip, a gigabyte."
  (* 1024 1024 1024))

(def media
  "The media types of a clip, with the extension of the stored file and
  its kind. The browser names some of them in more than one way."
  {"video/mp4" ["mp4" :video]
   "video/quicktime" ["mov" :video]
   "video/webm" ["webm" :video]
   "video/x-matroska" ["mkv" :video]
   "audio/mpeg" ["mp3" :audio]
   "audio/mp3" ["mp3" :audio]
   "audio/wav" ["wav" :audio]
   "audio/x-wav" ["wav" :audio]
   "audio/wave" ["wav" :audio]
   "audio/vnd.wave" ["wav" :audio]
   "audio/mp4" ["m4a" :audio]
   "audio/x-m4a" ["m4a" :audio]
   "audio/aac" ["aac" :audio]
   "audio/ogg" ["ogg" :audio]
   "audio/flac" ["flac" :audio]
   "audio/x-flac" ["flac" :audio]})

(def kinds
  "The kind of a stored clip by its extension."
  (into {} (vals media)))

(defn kind
  "`:video` or `:audio` for the file name of a clip, else nil."
  [file-name]
  (kinds (str/lower-case (or (second (re-find #"\.([^./]+)$" (str file-name))) ""))))

(defn- content-type
  [request]
  (some-> (get-in request [:headers "content-type"])
          (str/split #";")
          (first)
          (str/trim)
          (str/lower-case)))

(defn- respond
  [w status value]
  (assoc w
         :ring/response
         {:status status
          :headers {"Content-Type" "application/transit+json"}
          :body (transit/write-str value)}))

(defn- stored
  "The answer for a file that is complete."
  [url-prefix file]
  (let [file-name (.getName (io/file file))]
    {:name file-name
     :url (str url-prefix "/" file-name)
     :kind (kind file-name)
     :size (.length (io/file file))}))

(defn- hex
  [^bytes bytes]
  (apply str (map #(format "%02x" (bit-and % 0xff)) (take 16 bytes))))

(defn- whole!
  "A clip in one request, named by the hash of its bytes."
  [{:keys [dir max-bytes url-prefix]} w extension]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        part (io/file dir (str "." (random-uuid) ".part"))]
    (io/make-parents part)
    (.delete part)
    (let [length (pieces/copy-counted! (pieces/body w) part max-bytes digest)]
      (cond
        (nil? length)
        (do (.delete part)
            (respond w 413 {:error :too-large}))

        (zero? length)
        (do (.delete part)
            (respond w 400 {:error :empty}))

        :else
        (let [file (io/file dir (str (hex (.digest digest)) "." extension))]
          (if (.isFile file)
            (.delete part)
            (.renameTo part file))
          (respond w 200 (stored url-prefix file)))))))

(defn- piece!
  "One piece of a clip sent in pieces (`pchat.pieces/receive!`): the
  last one completes the file, which the answer names."
  [{:keys [dir max-bytes url-prefix]} w extension]
  (let [{:keys [status file] :as result} (pieces/receive! {:dir dir
                                                           :extension extension
                                                           :max-bytes max-bytes}
                                                          w)]
    (cond
      status (respond w status (dissoc result :status))
      file (respond w 200 (stored url-prefix file))
      :else (respond w 200 result))))

(defn- image!
  "An image, stored the way rchat does it, content-addressed; at most
  rchat's size, the browser scales it before."
  [{:keys [dir url-prefix]} w type]
  (let [bytes (.readAllBytes (pieces/body w))]
    (cond
      (zero? (count bytes))
      (respond w 400 {:error :empty})

      (< upload/max-bytes (count bytes))
      (respond w 413 {:error :too-large})

      :else
      (let [{:keys [name]} (upload/store! dir type bytes)]
        (respond w 200 {:name name
                        :url (str url-prefix "/" name)})))))

(defn handler
  "POST /chat/<id>/upload for `{:dir the chat's uploads folder,
  :url-prefix where it is served}`: an image content-addressed, a clip
  as it comes, any other type a 415."
  [opts w]
  (let [request (:ring/request w)
        type (content-type request)
        opts (merge {:max-bytes max-bytes} opts)]
    (cond
      (upload/extensions type)
      (image! opts w type)

      (not (media type))
      (respond w 415 {:error :not-media})

      (get (pieces/query-params request) "id")
      (piece! opts w (first (media type)))

      :else
      (whole! opts w (first (media type))))))

(defn file-handler
  "GET /chat/<id>/uploads/<name>: the file of the chat's uploads folder
  `dir`, never a hidden one."
  [dir w]
  (let [subpath (get-in w [:ring/route-params :*])
        file (when-not (re-find #"(^|/)\." (str subpath))
               (http/resolve-file dir subpath))]
    (assoc w :ring/response
           (if file
             (http/file-response (:ring/request w) file)
             http/not-found))))

(def line-pattern
  "A line the server adds to a message for each attached file: the label
  in the language of the page, then the path in the chat's folder."
  #"(?m)^\([^():\n]+: (uploads/[A-Za-z0-9]+\.[a-z0-9]+)\)$")

(defn paths
  "The paths of the files a message's text names."
  [text]
  (mapv second (re-seq line-pattern (str text))))

(defn without-lines
  "The text of a message without the lines that name files."
  [text]
  (str/trim (str/replace (str text) line-pattern "")))

(defn attachments
  "The clips a message names, for the page: `[{:kind :video :name
  \"ab12.mp4\"}]`, the file under the chat's uploads folder. A picture
  named in the text is shown from the message's image blocks, not from
  here."
  [text]
  (vec (for [path (paths text)
             :let [file-name (subs path (count "uploads/"))
                   kind (kind file-name)]
             :when kind]
         {:kind kind
          :name file-name})))
