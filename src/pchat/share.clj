(ns pchat.share
  "A link anyone can open, for one artifact file: `/share/<id>` serves
  the file at its path under the chat's artifacts/, as it is at the
  time, without the token of the page. A video the agent renders again
  is new behind the same link. The ids of a chat live in `.shares.edn`
  of its folder, `{path id}`; ending a link forgets its id. Projects are
  not shared, only files.

  The key this namespace owns in a chat of the db:

      :shares {path id}"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pchat.chats :as chats]
            [rframes.http :as http]))

(def path-prefix
  "/share")

(defn handle
  "{:db the app's atom, :chats-dir the folder of the chats}"
  [{:keys [chats-dir] :as opts}]
  (assoc opts :chats-dir (io/file chats-dir)))

(defn- file
  [{:keys [chats-dir]} id]
  (io/file (chats/dir chats-dir id) ".shares.edn"))

(defn- read-shares
  [file]
  (if (.isFile file)
    (edn/read-string (slurp file))
    {}))

(defn- write-shares!
  [file shares]
  (io/make-parents file)
  (spit file (pr-str shares)))

(defn load!
  "The links of every chat in the db, from the chats' files."
  [{:keys [db] :as handle}]
  (doseq [id (keys (:chats @db))]
    (swap! db assoc-in [:chats id :shares] (read-shares (file handle id)))))

(defn- new-id
  "Twenty hex characters nobody guesses."
  []
  (let [bytes (byte-array 10)]
    (.nextBytes (java.security.SecureRandom.) bytes)
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn share!
  "The id of the link for `path` of the chat, made now when there is
  none."
  [{:keys [db] :as handle} id path]
  (let [shares (read-shares (file handle id))
        share-id (or (get shares path) (new-id))
        shares (assoc shares path share-id)]
    (write-shares! (file handle id) shares)
    (swap! db assoc-in [:chats id :shares] shares)
    share-id))

(defn unshare!
  [{:keys [db] :as handle} id path]
  (let [shares (dissoc (read-shares (file handle id)) path)]
    (write-shares! (file handle id) shares)
    (swap! db assoc-in [:chats id :shares] shares)
    nil))

(defn link
  "The link of a share id on this server."
  [base-url share-id]
  (str base-url path-prefix "/" share-id))

(defn base-url
  "Where this page lives, for the request that asked for it: the proxy's
  scheme and host when there is one."
  [{:keys [scheme headers server-name server-port]}]
  (let [proto (or (get headers "x-forwarded-proto") (some-> scheme name) "http")
        host (or (get headers "x-forwarded-host") (get headers "host")
                 (str server-name (when server-port (str ":" server-port))))]
    (str proto "://" host)))

(defn- lookup
  "The chat and the artifact path of a share id, when the link exists."
  [{:keys [db]} share-id]
  (some (fn [[id {:keys [shares]}]]
          (some (fn [[path shared]]
                  (when (= share-id shared)
                    [id path]))
                shares))
        (:chats @db)))

(defn handler
  "GET /share/<id>: the file, or nothing for an id that is not a link."
  [{:keys [chats-dir] :as handle} w]
  (let [share-id (str/replace (str (get-in w [:ring/route-params :*])) #"/.*$" "")
        [id path] (when (re-matches #"[0-9a-f]{20}" share-id) (lookup handle share-id))
        file (when path (http/resolve-file (chats/artifacts-dir chats-dir id) path))]
    (assoc w :ring/response
           (if file
             (http/file-response (:ring/request w) file)
             http/not-found))))

(defn register
  "The route, outside the token: a link is its own key."
  [handle]
  [{:ring/route [:get (str path-prefix "/*")]
    :ring/handler (fn [w] (handler handle w))}])
