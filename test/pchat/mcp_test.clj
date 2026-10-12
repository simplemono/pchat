(ns pchat.mcp-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pchat.fake-mcp :as fake]
            [pchat.mcp :as mcp]))

(defn- temp-dir
  []
  (str (fs/create-temp-dir {:prefix "pchat-mcp"})))

(deftest names-and-parsing-test
  (is (mcp/valid-name? "calendar"))
  (is (mcp/valid-name? "my_box-2"))
  (is (not (mcp/valid-name? "my box")))
  (is (not (mcp/valid-name? "")))
  (is (not (mcp/valid-name? "a/b")))
  (is (= "mcp__echo_box__list_files" (mcp/tool-name "echo-box" "list.files")))
  (is (= ["Authorization" "Bearer x y"] (mcp/parse-header " Authorization : Bearer x y ")))
  (is (nil? (mcp/parse-header "no colon")))
  (is (nil? (mcp/parse-header "")))
  (is (= {:url "https://a.b/mcp"} (mcp/parse-address " https://a.b/mcp ")))
  (is (= {:command "npx" :args ["-y" "server"]} (mcp/parse-address "npx -y server")))
  (is (= {"url" "https://a.b/mcp" "type" "http" "headers" {"A" "b"} "exposure" "deferred"}
         (mcp/entry {:url "https://a.b/mcp" :headers {"A" "b"}})))
  (is (= {"command" "bb" "args" ["x"] "env" {"K" "v"} "exposure" "direct" "description" "d"}
         (mcp/entry {:command "bb" :args ["x"] :env {"K" "v"} :exposure "direct" :description "d"}))))

