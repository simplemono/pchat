(ns pchat.pieces
  "A large file sent in pieces, so that http-kit, which holds a whole
  request body in memory, never holds more than one piece of it: the
  browser (`pchat.client/send-in-pieces!`) POSTs the pieces one after
  the other, `?id=<hex>&offset=<n>&total=<n>` on each; a piece is
  appended to a hidden part file, which gets its name when the last
  one is in. For the clips of a message (`pchat.uploads`) and the zip
  of an import (`pchat.import`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn query-params
  "The parameters of the request's query string, `{\"id\" \"…\"}`."
  [request]
  (into {}
        (keep (fn [pair]
                (let [[k v] (str/split pair #"=" 2)]
                  (when (seq k)
                    [k (or v "")]))))
        (str/split (str (:query-string request)) #"&")))

(defn body
  "The body of the request as a stream, an empty one when it has none."
  [w]
  (or (:body (:ring/request w))
      (java.io.ByteArrayInputStream. (byte-array 0))))

(defn copy-counted!
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

(defn receive!
  "One piece, the body of the request in `w`, for `{:dir where the
  file lands, :extension its extension, :max-bytes the most a file may
  have}`: appended to `.<id>.<extension>.part` in `dir`, which becomes
  `<id>.<extension>` with the last piece. Answers `{:file f}` then,
  `{:received n}` before, or `{:status n :error kw}` for a piece that
  is refused: 400 `:bad-piece` (no id of 16 to 64 hex digits, no
  offset, no total), 413 `:too-large`, 409 `:out-of-order` with the
  bytes `:received` (a piece must start where the part file ends),
  400 `:longer-than-total`."
  [{:keys [dir extension max-bytes]} w]
  (let [{:strs [id offset total]} (query-params (:ring/request w))
        offset (parse-long (str offset))
        total (parse-long (str total))
        part (io/file dir (str "." id "." extension ".part"))
        file (io/file dir (str id "." extension))]
    (cond
      (not (and (re-matches #"[0-9a-f]{16,64}" (str id)) offset total (pos? total)))
      {:status 400 :error :bad-piece}

      (< max-bytes total)
      {:status 413 :error :too-large}

      (not= offset (if (zero? offset)
                     0
                     (when (.isFile part) (.length part))))
      {:status 409
       :error :out-of-order
       :received (if (.isFile part) (.length part) 0)}

      :else
      (do (io/make-parents part)
          (when (zero? offset)
            (.delete part))
          (let [length (copy-counted! (body w) part total nil)]
            (cond
              (nil? length) (do (.delete part)
                                {:status 400 :error :longer-than-total})
              (= length total) (do (.renameTo part file)
                                   {:file file})
              :else {:received length}))))))
