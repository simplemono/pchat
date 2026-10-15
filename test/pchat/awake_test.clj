(ns pchat.awake-test
  (:require [clojure.test :refer [deftest is testing]]
            [pchat.awake :as awake]))

(deftest step-test
  (testing "work holds the machine, once a minute"
    (is (= :hold (awake/step nil true 0)))
    (is (nil? (awake/step 0 true 30000)))
    (is (= :hold (awake/step 0 true 60000))))
  (testing "the end of the work lets it go, once"
    (is (= :release (awake/step 0 false 1000)))
    (is (nil? (awake/step nil false 1000)))))

(deftest watch-test
  (let [socket (str (java.nio.file.Files/createTempFile
                     "pchat-awake" ".sock"
                     (make-array java.nio.file.attribute.FileAttribute 0)))
        busy (atom true)
        calls (atom [])
        watch (awake/watch! (fn [] @busy)
                            {:socket socket
                             :every-ms 20
                             :hold! (fn [_] (swap! calls conj :hold) true)
                             :release! (fn [_] (swap! calls conj :release) true)})]
    (try
      (Thread/sleep 200)
      (testing "held once while busy, the refresh a minute away"
        (is (= [:hold] @calls)))
      (reset! busy false)
      (Thread/sleep 200)
      (testing "let go once when the work is done"
        (is (= [:hold :release] @calls)))
      (finally
        (future-cancel watch))))
  (testing "no socket, no Sprite: nothing runs"
    (is (nil? (awake/watch! (constantly true) {:socket "/nonexistent/api.sock"})))))