(deftest file-round-trip-test
  (let [dir (temp-dir)
        changes (atom 0)
        db (atom {})
        handle (mcp/handle {:db db :workspace dir :on-change #(swap! changes inc)})]
    (mcp/load! handle)
    (is (= {} (:connections @db)))
    (testing "a connection is written as pi reads it, private, and shown without its secret"
      (is (= {"url" "https://a.b/mcp?key=1" "type" "http" "headers" {"Authorization" "Bearer t"} "exposure" "deferred"}
             (mcp/add! handle "box" {:url "https://a.b/mcp?key=1" :headers {"Authorization" "Bearer t"}})))
      (is (= 1 @changes))
      (let [file (io/file dir ".pi" "mcp.json")]
        (is (.isFile file))
        (is (= "rw-------" (str (java.nio.file.attribute.PosixFilePermissions/toString
                                 (java.nio.file.Files/getPosixFilePermissions (.toPath file) (make-array java.nio.file.LinkOption 0))))))
        (is (= {"mcpServers" {"box" {"url" "https://a.b/mcp?key=1" "type" "http" "headers" {"Authorization" "Bearer t"} "exposure" "deferred"}}}
               (json/parse-string (slurp file)))))
      (let [{:keys [kind address enabled? secrets exposure tools]} (get-in @db [:connections "box"])]
        (is (= :http kind))
        (is (= "https://a.b/mcp?…" address) "the query of the address may carry a key")
        (is enabled?)
        (is (= ["Authorization"] secrets))
        (is (= "deferred" exposure))
        (is (nil? tools)))
      (is (not (str/includes? (pr-str (:connections @db)) "Bearer t")) "no secret on the page"))
    (testing "the checks"
      (is (= {:error :connections/exists} (mcp/add! handle "box" {:url "https://c.d/mcp"})))
      (is (= {:error :connections/bad-name} (mcp/add! handle "a b" {:url "https://c.d/mcp"})))
      (is (= {:error :connections/bad-address} (mcp/add! handle "c" {:url "ftp://c.d/mcp"})))
      (is (= {:error :connections/bad-address} (mcp/add! handle "c" {:command ""})))
      (is (= {:error :connections/bad-address} (mcp/add! handle "c" {:url "https://c.d/mcp" :exposure "hidden"}))))
    (testing "a program, off and on, removed"
      (mcp/add! handle "prog" {:command "bb" :args ["-m" "x"] :env {"TOKEN" "t"} :exposure "direct"})
      (is (= {:kind :stdio :address "bb -m x" :secrets ["TOKEN"] :exposure "direct"}
             (select-keys (get-in @db [:connections "prog"]) [:kind :address :secrets :exposure])))
      (mcp/set-enabled! handle "prog" false)
      (is (false? (get-in @db [:connections "prog" :enabled?])))
      (is (false? (get-in (mcp/servers handle) ["prog" "enabled"])))
      (mcp/set-enabled! handle "prog" true)
      (is (true? (get-in @db [:connections "prog" :enabled?])))
      (mcp/remove! handle "prog")
      (is (= ["box"] (keys (:connections @db))))
      (is (= ["box"] (keys (mcp/servers handle)))))
    (testing "a change by another writer, the agent's script, is noticed"
      (is (not (mcp/changed? handle)))
      (Thread/sleep 20)
      (spit (io/file dir ".pi" "mcp.json")
            (json/generate-string {"mcpServers" {"box" {"url" "https://a.b/mcp"} "other" {"command" "x"}}}))
      (is (mcp/changed? handle))
      (mcp/load! handle)
      (is (= ["box" "other"] (keys (:connections @db))))
      (is (not (mcp/changed? handle))))
    (fs/delete-tree dir)))

(deftest test-against-a-server-test
  (let [dir (temp-dir)
        db (atom {})
        handle (mcp/handle {:db db :workspace dir})
        plain (fake/start! {})
        secured (fake/start! {:token "s3cret"})
        sse (fake/start! {:sse? true})]
    (try
      (testing "the handshake over streamable HTTP, JSON and event-stream answers"
        (is (= ["echo"] (map :name (:tools (mcp/http-tools! {:url (:url plain)})))))
        (is (= ["initialize" "notifications/initialized" "tools/list"]
               (keep #(get-in % [:body :method]) @(:requests plain))))
        (is (= ["echo"] (map :name (:tools (mcp/http-tools! {:url (:url sse)}))))))
      (testing "the headers of the entry go along, with ${ENV} filled in"
        (is (str/includes? (:error (mcp/http-tools! {:url (:url secured)})) "401"))
        (is (= ["echo"] (map :name (:tools (mcp/http-tools! {:url (:url secured) :headers {"Authorization" "Bearer s3cret"}})))))
        (is (= "Bearer s3cret" (get-in (last @(:requests secured)) [:headers "authorization"])))
        (is (= "application/json, text/event-stream" (get-in (last @(:requests secured)) [:headers "accept"]))))
      (testing "a program over standard input and output"
        (is (= ["echo"] (map :name (:tools (mcp/stdio-tools! {:command "bb"
                                                                :args ["-cp" (str (fs/absolutize "test")) "-m" "pchat.fake-mcp" "stdio"]
                                                                :workspace dir})))))
        (is (str/includes? (:error (mcp/stdio-tools! {:command "no-such-program-pchat" :args [] :workspace dir}))
                           "could not be started")))
      (testing "nothing there"
        (is (str/includes? (:error (mcp/http-tools! {:url "http://127.0.0.1:9/mcp"})) "No answer")))
      (testing "test! keeps what it found for the page and the next start"
        (mcp/add! handle "box" {:url (:url secured) :headers {"Authorization" "Bearer s3cret"}})
        (is (= {:tools ["echo"]} (mcp/test! handle "box")))
        (is (= ["echo"] (get-in @db [:connections "box" :tools])))
        (is (nil? (get-in @db [:connections "box" :testing?])))
        (is (number? (get-in @db [:connections "box" :tested])))
        (let [db2 (atom {})]
          (mcp/load! (assoc handle :db db2))
          (is (= ["echo"] (get-in @db2 [:connections "box" :tools])) "the result survives a restart"))
        (mcp/add! handle "wrong" {:url (:url secured) :headers {"Authorization" "Bearer nope"}})
        (is (str/includes? (:error (mcp/test! handle "wrong")) "401"))
        (is (str/includes? (get-in @db [:connections "wrong" :error]) "401"))
        (is (= {:error "There is no connection of that name."} (mcp/test! handle "nothing"))))
      (finally
        ((:stop plain))
        ((:stop secured))
        ((:stop sse))
        (fs/delete-tree dir)))))
