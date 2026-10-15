(ns pchat.schedule-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pchat.schedule :as schedule]))

(deftest describe-test
  (testing "the shapes words cover"
    (is (= "Every day at 09:00" (schedule/describe "0 9 * * *" :en)))
    (is (= "Jeden Tag um 09:00" (schedule/describe "0 9 * * *" :de)))
    (is (= "Monday to Friday at 09:00" (schedule/describe "0 9 * * 1-5" :en)))
    (is (= "Saturday and Sunday at 10:30" (schedule/describe "30 10 * * 0,6" :en)))
    (is (= "Every Monday at 08:00" (schedule/describe "0 8 * * 1" :en)))
    (is (= "Every Monday, Wednesday and Friday at 08:00" (schedule/describe "0 8 * * 1,3,5" :en)))
    (is (= "Every Sunday at 20:00" (schedule/describe "0 20 * * 7" :en)) "7 is Sunday")
    (is (= "Jeden Montag und Mittwoch um 08:30" (schedule/describe "30 8 * * 1,3" :de)))
    (is (= "On the 1st of every month at 12:00" (schedule/describe "0 12 1 * *" :en)))
    (is (= "On the 22nd of every month at 12:00" (schedule/describe "0 12 22 * *" :en)))
    (is (= "On the 13th of every month at 12:00" (schedule/describe "0 12 13 * *" :en)))
    (is (= "Am 1. jedes Monats um 12:00" (schedule/describe "0 12 1 * *" :de)))
    (is (= "Every minute" (schedule/describe "* * * * *" :en)))
    (is (= "Every 15 minutes" (schedule/describe "*/15 * * * *" :en)))
    (is (= "Every hour at minute 30" (schedule/describe "30 * * * *" :en)))
    (is (= "Every 2 hours at minute 00" (schedule/describe "0 */2 * * *" :en))))
  (testing "what words do not cover"
    (is (nil? (schedule/describe "0 9 * 1 *" :en)) "a month")
    (is (nil? (schedule/describe "0 9,17 * * *" :en)) "two times")
    (is (nil? (schedule/describe "0 9 1,15 * *" :en)) "two days")
    (is (nil? (schedule/describe "not a line" :en)))))

(deftest next-runs-test
  (let [berlin (java.time.ZoneId/of "Europe/Berlin")
        from (java.time.ZonedDateTime/of 2026 10 23 10 0 0 0 berlin)
        runs (schedule/next-runs "0 9 * * *" "Europe/Berlin" 3 from)]
    (is (= ["Sat 24 Oct 09:00" "Sun 25 Oct 09:00" "Mon 26 Oct 09:00"] (mapv #(schedule/run-text % :en) runs)))
    (is (= ["+02:00" "+01:00" "+01:00"] (mapv #(str (.getOffset %)) runs)) "the clocks went back on the 25th")
    (is (= "Sa. 24 Okt. 09:00" (schedule/run-text (first runs) :de)))
    (is (= [] (schedule/next-runs "0 0 31 2 *" "UTC" 3 from)) "never"))
  (testing "a moment in a zone"
    (is (= "Thu 15 Oct 11:00" (schedule/run-text (schedule/at-ms 1792062000000 "Europe/Berlin") :en)))
    (is (= "Thu 15 Oct 09:00" (schedule/run-text (schedule/at-ms 1792062000000 "UTC") :en)))))

(deftest zones-test
  (is (schedule/valid-zone? "Europe/Berlin"))
  (is (not (schedule/valid-zone? "Berlin")))
  (is (not (schedule/valid-zone? nil)))
  (is (some #{"Europe/Berlin" "America/New_York" "Asia/Tokyo"} schedule/zones))
  (is (not-any? #(str/starts-with? % "Etc/") schedule/zones))
  (is (= schedule/zones (vec (sort schedule/zones)))))

(defn- answering
  "A post that answers like OpenRouter would, with `content`; records
  the request."
  [content requests]
  (fn [url request]
    (swap! requests conj [url request])
    {:status 200
     :body (json/generate-string {:choices [{:message {:role "assistant" :content content}}]})}))

(deftest words->cron-test
  (testing "no words, a cron line, no key"
    (is (= {:cron nil} (schedule/words->cron {:text "  " :key "k"})))
    (is (= {:cron "0 9 * * 1-5"} (schedule/words->cron {:text " 0 9 * * 1-5 " :key "k" :post (fn [& _] (throw (ex-info "not called" {})))})))
    (is (= {:error :no-key} (schedule/words->cron {:text "every day at 9" :key ""}))))
  (testing "the model's line, the zone and the language in the instructions"
    (let [requests (atom [])
          result (schedule/words->cron {:text "jeden Werktag um 9"
                                        :tz "Europe/Berlin"
                                        :locale :de
                                        :key "k"
                                        :model "m"
                                        :post (answering "```json\n{\"cron\": \"0 9 * * 1-5\"}\n```" requests)})
          [url request] (first @requests)
          body (json/parse-string (:body request) true)]
      (is (= {:cron "0 9 * * 1-5"} result))
      (is (= "https://openrouter.ai/api/v1/chat/completions" url))
      (is (= "Bearer k" (get-in request [:headers "Authorization"])))
      (is (= "m" (:model body)))
      (is (str/includes? (-> body :messages first :content) "Europe/Berlin"))
      (is (str/includes? (-> body :messages first :content) "(German)"))
      (is (= "jeden Werktag um 9" (-> body :messages second :content)))))
  (testing "what the model cannot say as a line, a bad line, no JSON, a failing network"
    (is (= {:error "Cron kann nicht jede zweite Woche; am nächsten: jeden Freitag um 09:00."}
           (schedule/words->cron {:text "jeden zweiten Freitag" :key "k" :post (answering "{\"error\": \"Cron kann nicht jede zweite Woche; am nächsten: jeden Freitag um 09:00.\"}" (atom []))})))
    (is (= {:error :not-understood} (schedule/words->cron {:text "x" :key "k" :post (answering "{\"cron\": \"9 * *\"}" (atom []))})))
    (is (= {:error :not-understood} (schedule/words->cron {:text "x" :key "k" :post (answering "I am not sure." (atom []))})))
    (is (= {:error "OpenRouter answered 500"} (schedule/words->cron {:text "x" :key "k" :post (fn [& _] {:status 500 :body "boom"})})))
    (is (= {:error "down"} (schedule/words->cron {:text "x" :key "k" :post (fn [& _] (throw (ex-info "down" {})))})))))
