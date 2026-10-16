(ns pchat.sleep-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [pchat.sleep :as sleep]))

(deftest step-test
  (testing "work resets the idle time"
    (is (= {:idle-since nil :stop? false} (sleep/step 5000 true 9000 1000))))
  (testing "idling is counted from its first round, and the stop comes with the time"
    (is (= {:idle-since 1000 :stop? false} (sleep/step nil false 1000 600000)))
    (is (= {:idle-since 1000 :stop? false} (sleep/step 1000 false 500000 600000)))
    (is (= {:idle-since 1000 :stop? true} (sleep/step 1000 false 601000 600000)))))

(deftest provisioning-test
  (let [dir (fs/create-temp-dir {:prefix "pchat-sleep"})
        f (fs/file dir "provisioning")
        now (System/currentTimeMillis)]
    (is (not (sleep/provisioning? dir now)) "no marker, nobody works on the machine")
    (spit f (str now))
    (is (sleep/provisioning? dir now) "a fresh marker: someone does")
    (fs/set-last-modified-time f (- now (* 2 60 60 1000)))
    (is (not (sleep/provisioning? dir now)) "one from two hours ago: that work has died")))

(deftest watch-test
  (let [busy (atom true)
        stops (atom 0)
        watch (sleep/watch! (fn [] @busy)
                            {:every-ms 10
                             :after-ms 100
                             :available? (constantly true)
                             :stop! (fn [] (swap! stops inc) true)})]
    (try
      (Thread/sleep 250)
      (is (= 0 @stops) "busy: no stop")
      (reset! busy false)
      (Thread/sleep 50)
      (is (= 0 @stops) "idle, but not for long enough")
      (Thread/sleep 400)
      (is (<= 2 @stops 5) "idle for the time: a stop, and more as the idle time ran out afresh (the process went on: the stop was refused, or the machine woke and idled again)")
      (reset! busy true)
      (let [n @stops]
        (Thread/sleep 250)
        (is (= n @stops) "busy again: no more stops"))
      (finally
        (future-cancel watch)))))

(deftest available-test
  (binding [sleep/*sprite-env* "no-such-command-anywhere"]
    (is (not (sleep/available?)) "without sprite-env nothing happens")))
