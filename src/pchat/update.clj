(ns pchat.update
  "A newer pchat, offered to the person and installed by their click.
  Once a day, and at start, the newest release is looked up with one
  HEAD on `latest-url`, which GitHub answers with a redirect to the
  release's tag page: no API call, no token, so machines behind shared
  addresses never run into a rate limit. A newer version shows as a
  banner with a button. The click downloads the release's jar next to
  the running one, runs it once with `--version` (the file must answer
  the expected version, which catches a cut-off or wrong download),
  waits until no chat works and no video renders, puts the new jar in
  place and ends the process with `exit-code`: whatever supervises the
  process starts it again with the new jar (a sprite's runtime restarts
  a service whose process exited by itself, within a second), and the
  page is back in seconds. Nothing updates by itself.

  The running jar is `PCHAT_JAR`, which the start script sets where a
  supervisor will start the process again; a pchat started any other
  way (from the sources, `bb pchat.jar` by hand, an older start script)
  shows no banner, since nothing would start it again.
  `PCHAT_UPDATE_URL` points the lookup at another address (the download
  comes from the same base), for tests.

  The db key this namespace owns:

      :update {:current \"0.1.3\" :available \"0.1.4\" :offered true
               :state :idle | :downloading | :ready | :failed
               :error \"...\"}

  `:ready` is the new jar checked and waiting for the work to end."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def exit-code
  "What the process ends with once the new jar is in place: the start
  script starts it again on this code and stops on any other."
  75)

(def latest-url
  "https://github.com/storrito/pchat/releases/latest")

(def check-interval-ms
  (* 24 60 60 1000))

(defn current-version
  "The version built into the jar (`bb jar` writes it), `dev` from the
  sources."
  []
  (or (some-> (io/resource "pchat/version.txt") slurp str/trim not-empty)
      "dev"))

(defn running-jar
  "The jar to replace, from `PCHAT_JAR` as the start script sets it;
  nil without it, and then nothing is offered."
  [path]
  (when (seq (str path))
    (io/file path)))

(defn download-url
  "The release's jar, from the same base as `latest-url`."
  [latest-url version]
  (str/replace latest-url #"/releases/latest/?$" (str "/releases/download/v" version "/pchat.jar")))

(defn handle
  "`{:db atom, :jar the running jar, :current its version, :latest-url,
  :bb the babashka to run the check with, :idle? a function answering
  whether the process may end now, :exit! what ends it}`; everything
  but `:db` has a default."
  [opts]
  (let [handle (merge {:latest-url (or (System/getenv "PCHAT_UPDATE_URL") latest-url)
                       :bb (or (System/getenv "BB") "bb")
                       :idle? (constantly true)
                       :exit! (fn [code] (System/exit code))
                       :current (current-version)
                       :jar (running-jar (System/getenv "PCHAT_JAR"))}
                      opts)]
    ;; The running version is on the page from the first frame on
    ;; (the sidebar shows it next to the name), before any lookup.
    (swap! (:db handle) assoc-in [:update :current] (:current handle))
    handle))

(defn version-of-location
  "The version in GitHub's redirect to a release:
  `.../releases/tag/v0.1.4` → `0.1.4`."
  [location]
  (second (re-find #"/releases/tag/v?([^/?#]+)" (str location))))

(defn parse-version
  "`0.1.4` → `[0 1 4]`; what is no number counts as 0."
  [version]
  (mapv #(or (parse-long %) 0) (str/split (str version) #"[.-]")))

(defn newer?
  [available current]
  (let [a (parse-version available)
        c (parse-version current)
        n (max (count a) (count c))
        pad (fn [v] (into v (repeat (- n (count v)) 0)))]
    (pos? (compare (pad a) (pad c)))))

(defn check!
  "Looks the newest release up and notes it in the db; answers the
  version found, nil when the lookup failed (the db keeps what it had)."
  [{:keys [db latest-url current jar]}]
  (let [found (try
                (let [client (http/client {:follow-redirects :never})
                      response (http/head latest-url {:client client :throw false :timeout 10000})]
                  (version-of-location (get-in response [:headers "location"])))
                (catch Exception e
                  (binding [*out* *err*]
                    (println "[update]" (str e)))
                  nil))]
    (swap! db update :update
           (fn [state]
             (cond-> (assoc (or state {}) :current current)
               found (assoc :available found
                            :offered (boolean (and jar (newer? found current)))))))
    found))

(defn check-now!
  "The person asks for a lookup right now (Settings): `check!` with
  its bookkeeping in the db, `:checking` while it runs, then
  `:checked-at` (millis) and `:lookup`, `:ok` or `:failed`. Answers
  what `check!` answers."
  [{:keys [db] :as handle}]
  (swap! db update :update assoc :checking true)
  (let [found (check! handle)]
    (swap! db update :update assoc
           :checking false
           :checked-at (System/currentTimeMillis)
           :lookup (if found :ok :failed))
    found))

(defn- check-run
  "Runs the downloaded jar once with `--version`; nil when it answers
  `version`, else what went wrong."
  [bb new-jar version]
  (let [proc (process/process {:out :string :err :string} bb (str new-jar) "--version")
        result (deref proc 60000 ::timeout)]
    (if (= ::timeout result)
      (do (process/destroy-tree proc)
          "the new jar did not answer in time")
      (let [{:keys [exit out]} result
            answer (str/trim (str out))]
        (cond
          (not= 0 exit) (str "the new jar failed (exit " exit ")")
          (not= version answer) (str "the new jar answers " (if (str/blank? answer) "nothing" answer) ", not " version)
          :else nil)))))

(defn new-jar-file
  "Where the download lands, next to the running jar: `pchat.new.jar`
  for `pchat.jar`. The name has to end in `.jar`, since babashka runs a
  file by its extension and would read `pchat.jar.new` as a script."
  [jar]
  (let [path (str jar)]
    (io/file (if (str/ends-with? path ".jar")
               (str (subs path 0 (- (count path) 4)) ".new.jar")
               (str path ".new.jar")))))

(defn download!
  "The release's jar as `new-jar-file`; answers the file."
  [{:keys [jar latest-url]} version]
  (let [new-jar (new-jar-file jar)]
    (with-open [in (:body (http/get (download-url latest-url version) {:as :stream :timeout 600000}))]
      (io/copy in new-jar))
    new-jar))

(defn- fail!
  [db message]
  (swap! db update :update assoc :state :failed :error message))

(defn update!
  "The click: the download and the check in the background, then the
  swap and the exit once `idle?` says so. A second click while that
  runs does nothing."
  [{:keys [db jar bb idle? exit!] :as handle}]
  (let [version (get-in @db [:update :available])]
    (when (and jar version (not (#{:downloading :ready} (get-in @db [:update :state]))))
      (swap! db update :update assoc :state :downloading :error nil)
      (future
        (try
          (let [new-jar (download! handle version)]
            (if-let [problem (check-run bb new-jar version)]
              (do (fs/delete-if-exists new-jar)
                  (fail! db problem))
              (do (swap! db update :update assoc :state :ready)
                  (loop []
                    (if (idle?)
                      (do (fs/move new-jar jar {:replace-existing true :atomic-move true})
                          (exit! exit-code))
                      (do (Thread/sleep 2000)
                          (recur)))))))
          (catch Exception e
            (fs/delete-if-exists (new-jar-file jar))
            (fail! db (or (ex-message e) (str e))))))
      :started)))

(defn watch!
  "The lookup at start and then once a day, a few minutes apart from
  the other machines. Nothing when there is no jar to replace."
  [{:keys [jar] :as handle}]
  (when jar
    (future
      (loop []
        (check! handle)
        (Thread/sleep (+ check-interval-ms (rand-int (* 5 60 1000))))
        (recur)))))
