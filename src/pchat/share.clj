(ns pchat.share
  "A link anyone can open, for one artifact file: `/share/<id>` serves
  the file at its path under artifacts/, as it is at the time, without
  the token of the page. A video the agent renders again is new behind
  the same link. The ids live in `.pchat/shares.edn`, `{path id}`;
  ending a link forgets its id. Projects are not shared, only files.

  The db key this namespace owns:

      :shares {path id}"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rframes.http :as http]))

(def path-prefix
  "/share")

(defn handle
  "{:db the app's atom, :dir the artifacts directory, :file the edn file
  the links are kept in}"
  [{:keys [dir file] :as opts}]
  (assoc opts :dir (io/file dir) :file (io/file file)))

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
  "The links of the file into the db."
  [{:keys [db file]}]
  (swap! db assoc :shares (read-shares file)))

(defn- new-id
  "Twenty hex characters nobody guesses."
  []
  (let [bytes (byte-array 10)]
    (.nextBytes (java.security.SecureRandom.) bytes)
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn share!
  "The id of the link for `path`, made now when there is none."
  [{:keys [db file]} path]
  (let [shares (read-shares file)
        id (or (get shares path) (new-id))
        shares (assoc shares path id)]
    (write-shares! file shares)
    (swap! db assoc :shares shares)
    id))

(defn unshare!
  [{:keys [db file]} path]
  (let [shares (dissoc (read-shares file) path)]
    (write-shares! file shares)
    (swap! db assoc :shares shares)
    nil))

(defn link
  "The link of a share id on this server."
  [base-url id]
  (str base-url path-prefix "/" id))

(defn base-url
  "Where this page lives, for the request that asked for it: the proxy's
  scheme and host when there is one."
  [{:keys [scheme headers server-name server-port]}]
  (let [proto (or (get headers "x-forwarded-proto") (some-> scheme name) "http")
        host (or (get headers "x-forwarded-host") (get headers "host")
                 (str server-name (when server-port (str ":" server-port))))]
    (str proto "://" host)))

(defn- path-of
  "The artifact path of a share id, when the link exists."
  [{:keys [db]} id]
  (some (fn [[path share-id]]
          (when (= id share-id)
            path))
        (:shares @db)))

(defn handler
  "GET /share/<id>: the file, or nothing for an id that is not a link."
  [{:keys [dir] :as handle} w]
  (let [id (str/replace (str (get-in w [:ring/route-params :*])) #"/.*$" "")
        path (when (re-matches #"[0-9a-f]{20}" id) (path-of handle id))
        file (when path (http/resolve-file dir path))]
    (assoc w :ring/response
           (if file
             (http/file-response (:ring/request w) file)
             http/not-found))))

(defn register
  "The route, outside the token: a link is its own key."
  [handle]
  [{:ring/route [:get (str path-prefix "/*")]
    :ring/handler (fn [w] (handler handle w))}])
