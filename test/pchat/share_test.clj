(ns pchat.share-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [pchat.share :as share]))

(defn- temp-dir
  []
  (str (java.nio.file.Files/createTempDirectory
        "pchat-share"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest share-test
  (let [dir (temp-dir)
        db (atom {})
        handle (share/handle {:db db
                              :dir (io/file dir "artifacts")
                              :file (io/file dir ".pchat" "shares.edn")})
        file (io/file dir "artifacts" "card.mp4")]
    (io/make-parents file)
    (spit file "video")
    (share/load! handle)
    (is (= {} (:shares @db)))
    (let [id (share/share! handle "card.mp4")]
      (is (re-matches #"[0-9a-f]{20}" id))
      (is (= id (share/share! handle "card.mp4")) "the same link again")
      (is (= {"card.mp4" id} (:shares @db)))
      (testing "the link serves the file, and only the file"
        (let [request {:request-method :get :uri (str "/share/" id)}
              response (:ring/response (share/handler handle {:ring/request request
                                                              :ring/route-params {:* id}}))]
          (is (= 200 (:status response)))
          (is (= "video/mp4" (get-in response [:headers "Content-Type"]))))
        (is (= 404 (:status (:ring/response (share/handler handle {:ring/request {}
                                                                   :ring/route-params {:* "0123456789abcdef0123"}})))))
        (is (= 404 (:status (:ring/response (share/handler handle {:ring/request {}
                                                                   :ring/route-params {:* "../x"}}))))))
      (testing "the links survive a restart"
        (let [db2 (atom {})]
          (share/load! (assoc handle :db db2))
          (is (= {"card.mp4" id} (:shares @db2)))))
      (share/unshare! handle "card.mp4")
      (is (= {} (:shares @db)))
      (is (= 404 (:status (:ring/response (share/handler handle {:ring/request {}
                                                                 :ring/route-params {:* id}}))))))
    (is (= "https://pchat.example" (share/base-url {:headers {"x-forwarded-proto" "https" "host" "pchat.example"}})))
    (is (= "http://localhost:8080" (share/base-url {:scheme :http :headers {"host" "localhost:8080"}})))
    (is (= "https://pchat.example/share/abc" (share/link "https://pchat.example" "abc")))))
