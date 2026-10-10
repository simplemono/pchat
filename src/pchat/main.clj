(ns pchat.main
  "The server: one chat on one pi process, the page over rframes.

      PORT             default 8080
      PCHAT_WORKSPACE  the folder the agent works in, default ./workspace
      PCHAT_PROVIDER   default openrouter (OPENROUTER_API_KEY in the environment)
      PCHAT_MODEL      default openai/gpt-6.1-sol
      PCHAT_TOKEN      a secret: the page is behind the link that sets the cookie"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as server]
            [pchat.chat :as chat]
            [pchat.pi :as pi]
            [pchat.view :as view]
            [rchat.auth :as auth]
            [rframes.command :as command]
            [rframes.http :as http]
            [rframes.replicant :as replicant]
            [rframes.sse :as sse]))

(def workspace
  (io/file (or (System/getenv "PCHAT_WORKSPACE") "./workspace")))

(def provider
  (or (System/getenv "PCHAT_PROVIDER") "openrouter"))

(def model
  (or (System/getenv "PCHAT_MODEL") "openai/gpt-6.1-sol"))

(def key-missing?
  (and (= "openrouter" provider)
       (str/blank? (System/getenv "OPENROUTER_API_KEY"))))

(defonce db
  (atom {:chat chat/empty-chat}))

;; The pi process handle is not a value, so it lives beside the db.
(defonce pi*
  (atom nil))

(defn- chat-id
  "The id of the one chat, kept in the workspace so pi resumes its session."
  []
  (let [file (io/file workspace ".pchat" "chat-id")]
    (when-not (.isFile file)
      (io/make-parents file)
      (spit file (str (random-uuid))))
    (str/trim (slurp file))))

(defn- on-event
  [event]
  (when (= "exited" (:type event))
    (reset! pi* nil))
  (swap! db update :chat chat/event event))

(defn- ensure-pi!
  []
  (or @pi*
      (reset! pi* (pi/spawn! {:dir workspace
                              :session-dir (io/file workspace ".pchat" "sessions")
                              :session-id (chat-id)
                              :provider provider
                              :model model
                              :system-prompt (slurp (io/resource "pchat/prompts/system.md"))
                              :on-event on-event}))))

(defn- send-command
  [w]
  (let [{:keys [text]} (command/command-data w)
        text (str/trim (str text))]
    (if (str/blank? text)
      (command/rejected w "Nothing to send.")
      (let [p (ensure-pi!)
            streaming? (= :working (get-in @db [:chat :status]))]
        (pi/prompt! p text streaming?)
        (command/accepted w)))))

(defn- abort-command
  [w]
  (when-let [p @pi*]
    (pi/abort! p))
  (command/accepted w))

(defn page-view
  [_w]
  (view/page @db {:key-missing? key-missing?}))

(def head
  (str "<title>pchat</title>"
       "<link rel=\"icon\" href=\"data:,\">"
       "<link rel=\"stylesheet\" href=\"/pchat.css\">"
       "<script defer src=\"/js/main.js\"></script>"))

(def register
  [(http/resources "/pchat.css" "pchat/chat.css")
   (http/resources "/js/*" "public/js")
   {:replicant/shim "/"
    :head head}
   {:replicant/render "/"
    :render/fn #'page-view}
   {:command/kind :chat/send
    :command/fn #'send-command}
   {:command/kind :chat/abort
    :command/fn #'abort-command}])

(defn get-register
  []
  (replicant/expand (concat register command/register)))

(defn -main
  [& _args]
  (.mkdirs workspace)
  (sse/watch! db)
  (let [port (parse-long (or (System/getenv "PORT") "8080"))
        token (System/getenv "PCHAT_TOKEN")]
    (server/run-server (auth/wrap (http/ring-handler get-register) {:token token})
                       {:port port})
    (println (str "pchat on "
                  (if token
                    (auth/link (str "http://localhost:" port) token)
                    (str "http://localhost:" port))
                  ", the model is " provider "/" model
                  ", the workspace " (.getCanonicalPath workspace))))
  @(promise))
