(ns pchat.projects-test
  (:require [babashka.nrepl.server :as server]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [pchat.nrepl :as nrepl]
            [pchat.projects :as projects]))

(deftest nrepl-eval-test
  (let [{:keys [socket]} (server/start-server! {:host "127.0.0.1" :port 0 :quiet true})
        port (.getLocalPort socket)]
    (try
      (is (= "3" (:value (nrepl/eval! port "(+ 1 2)"))))
      (is (= "hi\n" (:out (nrepl/eval! port "(println \"hi\")"))))
      (is (string? (:ex (nrepl/eval! port "(/ 1 0)"))))
      (finally
        (.close socket)))))

(deftest names-test
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "pchat-projects"
                  (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.mkdirs (io/file dir "b"))
    (spit (io/file dir "b" "bb.edn") "{}")
    (.mkdirs (io/file dir "a"))
    (spit (io/file dir "a" "bb.edn") "{}")
    (.mkdirs (io/file dir "plain"))
    (.mkdirs (io/file dir ".hidden"))
    (spit (io/file dir ".hidden" "bb.edn") "{}")
    (is (= ["a" "b"] (projects/names dir)))))

(deftest handler-passes-unknown-test
  (let [handle (projects/handle {:db (atom {}) :chats-dir "/tmp/none"})
        w {:ring/route-params {:* "hello.html"} :ring/request {}}]
    (testing "a path that is no running project is left to the caller"
      (is (= w (projects/handler handle "c" w))))
    (testing "no chats, no projects, nothing to sync"
      (is (nil? (projects/sync! handle))))))

(deftest media-of-a-project-test
  (let [chats (str (java.nio.file.Files/createTempDirectory
                    "pchat-projects"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
        id "0199dd7e-1f1a-7a2b-8b3c-4d5e6f708192"
        dir (io/file chats id "artifacts" "short")
        db (atom {:chats {id {:projects {"short" {:ready-at 1}}}}})
        handle (projects/handle {:db db :chats-dir chats})
        w (fn [path] {:ring/route-params {:* path} :ring/request {}})]
    (.mkdirs dir)
    (spit (io/file dir "highlight.mp4") "video")
    (swap! (:procs handle) assoc [id "short"] {:port 1})
    (testing "a clip in a running project's folder is left to the files route, not proxied"
      (is (= (w "short/highlight.mp4") (projects/handler handle id (w "short/highlight.mp4")))))
    (testing "its page, and a hidden file, go to the process"
      (is (contains? (projects/handler handle id (w "short/")) :ring/response))
      (is (contains? (projects/handler handle id (w "short/.hidden/x.mp4")) :ring/response)))))

