(ns pchat.nrepl-server-test
  "The nREPL server pchat starts for live development: loopback, a
  free port, the port file in the workspace, an eval through pchat's
  own client."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [pchat.main :as main]
            [pchat.nrepl :as nrepl]))

(deftest start-nrepl-test
  (let [dir (str (fs/create-temp-dir {:prefix "pchat-nrepl"}))]
    (testing "a free loopback port, written to .nrepl-port, answering an eval"
      (let [port (main/start-nrepl! (fs/file dir))]
        (is (pos-int? port))
        (is (= (str port) (slurp (fs/file dir ".nrepl-port"))))
        (is (= "3" (:value (nrepl/eval! port "(+ 1 2)"))))))))
