(ns pchat.fake-mcp
  "A small MCP server over streamable HTTP, for the tests and for trying
  the Connections panel by hand: one tool `echo` that answers with the
  text it was given. `(start! {:token \"secret\"})` serves on a free port
  and returns {:url :port :requests :stop}; `:requests` is an atom with
  every request it saw ({:method :uri :headers :body}), `:stop` ends it.
  With `:token` a request without `Authorization: Bearer <token>` is
  refused; with `:sse? true` the answers come as an event stream, the
  other shape a streamable HTTP server may use."
  (:require [cheshire.core :as json]
            [org.httpkit.server :as server]))

(def tools
  [{:name "echo"
    :description "Answers with the text it was given."
    :inputSchema {:type "object"
                  :properties {:text {:type "string"
                                      :description "The text to echo"}}
                  :required ["text"]}}])

(defn- result
  [id value]
  {:jsonrpc "2.0" :id id :result value})

(defn- failure
  [id code message]
  {:jsonrpc "2.0" :id id :error {:code code :message message}})

(defn answer
  "The answer to one JSON-RPC message, nil for a notification."
  [{:keys [id method params]}]
  (case method
    "initialize" (result id {:protocolVersion (or (:protocolVersion params) "2025-06-18")
                             :capabilities {:tools {}}
                             :serverInfo {:name "fake-mcp" :version "1"}})
    "notifications/initialized" nil
    "ping" (result id {})
    "tools/list" (result id {:tools tools})
    "tools/call" (let [{:keys [name arguments]} params]
                   (if (= "echo" name)
                     (result id {:content [{:type "text" :text (str "echo: " (:text arguments))}]})
                     (failure id -32602 (str "Unknown tool " name))))
    (failure id -32601 (str "Unknown method " method))))

(defn- free-port
  []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- json-body
  [answers sse?]
  (if sse?
    {:status 200
     :headers {"Content-Type" "text/event-stream"}
     :body (apply str (for [a answers]
                        (str "event: message\ndata: " (json/generate-string a) "\n\n")))}
    {:status 200
     :headers {"Content-Type" "application/json"
               "Mcp-Session-Id" "fake-session-1"}
     :body (json/generate-string (if (= 1 (count answers)) (first answers) answers))}))

(defn- handler
  [{:keys [token requests sse?]} request]
  (let [body (some-> (:body request) slurp)
        parsed (try (json/parse-string body true) (catch Exception _ nil))]
    (swap! requests conj {:method (:request-method request)
                          :uri (:uri request)
                          :headers (:headers request)
                          :body parsed})
    (cond
      (and token (not= (str "Bearer " token) (get-in request [:headers "authorization"])))
      {:status 401 :headers {"Content-Type" "text/plain"} :body "Unauthorized"}

      (= :get (:request-method request))
      {:status 405 :headers {"Content-Type" "text/plain"} :body "No stream here"}

      (= :delete (:request-method request))
      {:status 200 :body ""}

      (nil? parsed)
      {:status 400 :headers {"Content-Type" "text/plain"} :body "Not JSON"}

      :else
      (let [answers (keep answer (if (sequential? parsed) parsed [parsed]))]
        (if (empty? answers)
          {:status 202 :body ""}
          (json-body answers sse?))))))

(defn start!
  [{:keys [token sse? port]}]
  (let [port (or port (free-port))
        state {:token token
               :sse? sse?
               :requests (atom [])}
        stop (server/run-server (partial handler state) {:port port})]
    {:url (str "http://127.0.0.1:" port "/mcp")
     :port port
     :requests (:requests state)
     :stop (fn [] (stop :timeout 100))}))

(defn stdio!
  "The same server as a program: JSON-RPC lines on standard input and
  output, until the input ends."
  []
  (loop []
    (when-let [line (read-line)]
      (when-let [message (try (json/parse-string line true) (catch Exception _ nil))]
        (when-let [a (answer message)]
          (println (json/generate-string a))
          (flush)))
      (recur))))

(defn -main
  "`bb -cp test -m pchat.fake-mcp [port] [token]`: the server for trying
  the panel by hand; runs until the process ends. `bb -cp test -m
  pchat.fake-mcp stdio`: the same over standard input and output."
  [& [port token]]
  (if (= "stdio" port)
    (stdio!)
    (let [{:keys [url]} (start! {:port (some-> port parse-long)
                                 :token (not-empty token)})]
      (println (str "fake MCP server at " url (when (seq token) " (Authorization: Bearer <token>)")))
      @(promise))))
