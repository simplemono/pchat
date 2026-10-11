;; bb smoke [--keep]   one real run of the agent, end to end: pchat starts on
;; a free port with a fresh workspace, gets the wish for a short card with
;; music and its video, and the run passes when an mp4 with a video and an
;; audio stream came out of it. Costs a few cents of model, music and
;; render time; a few minutes. Not a test for CI: a check after a change to
;; the template, the scripts or the prompt. --keep leaves the workspace
;; behind to look at.
(require '[babashka.fs :as fs]
         '[babashka.http-client :as http]
         '[babashka.process :as process]
         '[clojure.string :as str]
         '[pchat.session :as session]
         '[rframes.transit :as transit])

(def wish
  "Make a three-second animated card that says Hello in big letters on a warm background, with one short piece of music under it, then make the video. Decide everything yourself and do not ask me anything.")

(def timeout-ms
  (* 8 60 1000))

(defn free-port
  []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn answers?
  [url]
  (try
    (= 200 (:status (http/get url {:throw false :timeout 2000})))
    (catch Exception _
      false)))

(defn wait-for!
  [what pred ms]
  (let [until (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond
        (pred) true
        (< until (System/currentTimeMillis)) (throw (ex-info (str what " did not happen in " (quot ms 1000) " s") {}))
        :else (do (Thread/sleep 2000) (recur))))))

(defn settled?
  "The agent is done when the last message of the session is its own and
  it stopped for no tool."
  [sessions-dir]
  (when-let [file (first (session/files sessions-dir))]
    (let [{:keys [message]} (last (filter #(= "message" (:type %)) (session/entries file)))]
      (and (= "assistant" (:role message))
           (not= "toolUse" (:stopReason message))))))

(defn streams
  [file]
  (-> (process/shell {:out :string :err :string :continue true}
                     "ffprobe" "-v" "error" "-show_entries" "stream=codec_type" "-of" "csv=p=0" (str file))
      :out
      str/split-lines
      set))

(let [keep? (some #{"--keep"} *command-line-args*)
      workspace (fs/create-temp-dir {:prefix "pchat-smoke"})
      port (free-port)
      base (str "http://localhost:" port)
      log (fs/file workspace ".pchat.log")
      started (System/currentTimeMillis)
      server (process/process ["bb" "-m" "pchat.main"]
                              {:extra-env {"PORT" (str port)
                                           "PCHAT_WORKSPACE" (str workspace)}
                               :out (str log)
                               :err (str log)})]
  (println (str "pchat on " base ", the workspace " workspace))
  (try
    (wait-for! "the page" #(answers? base) 60000)
    (http/post (str base "/command")
               {:headers {"Content-Type" "application/transit+json"}
                :body (transit/write-str {:command/kind :chat/send
                                          :command/data {:text wish}})})
    (println "The wish is sent, waiting for the agent …")
    (wait-for! "the agent's answer" #(settled? (fs/file workspace ".pchat" "sessions")) timeout-ms)
    (let [entries (session/entries (first (session/files (fs/file workspace ".pchat" "sessions"))))
          items (session/items entries)
          videos (vec (fs/glob (fs/file workspace "artifacts") "*.mp4"))
          music (vec (fs/glob (fs/file workspace "artifacts") "**.mp3"))
          found (when (seq videos) (streams (first videos)))
          seconds (quot (- (System/currentTimeMillis) started) 1000)
          problems (cond-> []
                     (empty? videos) (conj "no mp4 under artifacts/")
                     (and (seq videos) (not (contains? found "video"))) (conj "the mp4 has no video stream")
                     (and (seq videos) (not (contains? found "audio"))) (conj "the mp4 has no audio stream")
                     (empty? music) (conj "no mp3 under artifacts/"))]
      (println (str (count items) " items, $" (format "%.3f" (session/cost entries)) ", " seconds " s"))
      (println (str "videos: " (str/join ", " (map fs/file-name videos))
                    "; music: " (str/join ", " (map #(str (fs/relativize (fs/file workspace "artifacts") %)) music))))
      (println (str "the agent said: " (str/replace (str/trim (str (:text (last (filter #(= :assistant (:kind %)) items))))) #"\s+" " ")))
      (if (empty? problems)
        (println "PASS")
        (do (println (str "FAIL: " (str/join "; " problems)))
            (println (str "the log: " log))
            (System/exit 1))))
    (finally
      (process/destroy-tree server)
      (if keep?
        (println (str "kept " workspace))
        (do (Thread/sleep 1000)
            (fs/delete-tree workspace))))))
(System/exit 0)
