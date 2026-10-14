(ns pchat.share-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [pchat.share :as share]))

(defn- temp-dir
  []
  (str (java.nio.file.Files/createTempDirectory
        "pchat-share"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(def id
  "0199dd7e-1f1a-7a2b-8b3c-4d5e6f708192")

(deftest share-test
  (let [chats (temp-dir)
        db (atom {:chats {id {:id id}}})
        handle (share/handle {:db db :chats-dir chats})
        file (io/file chats id "artifacts" "card.mp4")]
    (io/make-parents file)
    (spit file "video")
    (share/load! handle)
    (is (= {} (get-in @db [:chats id :shares])))
    (let [share-id (share/share! handle id "card.mp4")]
      (is (re-matches #"[0-9a-f]{20}" share-id))
      (is (= share-id (share/share! handle id "card.mp4")) "the same link again")
      (is (= {"card.mp4" share-id} (get-in @db [:chats id :shares])))
      (is (.isFile (io/file chats id ".shares.edn")) "kept in the chat's folder")
      (testing "the link serves the file of the chat, and only the file"
        (let [request {:request-method :get :uri (str "/share/" share-id)}
              response (:ring/response (share/handler handle {:ring/request request
                                                              :ring/route-params {:* share-id}}))]
          (is (= 200 (:status response)))
          (is (= "video/mp4" (get-in response [:headers "Content-Type"]))))
        (is (= 404 (:status (:ring/response (share/handler handle {:ring/request {}
                                                                   :ring/route-params {:* "0123456789abcdef0123"}})))))
        (is (= 404 (:status (:ring/response (share/handler handle {:ring/request {}
                                                                   :ring/route-params {:* "../x"}}))))))
      (testing "the links survive a restart"
        (let [db2 (atom {:chats {id {:id id}}})]
          (share/load! (assoc handle :db db2))
          (is (= {"card.mp4" share-id} (get-in @db2 [:chats id :shares])))))
      (share/unshare! handle id "card.mp4")
      (is (= {} (get-in @db [:chats id :shares])))
      (is (= 404 (:status (:ring/response (share/handler handle {:ring/request {}
                                                                 :ring/route-params {:* share-id}}))))))
    (is (= "https://pchat.example" (share/base-url {:headers {"x-forwarded-proto" "https" "host" "pchat.example"}})))
    (is (= "http://localhost:8080" (share/base-url {:scheme :http :headers {"host" "localhost:8080"}})))
    (is (= "https://pchat.example/share/abc" (share/link "https://pchat.example" "abc")))))
