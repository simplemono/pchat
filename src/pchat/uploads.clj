(ns pchat.uploads
  "Video and audio the person attaches to a message: a podcast clip, a
  voice memo, a screen recording. Images stay rchat's, scaled in the
  browser and sent to the model as image blocks; a clip is too big for
  that and means nothing to the model as bytes. It is stored under
  `uploads/` in the workspace, and the message names its path, so the
  agent works with the file like with any other.

  POST /upload takes both: an image goes on to rchat's handler, a clip is
  written to disk as it comes. The browser sends a large file in pieces,
  `?id=<hex>&offset=<n>&total=<n>` on each, appended to a hidden part
  file and renamed when the last piece is in; http-kit holds a whole
  request body in memory, the pieces keep that small. A clip in one
  request (no `id`) is named by the hash of its bytes. GET /uploads/*
  serves the files, with ranges for the players."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rchat.upload :as upload]
            [rframes.http :as http]
            [rframes.transit :as transit]))

(def path-prefix
  "/uploads")

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

(defn- query-params
  [request]
  (into {}
        (keep (fn [pair]
                (let [[k v] (str/split pair #"=" 2)]
                  (when (seq k)
                    [k (or v "")]))))
        (str/split (str (:query-string request)) #"&")))

(defn- body
  "The body of the request as a stream, an empty one when it has none."
  [w]
  (or (:body (:ring/request w))
      (java.io.ByteArrayInputStream. (byte-array 0))))

(defn- respond
  [w status value]
  (assoc w
         :ring/response
         {:status status
          :headers {"Content-Type" "application/transit+json"}
          :body (transit/write-str value)}))

(defn- stored
  "The answer for a file that is complete."
  [file]
  (let [file-name (.getName (io/file file))]
    {:name file-name
     :url (str path-prefix "/" file-name)
     :kind (kind file-name)
     :size (.length (io/file file))}))

(defn- hex
  [^bytes bytes]
  (apply str (map #(format "%02x" (bit-and % 0xff)) (take 16 bytes))))

(defn- copy-counted!
  "Copies `in` to the end of `file`, at most `limit` bytes in all, and
  returns the bytes it holds then, or nil when it would grow past
  `limit`. `digest` sees every byte when given."
  [in file limit digest]
  (let [buffer (byte-array 65536)]
    (with-open [out (java.io.FileOutputStream. (io/file file) true)]
      (loop [length (.length (io/file file))]
        (let [n (.read in buffer)]
          (cond
            (neg? n) length
            (< limit (+ length n)) nil
            :else (do (.write out buffer 0 n)
                      (some-> digest (.update buffer 0 n))
                      (recur (+ length n)))))))))

(defn- whole!
  "A clip in one request, named by the hash of its bytes."
  [{:keys [dir max-bytes]} w extension]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        part (io/file dir (str "." (random-uuid) ".part"))]
    (io/make-parents part)
    (.delete part)
    (let [length (copy-counted! (body w) part max-bytes digest)]
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
          (respond w 200 (stored file)))))))

(defn- piece!
  "One piece of a clip sent in pieces: it must start where the part file
  ends; the last one completes the file."
  [{:keys [dir max-bytes]} w extension {:strs [id offset total]}]
  (let [offset (parse-long (str offset))
        total (parse-long (str total))
        part (io/file dir (str "." id "." extension ".part"))
        file (io/file dir (str id "." extension))]
    (cond
      (not (and (re-matches #"[0-9a-f]{16,64}" (str id)) offset total (pos? total)))
      (respond w 400 {:error :bad-piece})

      (< max-bytes total)
      (respond w 413 {:error :too-large})

      (not= offset (if (zero? offset)
                     0
                     (when (.isFile part) (.length part))))
      (respond w 409 {:error :out-of-order
                      :received (if (.isFile part) (.length part) 0)})

      :else
      (do (io/make-parents part)
          (when (zero? offset)
            (.delete part))
          (let [length (copy-counted! (body w) part total nil)]
            (cond
              (nil? length)
              (do (.delete part)
                  (respond w 400 {:error :longer-than-total}))

              (= length total)
              (do (.renameTo part file)
                  (respond w 200 (stored file)))

              :else
              (respond w 200 {:received length})))))))

(defn handler
  "POST /upload: an image to rchat's handler for the looks directory, a
  clip into `dir`, any other type a 415."
  [{:keys [looks-dir] :as opts} w]
  (let [request (:ring/request w)
        type (content-type request)
        opts (merge {:max-bytes max-bytes} opts)
        params (query-params request)]
    (cond
      (upload/extensions type)
      (upload/handler {:looks-dir looks-dir} w)

      (not (media type))
      (respond w 415 {:error :not-media})

      (get params "id")
      (piece! opts w (first (media type)) params)

      :else
      (whole! opts w (first (media type))))))

(defn- file-handler
  [dir w]
  (let [subpath (get-in w [:ring/route-params :*])
        file (when-not (re-find #"(^|/)\." (str subpath))
               (http/resolve-file dir subpath))]
    (assoc w :ring/response
           (if file
             (http/file-response (:ring/request w) file)
             http/not-found))))

(defn register
  "POST /upload and GET /uploads/*."
  [{:keys [dir] :as opts}]
  [{:ring/route [:post "/upload"]
    :ring/handler (fn [w] (handler opts w))}
   {:ring/route [:get (str path-prefix "/*")]
    :ring/handler (fn [w] (file-handler dir w))}])

(def line-pattern
  "A line the server adds to a message for each clip: the label in the
  language of the page, then the path in the workspace."
  #"(?m)^\([^():\n]+: (uploads/[A-Za-z0-9]+\.[a-z0-9]+)\)$")

(defn paths
  "The paths of the clips a message's text names."
  [text]
  (mapv second (re-seq line-pattern (str text))))

(defn without-lines
  "The text of a message without the lines that name clips."
  [text]
  (str/trim (str/replace (str text) line-pattern "")))

(defn attachments
  "What the page shows for the clips a message names:
  `[{:kind :video :url \"/uploads/ab12.mp4\"}]`."
  [text]
  (vec (for [path (paths text)
             :let [file-name (subs path (count "uploads/"))]]
         {:kind (kind file-name)
          :url (str path-prefix "/" file-name)})))
