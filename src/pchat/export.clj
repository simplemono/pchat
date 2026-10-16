(ns pchat.export
  "A chat's folder as a zip file, for the person to keep or to take
  elsewhere: `.chat.edn`, `.session/` (pi's history), `artifacts/`,
  `uploads/` and whatever else is in the folder, with their modification
  times, the paths relative to the folder, a directory as an entry of
  its own so that an empty one survives. Not in it: what is this
  machine's (`pchat.chats/local-only`: `tmp/`, `.shares.edn`) and every
  symlink, the links to the shared parts (`bin`, `.pchat`, `.pi`, which
  holds a credential), a job conversation's `template`, whatever the
  agent linked; a link is never followed.

  The zip is built in memory: http-kit holds a response body in memory
  before it sends it, so a stream would save nothing. GET
  /chat/<id>/export.zip (`pchat.main`) answers with `response`."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pchat.chats :as chats]))

(defn- exported?
  "Whether an entry of the chat's folder goes into the zip: no link,
  and at the top nothing of the machine's."
  [entry top?]
  (and (not (fs/sym-link? entry))
       (not (and top? (contains? chats/local-only (.getName entry))))))

(defn entries
  "The files and directories of the chat folder `dir` that the zip
  holds, parents before children, each `{:file :path}`: the path
  relative to the folder with `/` between the parts and at the end of
  a directory's."
  [dir]
  (letfn [(walk [folder prefix top?]
            (for [entry (sort-by #(.getName %) (or (.listFiles (io/file folder)) []))
                  :when (exported? entry top?)
                  :let [name (.getName entry)]
                  item (if (.isDirectory entry)
                         (cons {:file entry :path (str prefix name "/")}
                               (walk entry (str prefix name "/") false))
                         [{:file entry :path (str prefix name)}])]
              item))]
    (vec (walk (io/file dir) "" true))))

(defn- add!
  "One entry into the zip: a directory by its name alone, a file with
  its bytes; the time of both as it is on disk."
  [^java.util.zip.ZipOutputStream zip {:keys [file path]}]
  (let [entry (java.util.zip.ZipEntry. path)]
    (.setLastModifiedTime entry (fs/last-modified-time file))
    (.putNextEntry zip entry)
    (when (.isFile file)
      (java.nio.file.Files/copy (.toPath file) zip))
    (.closeEntry zip)))

(defn zip-bytes
  "The chat folder `dir` as the bytes of a zip file."
  [dir]
  (let [bytes (java.io.ByteArrayOutputStream.)]
    (with-open [zip (java.util.zip.ZipOutputStream. bytes)]
      (doseq [entry (entries dir)]
        (add! zip entry)))
    (.toByteArray bytes)))

(def max-name-chars
  80)

(defn file-name
  "`<name>.zip` for the chat's name: what a file system or a browser
  takes in a file name, a dash for the rest, one blank for a run of
  them, at most `max-name-chars`; `fallback` (the chat's id) when
  nothing is left."
  [name fallback]
  (let [clean (-> (str name)
                  (str/replace #"\s+" " ")
                  (str/replace #"[\\/:*?\"<>|\p{Cntrl}]" "-")
                  (str/trim))
        clean (if (< max-name-chars (count clean))
                (str/trim (subs clean 0 max-name-chars))
                clean)]
    (str (if (str/blank? clean) fallback clean) ".zip")))

(defn- disposition
  "`attachment` with the file name twice: in ASCII for every browser,
  percent-encoded UTF-8 (RFC 6266) for the ones that read it, so that
  an umlaut in the chat's name stays."
  [file-name]
  (let [ascii (str/replace file-name #"[^\x20-\x7E]" "_")
        encoded (-> (java.net.URLEncoder/encode (str file-name) "UTF-8")
                    (str/replace "+" "%20"))]
    (str "attachment; filename=\"" ascii "\"; filename*=UTF-8''" encoded)))

(defn response
  "The ring response that downloads the chat folder `dir` as the file
  `file-name` (see `file-name`)."
  [dir file-name]
  {:status 200
   :headers {"Content-Type" "application/zip"
             "Content-Disposition" (disposition file-name)
             "Cache-Control" "no-store"}
   :body (java.io.ByteArrayInputStream. (zip-bytes dir))})

(comment
  (map :path (entries "workspace/chats/0199dd7e-1f1a-7a2b-8b3c-4d5e6f708192"))
  (file-name "Karte für Oma" "0199dd7e-1f1a-7a2b-8b3c-4d5e6f708192")
  )
