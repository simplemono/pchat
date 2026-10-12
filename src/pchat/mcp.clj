(ns pchat.mcp
  "Connections: the services the agent can use through their MCP
  servers, their tools reached with the Model Context Protocol. This
  namespace owns `<workspace>/.pi/mcp.json`, the file pi reads when a
  chat's process starts, `{\"mcpServers\" {name {...}}}`, and
  `.pchat/connections.edn`, what the last test of each server found. It
  needs nothing beyond what babashka brings, so that `bin/mcp` in the
  workspace can load it from `.pchat/lib`.

  The keys this namespace owns in the db:

      :connections {name {:name :kind (:http or :stdio) :address
                          :enabled? :exposure :secrets [header or env names]
                          :tools [tool names] :tested ms :error :testing?}}
      :settings-open     whether the settings are shown in place of the chat,
      :settings-tab      the tab there (`pchat.view/tabs`)"
  (:require [babashka.http-client :as http]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def request-timeout-ms
  "A server that takes longer to answer one request has failed the test."
  15000)

(def protocol-version
  "The MCP revision pchat speaks when it tests a server."
  "2025-06-18")

(def exposures
  "How a server's tools reach the model: `deferred` is declared only when
  the agent searches for it (little context), `direct` always."
  #{"deferred" "direct"})

(defn handle
  "{:db the app's atom, :workspace the directory of the agent, :on-change
  a function called after the file changed: the chats' processes read it
  only when they start}"
  [{:keys [workspace] :as opts}]
  (assoc opts
         :workspace (io/file workspace)
         :file (io/file workspace ".pi" "mcp.json")
         :state-file (io/file workspace ".pchat" "connections.edn")
         :seen (atom nil)))

(def name-pattern
  #"[A-Za-z0-9_-]+")

(defn valid-name?
  [name]
  (boolean (re-matches name-pattern (str name))))

(defn tool-name
  "The name of a tool of the server as the model sees it:
  mcp__<server>__<tool>, anything but letters, digits and _ as _."
  [server tool]
  (str "mcp__" (str/replace (str server) #"[^A-Za-z0-9_]" "_")
       "__" (str/replace (str tool) #"[^A-Za-z0-9_]" "_")))

;;; The files

(defn- private!
  "Readable by the owner only: the file holds tokens."
  [file]
  (try
    (java.nio.file.Files/setPosixFilePermissions
     (.toPath file)
     (java.nio.file.attribute.PosixFilePermissions/fromString "rw-------"))
    (catch Exception _
      nil)))

(defn- write-atomically!
  "The whole text or nothing: written beside the file, then moved over
  it."
  [file text]
  (io/make-parents file)
  (let [temp (io/file (.getParentFile file) (str "." (.getName file) ".tmp"))]
    (spit temp text)
    (private! temp)
    (java.nio.file.Files/move (.toPath temp) (.toPath file)
                              (into-array java.nio.file.CopyOption
                                          [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                                           java.nio.file.StandardCopyOption/REPLACE_EXISTING]))))

(defn read-config
  "The content of mcp.json, string keys, {} without the file."
  [{:keys [file]}]
  (if (.isFile file)
    (or (json/parse-string (slurp file)) {})
    {}))

(defn servers
  "{name entry} of the file."
  [handle]
  (get (read-config handle) "mcpServers" {}))

(defn- write-servers!
  [{:keys [file] :as handle} servers]
  (write-atomically! file (json/generate-string (assoc (read-config handle) "mcpServers" servers)
                                                {:pretty true})))

(defn- read-state
  [{:keys [state-file]}]
  (if (.isFile state-file)
    (edn/read-string (slurp state-file))
    {}))

(defn- write-state!
  [{:keys [state-file]} state]
  (write-atomically! state-file (pr-str state)))

;;; The value on the page

(defn- masked-address
  "The query of an address may carry a key; the rest says where it goes."
  [url]
  (str/replace (str url) #"\?.*$" "?…"))

(defn summary
  "What the page shows of a server: its kind and address, no secret."
  [name entry state]
  (let [{:strs [url command args headers env enabled exposure description]} entry]
    (merge {:name name
            :kind (if url :http :stdio)
            :address (if url
                       (masked-address url)
                       (str/join " " (cons command args)))
            :enabled? (not (false? enabled))
            :exposure (or exposure "codemode")
            :secrets (vec (sort (keys (merge headers env))))
            :description description}
           (select-keys state [:tools :tested :error]))))

(defn- mtimes
  [{:keys [file state-file]}]
  [(.lastModified file) (.lastModified state-file)])

(defn load!
  "The servers of the file and the results of their last tests, into
  the db."
  [{:keys [db seen] :as handle}]
  (let [state (read-state handle)
        connections (into (sorted-map)
                          (for [[name entry] (servers handle)]
                            [name (summary name entry (get state name))]))]
    (reset! seen (mtimes handle))
    (swap! db update :connections
           (fn [old]
             (into (sorted-map)
                   (for [[name connection] connections]
                     [name (merge connection (select-keys (get old name) [:testing?]))]))))
    connections))

(defn changed?
  "Whether a file was written since `load!` last read them: the agent's
  script edits the same file."
  [{:keys [seen] :as handle}]
  (not= @seen (mtimes handle)))

(defn- changed!
  [{:keys [on-change] :as handle}]
  (load! handle)
  (when on-change
    (on-change)))

;;; Adding, removing, on and off

(defn parse-header
  "\"Authorization: Bearer x\" as [\"Authorization\" \"Bearer x\"], nil
  when it is not that shape."
  [line]
  (let [[_ k v] (re-matches #"\s*([A-Za-z0-9-]+)\s*:\s*(.*\S)\s*" (str line))]
    (when k
      [k v])))

(defn parse-address
  "A URL as {:url}, anything else as a program with its arguments,
  {:command :args}."
  [address]
  (let [address (str/trim (str address))]
    (if (re-find #"^https?://" address)
      {:url address}
      (let [[command & args] (str/split address #"\s+")]
        {:command command
         :args (vec args)}))))

(defn entry
  "The mcp.json entry of a connection: `:url` with `:headers`, or
  `:command` with `:args` and `:env`; `:exposure` deferred unless given."
  [{:keys [url headers command args env exposure description]}]
  (cond-> {}
    url (assoc "url" url "type" "http")
    (seq headers) (assoc "headers" headers)
    command (assoc "command" command)
    (seq args) (assoc "args" (vec args))
    (seq env) (assoc "env" env)
    true (assoc "exposure" (or exposure "deferred"))
    (seq description) (assoc "description" description)))

(defn check
  "Why `spec` cannot be added as `name`, nil when it can: a keyword the
  page turns into a sentence."
  [handle name {:keys [url command exposure replace?]}]
  (cond
    (not (valid-name? name)) :connections/bad-name
    (and (not replace?) (contains? (servers handle) name)) :connections/exists
    (and (nil? url) (str/blank? command)) :connections/bad-address
    (and url (not (re-find #"^https?://\S+$" url))) :connections/bad-address
    (and exposure (not (exposures exposure))) :connections/bad-address
    :else nil))

(defn add!
  "The server in the file; the result is the entry, or {:error keyword}."
  [handle name spec]
  (if-let [error (check handle name spec)]
    {:error error}
    (let [entry (entry spec)]
      (write-servers! handle (assoc (servers handle) name entry))
      (changed! handle)
      entry)))

(defn remove!
  [handle name]
  (write-servers! handle (dissoc (servers handle) name))
  (write-state! handle (dissoc (read-state handle) name))
  (changed! handle)
  nil)

(defn set-enabled!
  "Off keeps the entry, pi does not connect it."
  [handle name enabled?]
  (when-let [entry (get (servers handle) name)]
    (write-servers! handle (assoc (servers handle) name (assoc entry "enabled" (boolean enabled?))))
    (changed! handle)
    nil))

;;; Testing a server: the MCP handshake and the list of its tools

(defn- substitute
  "${NAME} in a header or an environment value is the environment
  variable, as pi fills it in."
  [values]
  (into {}
        (for [[k v] values]
          [k (str/replace (str v) #"\$\{([A-Za-z_][A-Za-z0-9_]*)\}"
                          (fn [[_ name]] (or (System/getenv name) "")))])))

(defn- lower-headers
  [headers]
  (into {}
        (for [[k v] headers]
          [(str/lower-case (name k)) (if (sequential? v) (first v) v)])))

(defn- parse-messages
  "The JSON-RPC messages of an answer, JSON or an event stream."
  [{:keys [headers body]}]
  (let [type (str (get (lower-headers headers) "content-type"))
        body (str body)]
    (cond
      (str/starts-with? type "text/event-stream")
      (->> (str/split-lines body)
           (filter #(str/starts-with? % "data:"))
           (map #(str/trim (subs % 5)))
           (remove str/blank?)
           (keep #(try (json/parse-string % true) (catch Exception _ nil)))
           (vec))

      (str/blank? body)
      []

      :else
      (let [value (json/parse-string body true)]
        (if (sequential? value) (vec value) [value])))))

(defn- post!
  "One message to the server: {:status :messages :headers}, or {:error}."
  [url headers message]
  (try
    (let [response (http/post url {:headers headers
                                   :body (json/generate-string message)
                                   :throw false
                                   :timeout request-timeout-ms})
          status (:status response)]
      (if (<= 200 status 299)
        {:status status
         :headers (lower-headers (:headers response))
         :messages (try (parse-messages response) (catch Exception _ []))}
        {:error (str "The address answered " status
                     (let [text (str/trim (str (:body response)))]
                       (when (seq text) (str ": " (subs text 0 (min 200 (count text)))))))}))
    (catch Exception e
      {:error (str "No answer from the address: " (or (ex-message e) (str e)))})))

(defn- answer
  "The result of the message with `id` among `messages`, or {:error}."
  [messages id]
  (if-let [message (some #(when (= id (:id %)) %) messages)]
    (if-let [error (:error message)]
      {:error (str "The server said: " (:message error))}
      {:result (:result message)})
    {:error "The server did not answer the request."}))

(defn http-tools!
  "The tools of a streamable HTTP server: initialize, initialized,
  tools/list, with the headers of the entry. {:tools [...]} or {:error}."
  [{:keys [url headers]}]
  (let [base (merge {"Content-Type" "application/json"
                     "Accept" "application/json, text/event-stream"}
                    (substitute headers))
        {:keys [error] :as init} (post! url base {:jsonrpc "2.0"
                                                  :id 1
                                                  :method "initialize"
                                                  :params {:protocolVersion protocol-version
                                                           :capabilities {}
                                                           :clientInfo {:name "pchat" :version "1"}}})]
    (if error
      init
      (let [{:keys [error result]} (answer (:messages init) 1)]
        (if error
          {:error error}
          (let [headers (cond-> (assoc base "MCP-Protocol-Version" (or (:protocolVersion result) protocol-version))
                          (get-in init [:headers "mcp-session-id"])
                          (assoc "Mcp-Session-Id" (get-in init [:headers "mcp-session-id"])))]
            (post! url headers {:jsonrpc "2.0" :method "notifications/initialized"})
            (loop [cursor nil
                   tools []
                   id 2]
              (let [{:keys [error messages]} (post! url headers (cond-> {:jsonrpc "2.0" :id id :method "tools/list" :params {}}
                                                                  cursor (assoc-in [:params :cursor] cursor)))
                    {result :result error :error} (if error {:error error} (answer messages id))]
                (cond
                  error {:error error}
                  (and (:nextCursor result) (< id 20)) (recur (:nextCursor result) (into tools (:tools result)) (inc id))
                  :else {:tools (into tools (:tools result))})))))))))

(defn stdio-tools!
  "The tools of a server that is a program: started in the workspace,
  JSON-RPC on its standard input and output. {:tools [...]} or {:error}."
  [{:keys [command args env workspace]}]
  (try
    (let [p (process/process (into [command] args)
                             {:dir (str workspace)
                              :in :stream
                              :out :stream
                              :err :string
                              :extra-env (substitute env)})
          writer (io/writer (:in p))
          reader (io/reader (:out p))
          send! (fn [message]
                  (.write writer (str (json/generate-string message) "\n"))
                  (.flush writer))
          await (fn [id]
                  (deref (future
                           (loop []
                             (let [line (.readLine reader)]
                               (cond
                                 (nil? line) {:error "The program ended without an answer."}
                                 :else (let [message (try (json/parse-string line true) (catch Exception _ nil))]
                                         (if (= id (:id message))
                                           (if-let [error (:error message)]
                                             {:error (str "The server said: " (:message error))}
                                             {:result (:result message)})
                                           (recur)))))))
                         request-timeout-ms
                         {:error "The program took longer than fifteen seconds to answer."}))]
      (try
        (send! {:jsonrpc "2.0" :id 1 :method "initialize"
                :params {:protocolVersion protocol-version :capabilities {} :clientInfo {:name "pchat" :version "1"}}})
        (let [{:keys [error]} (await 1)]
          (if error
            {:error error}
            (do (send! {:jsonrpc "2.0" :method "notifications/initialized"})
                (send! {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}})
                (let [{:keys [error result]} (await 2)]
                  (if error
                    {:error error}
                    {:tools (:tools result)})))))
        (finally
          (process/destroy-tree p))))
    (catch Exception e
      {:error (str "The program could not be started: " (or (ex-message e) (str e)))})))

(defn tools!
  "The tools of an entry of mcp.json, as the file has it."
  [{:keys [workspace]} entry]
  (let [{:strs [url headers command args env]} entry]
    (if url
      (http-tools! {:url url :headers headers})
      (stdio-tools! {:command command :args args :env env :workspace workspace}))))

(defn test!
  "Connects to the server and keeps what it found, the names of its
  tools or the error, in the state file and the db. The result:
  {:tools [names]} or {:error text}."
  [{:keys [db] :as handle} name]
  (if-let [entry (get (servers handle) name)]
    (do (swap! db assoc-in [:connections name :testing?] true)
        (let [{:keys [tools error]} (try
                                      (tools! handle entry)
                                      (catch Exception e
                                        {:error (str "The test failed: " (or (ex-message e) (str e)))}))
              result (if error
                       {:error error :tested (System/currentTimeMillis)}
                       {:tools (vec (map :name tools)) :tested (System/currentTimeMillis)})]
          (write-state! handle (assoc (read-state handle) name result))
          (load! handle)
          (swap! db update-in [:connections name] #(some-> % (dissoc :testing?)))
          (if error
            {:error error}
            {:tools (:tools result)})))
    {:error "There is no connection of that name."}))
