(ns pchat.session
  "pi's session files as the list of chats and their history. One file per
  chat under the sessions dir, `<timestamp>_<id>.jsonl`, written by pi;
  pchat only reads them: the entries in file order, the messages as the
  items of `pchat.chat`."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pchat.chat :as chat]))

(defn id-of
  "The chat id of a session file, the uuid after the timestamp."
  [file]
  (second (re-find #"_([0-9a-f-]{36})\.jsonl$" (.getName (io/file file)))))

(defn files
  "The session files in `dir`, newest first."
  [dir]
  (->> (.listFiles (io/file dir))
       (filter #(and (.isFile %) (id-of %)))
       (sort-by #(.getName %))
       (reverse)))

(defn entries
  [file]
  (with-open [reader (io/reader file)]
    (->> (line-seq reader)
         (remove str/blank?)
         (mapv #(json/parse-string % true)))))

(defn- text-of
  [content]
  (if (string? content)
    content
    (->> content
         (filter #(= "text" (:type %)))
         (map :text)
         (str/join ""))))

(defn- images-of
  "The URLs of the image blocks, after `pchat.looks/intern`."
  [content]
  (if (string? content)
    []
    (into []
          (comp (filter #(= "image" (:type %)))
                (keep :url))
          content)))

(defn- message-items
  "The items one message adds, in the order the live events would."
  [{:keys [role content stopReason errorMessage]}]
  (case role
    "user" [{:kind :user
             :text (text-of content)
             :images (images-of content)}]
    "assistant" (into [(cond-> {:kind :assistant
                                :text (text-of content)
                                :thinking ""
                                :done? true}
                         (= "error" stopReason) (assoc :error (or errorMessage [:i18n :chat/model-failed])))]
                      (for [{:keys [type id name arguments]} content
                            :when (= "toolCall" type)]
                        {:kind :tool
                         :id id
                         :name name
                         :args arguments}))
    []))

(defn- with-result
  [items {:keys [toolCallId content isError]}]
  (if-let [i (last (keep-indexed (fn [i item]
                                   (when (and (= :tool (:kind item))
                                              (= toolCallId (:id item)))
                                     i))
                                 items))]
    (update items i assoc
            :done? true
            :result {:content content}
            :error? (boolean isError))
    items))

(defn items
  "The chat items of the message entries, in file order. `intern` (a
  function of a message, see `pchat.looks/intern-message`) replaces the
  image bytes of a message by the stored file; the identity by default."
  ([entries]
   (items entries identity))
  ([entries intern]
   (reduce (fn [items {:keys [type message]}]
             (if (= "message" type)
               (let [message (intern message)]
                 (if (= "toolResult" (:role message))
                   (with-result items message)
                   (into items (message-items message))))
               items))
           []
           entries)))

(defn cost
  [entries]
  (->> entries
       (filter #(= "message" (:type %)))
       (map #(get-in % [:message :usage :cost :total]))
       (filter number?)
       (reduce + 0.0)))

(defn name-of
  "What the sidebar calls a chat: the start of its first message."
  [items]
  (when-let [text (some #(when (= :user (:kind %)) (:text %)) items)]
    (let [line (first (str/split-lines (str/trim text)))]
      (if (> (count line) 48)
        (str (subs line 0 47) "…")
        line))))

(defn- millis
  "A timestamp of a session entry as Unix milliseconds: the header's is an
  ISO string, a message's a number."
  [t]
  (cond
    (number? t) (long t)
    (string? t) (.toEpochMilli (java.time.Instant/parse t))
    :else nil))

(defn summary
  "What the sidebar needs of a session file, without its items."
  [file]
  (let [entries (entries file)
        header (first (filter #(= "session" (:type %)) entries))
        items (items entries)]
    {:id (or (:id header) (id-of file))
     :file (str file)
     :created (millis (:timestamp header))
     :updated (or (millis (:timestamp (last entries)))
                  (millis (:timestamp header)))
     :name (name-of items)}))

(defn load-chat
  "The chat value of a session file: its items and cost, idle. `intern`
  as in `items`."
  ([file]
   (load-chat file identity))
  ([file intern]
   (let [entries (entries file)]
     (merge chat/empty-chat
            (summary file)
            {:items (items entries intern)
             :cost (cost entries)
             :loaded? true}))))
