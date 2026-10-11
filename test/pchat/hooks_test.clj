(ns pchat.hooks-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pchat.hooks :as hooks]))

(defn- temp-dir
  []
  (str (java.nio.file.Files/createTempDirectory
        "pchat-hooks"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest hooks-test
  (let [dir (temp-dir)
        id "11111111-2222-3333-4444-555555555555"
        db (atom {:chats {id {:id id}}})
        handle (hooks/handle {:db db
                              :workspace dir
                              :bb "bb"
                              :artifacts (io/file dir "artifacts")})]
    (.mkdirs (io/file dir "artifacts"))
    (hooks/load! handle)
    (is (nil? (get-in @db [:chats id :hook])))
    (is (= [] (get-in @db [:chats id :runs])))
    (testing "a webhook comes with a secret and a stub script"
      (let [{:keys [secret]} (hooks/create! handle id)]
        (is (re-matches #"[0-9a-f]{32}" secret))
        (is (.isFile (hooks/script-file handle id)))
        (is (= secret (get-in @db [:chats id :hook :secret])))
        (testing "a call runs the script with the body, the run is kept and logged"
          (let [request {:request-method :post
                         :query-string "x=1"
                         :headers {"content-type" "application/json"}
                         :body (java.io.ByteArrayInputStream. (.getBytes "{\"review\": \"Lovely\"}" "UTF-8"))}
                response (:ring/response (hooks/handler handle {:ring/request request
                                                                :ring/route-params {:* (str id "/" secret)}}))
                [run] (get-in @db [:chats id :runs])]
            (is (= 200 (:status response)))
            (is (str/includes? (:body response) "Received 20 bytes of JSON"))
            (is (= 1 (:n run)))
            (is (= 0 (:exit run)))
            (is (= "POST" (:method run)))
            (is (= 20 (:bytes run)))
            (is (str/includes? (:output run) "{:review \"Lovely\"}"))
            (is (= [] (:made run)))
            (is (str/includes? (slurp (hooks/log-file handle id)) "--- exit 0"))
            (let [db2 (atom {:chats {id {}}})]
              (hooks/load! (assoc handle :db db2))
              (is (= [run] (get-in @db2 [:chats id :runs])) "the runs survive a restart")
              (is (= secret (get-in @db2 [:chats id :hook :secret]))))))
        (testing "a wrong secret or chat is nothing"
          (is (= 404 (:status (:ring/response (hooks/handler handle {:ring/request {:request-method :post}
                                                                     :ring/route-params {:* (str id "/nope")}})))))
          (is (= 404 (:status (:ring/response (hooks/handler handle {:ring/request {:request-method :post}
                                                                     :ring/route-params {:* (str "other/" secret)}}))))))
        (testing "a failing script is a failed run with its output"
          (spit (hooks/script-file handle id) "(println \"boom\") (System/exit 3)")
          (let [response (:ring/response (hooks/handler handle {:ring/request {:request-method :get}
                                                                :ring/route-params {:* (str id "/" secret)}}))
                run (last (get-in @db [:chats id :runs]))]
            (is (= 500 (:status response)))
            (is (= 3 (:exit run)))
            (is (= "GET" (:method run)))
            (is (= "boom\n" (:output run)))))
        (testing "what the script writes under artifacts/ is what the run made"
          (spit (hooks/script-file handle id) "(spit \"artifacts/made.txt\" \"x\")")
          (hooks/handler handle {:ring/request {:request-method :post}
                                 :ring/route-params {:* (str id "/" secret)}})
          (is (= ["made.txt"] (:made (last (get-in @db [:chats id :runs]))))))
        (testing "off: the address is gone, the script stays"
          (hooks/remove! handle id)
          (is (nil? (get-in @db [:chats id :hook])))
          (is (.isFile (hooks/script-file handle id)))
          (is (= 404 (:status (:ring/response (hooks/handler handle {:ring/request {:request-method :post}
                                                                     :ring/route-params {:* (str id "/" secret)}}))))))
        (testing "forgotten with the chat"
          (hooks/forget! handle id)
          (is (not (.exists (hooks/script-file handle id))))
          (is (nil? (get-in @db [:chats id :runs]))))))
    (is (= "https://x/hook/a/b" (hooks/url "https://x" "a" "b")))
    (is (str/includes? (hooks/prompt id) (str "hooks/" id ".clj")))))
