(ns pchat.wake-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pchat.wake :as wake]))

(defn- fake-sprite-env!
  "A `sprite-env` that records its calls in `log` and fails an `update`
  of what was never created."
  []
  (let [dir (fs/create-temp-dir {:prefix "pchat-wake"})
        log (fs/path dir "calls")
        script (fs/path dir "sprite-env")]
    (spit (str script)
          (str "#!/bin/sh\n"
               "echo \"$@\" >> " log "\n"
               "case \"$1 $2\" in\n"
               "  'automation update') grep -q \"automation create $3 \" " log " || { echo not found >&2; exit 1; } ;;\n"
               "  'automation get') grep -q \"automation create $3 \" " log " || { echo not found >&2; exit 1; } ;;\n"
               "esac\n"
               "exit 0\n"))
    (fs/set-posix-file-permissions script "rwx------")
    {:script (str script) :log (str log)}))

(defn- calls
  [log]
  (if (fs/exists? log) (str/split-lines (slurp log)) []))

(deftest cron-of-test
  (is (= "30 7 16 10 *" (wake/cron-of (.toEpochMilli (java.time.Instant/parse "2026-10-16T07:30:00Z")))))
  (is (= "0 0 1 1 *" (wake/cron-of (.toEpochMilli (java.time.Instant/parse "2027-01-01T00:00:00Z")))))
  (is (= 1792000080000 (wake/next-minute 1792000020001))))

(deftest arm-test
  (let [{:keys [script log]} (fake-sprite-env!)
        at (+ (System/currentTimeMillis) (* 10 60 1000))
        cron (wake/cron-of (* 60000 (quot at 60000)))]
    (binding [wake/*sprite-env* script]
      (testing "the first alarm is made (the update of what is not there fails), then enabled"
        (is (true? (wake/arm! {:bb "/home/sprite/.local/bin/bb" :script "/home/sprite/pchat/workspace/bin/tick"} {:at at :minutes 30})))
        (is (= [(str "automation update pchat-wake --cron " cron " --exec --after sleep_if_woken --timeout 2100 -- /home/sprite/.local/bin/bb /home/sprite/pchat/workspace/bin/tick")
                (str "automation create pchat-wake --cron " cron " --exec --after sleep_if_woken --timeout 2100 -- /home/sprite/.local/bin/bb /home/sprite/pchat/workspace/bin/tick")
                "automation resume pchat-wake"]
               (calls log))))
      (testing "the next alarm is an update"
        (wake/arm! {:bb "bb" :script "/t"} {:at (+ at 60000) :minutes 5})
        (is (= (str "automation update pchat-wake --cron " (wake/cron-of (* 60000 (quot (+ at 60000) 60000))) " --exec --after sleep_if_woken --timeout 600 -- bb /t")
               (nth (calls log) 3)))
        (is (= "automation resume pchat-wake" (nth (calls log) 4))))
      (testing "an alarm in the past is set for the next minute"
        (wake/arm! {:bb "bb" :script "/t"} {:at 0 :minutes 1})
        (is (str/includes? (nth (calls log) 5) (wake/cron-of (wake/next-minute (System/currentTimeMillis))))))
      (testing "no scheduled job: the alarm goes"
        (wake/arm! {:bb "bb" :script "/t"} nil)
        (is (= "automation delete pchat-wake" (last (calls log)))))
      (testing "the daily wake is made once"
        (is (true? (wake/ensure-daily! {:bb "bb" :script "/t"})))
        (is (= (str "automation create pchat-daily --cron 17 3 * * * --exec --after sleep_if_woken --timeout 600 -- bb /t")
               (nth (calls log) (- (count (calls log)) 2))))
        (let [n (count (calls log))]
          (wake/ensure-daily! {:bb "bb" :script "/t"})
          (is (= (inc n) (count (calls log))) "only the look"))))))

(deftest without-sprite-env-test
  (binding [wake/*sprite-env* "no-such-command-anywhere"]
    (is (false? (boolean (wake/available?))))
    (is (nil? (wake/arm! {:bb "bb" :script "/t"} {:at 1 :minutes 1})))
    (is (nil? (wake/ensure-daily! {:bb "bb" :script "/t"})))))
