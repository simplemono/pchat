(ns pchat.schedule
  "When a job runs, in the person's words (the job itself is
  `pchat.jobs`): the model turns a sentence into a cron line, the page
  shows the line back as words with the next runs in the job's time
  zone, and the person saves it when it is what they meant. A technical
  person types the cron line itself; it is taken as it is."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [pchat.jobs :as jobs]))

;;; Time zones

(def zones
  "The region zones, `Europe/Berlin`, sorted; the offsets and aliases
  (`Etc/GMT+3`, `EST`) are left out."
  (->> (java.time.ZoneId/getAvailableZoneIds)
       (filter #(and (str/includes? % "/")
                     (not (str/starts-with? % "Etc/"))
                     (not (str/starts-with? % "SystemV/"))))
       (sort)
       (vec)))

(defn valid-zone?
  [tz]
  (boolean (and (string? tz)
                (not (str/blank? tz))
                (try (java.time.ZoneId/of tz) true
                     (catch Exception _ false)))))

;;; The next runs

(defn next-runs
  "The next `n` runs of the cron line in the zone, after `from` (this
  minute in the zone unless given), as moments in that zone."
  ([cron tz n]
   (next-runs cron tz n (jobs/now-in tz)))
  ([cron _tz n from]
   (->> (iterate #(jobs/next-run cron %) (jobs/next-run cron from))
        (take-while some?)
        (take n)
        (vec))))

(defn run-text
  "A moment as the person reads it, `Thu 16 Oct 09:00`."
  [^java.time.ZonedDateTime at locale]
  (.format at (java.time.format.DateTimeFormatter/ofPattern
               "EEE d MMM HH:mm"
               (if (= :de locale) java.util.Locale/GERMAN java.util.Locale/ENGLISH))))

(defn at-ms
  "The moment of the milliseconds in the zone."
  [ms tz]
  (.atZone (java.time.Instant/ofEpochMilli ms) (jobs/zone {:tz tz})))

;;; The line as words

(def ^:private day-names
  {:en ["Sunday" "Monday" "Tuesday" "Wednesday" "Thursday" "Friday" "Saturday"]
   :de ["Sonntag" "Montag" "Dienstag" "Mittwoch" "Donnerstag" "Freitag" "Samstag"]})

(def ^:private words
  {:en {:daily "Every day at %s"
        :weekdays "Monday to Friday at %s"
        :weekends "Saturday and Sunday at %s"
        :weekly "Every %s at %s"
        :monthly "On the %s of every month at %s"
        :minute "Every minute"
        :minutes "Every %d minutes"
        :hourly "Every hour at minute %s"
        :hours "Every %d hours at minute %s"
        :and " and "}
   :de {:daily "Jeden Tag um %s"
        :weekdays "Montag bis Freitag um %s"
        :weekends "Samstag und Sonntag um %s"
        :weekly "Jeden %s um %s"
        :monthly "Am %s jedes Monats um %s"
        :minute "Jede Minute"
        :minutes "Alle %d Minuten"
        :hourly "Jede Stunde zur Minute %s"
        :hours "Alle %d Stunden zur Minute %s"
        :and " und "}})

(defn- ordinal
  [n locale]
  (if (= :de locale)
    (str n ".")
    (str n (cond
             (<= 11 (mod n 100) 13) "th"
             (= 1 (mod n 10)) "st"
             (= 2 (mod n 10)) "nd"
             (= 3 (mod n 10)) "rd"
             :else "th"))))

(defn- day-list
  "`Monday, Wednesday and Friday`, Monday first."
  [weekdays locale]
  (let [names (get day-names locale (:en day-names))
        days (map names (sort-by #(mod (+ % 6) 7) weekdays))]
    (if (= 1 (count days))
      (first days)
      (str (str/join ", " (butlast days)) (get-in words [locale :and] " and ") (last days)))))

(defn- step-of
  "The step of a set like #{0 15 30 45} over 0..hi, or nil."
  [s hi]
  (let [v (vec (sort s))]
    (when (and (< 1 (count v)) (zero? (first v)))
      (let [d (- (second v) (first v))]
        (when (and (pos? d) (= v (vec (range 0 (inc hi) d))))
          d)))))

(defn describe
  "The cron line as words in the locale, or nil for a line words do not
  cover (the page shows the line itself then)."
  [cron locale]
  (when-let [[minutes hours days months weekdays] (jobs/parse-cron cron)]
    (let [t (get words locale (:en words))
          one (fn [s] (when (= 1 (count s)) (first s)))
          all-days? (= 31 (count days))
          all-weekdays? (= 7 (count weekdays))
          all-hours? (= 24 (count hours))
          clock (when (and (one minutes) (one hours))
                  (format "%02d:%02d" (one hours) (one minutes)))]
      (cond
        (not= 12 (count months)) nil
        (and clock all-days? all-weekdays?) (format (:daily t) clock)
        (and clock all-days? (= #{1 2 3 4 5} weekdays)) (format (:weekdays t) clock)
        (and clock all-days? (= #{0 6} weekdays)) (format (:weekends t) clock)
        (and clock all-days? (<= (count weekdays) 3)) (format (:weekly t) (day-list weekdays locale) clock)
        (and clock all-weekdays? (one days)) (format (:monthly t) (ordinal (one days) locale) clock)
        (not (and all-days? all-weekdays?)) nil
        (and all-hours? (= 60 (count minutes))) (:minute t)
        (and all-hours? (step-of minutes 59)) (format (:minutes t) (step-of minutes 59))
        (and all-hours? (one minutes)) (format (:hourly t) (format "%02d" (one minutes)))
        (and (one minutes) (step-of hours 23)) (format (:hours t) (step-of hours 23) (format "%02d" (one minutes)))
        :else nil))))

;;; The words as a line, by the model

(def ^:private instructions
  "You turn a person's words about when something should happen into a cron line of five fields: minute hour day-of-month month weekday (0-6, Sunday is 0). Answer with JSON only, nothing around it.
{\"cron\": \"0 9 * * 1-5\"} when the words fit one cron line.
{\"error\": \"...\"} when they do not: every other week, the last day of a month, one date only, seconds, or several times that would need two lines. The error is one sentence in the person's language (%s): what cron cannot do, and the closest cron line as words.
The fields are in the person's time zone, %s; write no zone. Now it is %s there. 'at 9' is 09:00, 'at 5pm' is 17:00. Weekdays are Monday to Friday (1-5). 'every hour' is 0 * * * *. 'every 30 minutes' is */30 * * * *. 'first of the month' is day 1. 'every Monday' is weekday 1 with a time; without a time in the words take 09:00.")

(defn- language-name
  [locale]
  (if (= :de locale) "German" "English"))

(defn- answer-of
  "The JSON object in the model's answer, or nil."
  [content]
  (when-let [text (some->> (str content) (re-find #"(?s)\{.*\}"))]
    (try
      (json/parse-string text true)
      (catch Exception _
        nil))))

(defn words->cron
  "`{:cron line}` for the words, `{:cron nil}` for no words, or
  `{:error text}` (`:no-key` without an OpenRouter key, `:not-understood`
  when the model gave no line, else the model's or the network's
  sentence). A cron line in the words is taken as it is. `:post` is the
  HTTP post, babashka's unless the caller gives one."
  [{:keys [text tz locale key model post] :or {post http/post}}]
  (let [text (str/trim (str text))]
    (cond
      (str/blank? text) {:cron nil}
      (jobs/valid-cron? text) {:cron text}
      (str/blank? key) {:error :no-key}
      :else
      (try
        (let [now (.format (jobs/now-in tz) (java.time.format.DateTimeFormatter/ofPattern "EEEE, d MMMM yyyy HH:mm" java.util.Locale/ENGLISH))
              response (post "https://openrouter.ai/api/v1/chat/completions"
                             {:headers {"Authorization" (str "Bearer " key)
                                        "Content-Type" "application/json"}
                              :body (json/generate-string
                                     {:model model
                                      :temperature 0
                                      :max_tokens 300
                                      :messages [{:role "system"
                                                  :content (format instructions (language-name locale) tz now)}
                                                 {:role "user" :content text}]})
                              :timeout 30000
                              :throw false})
              content (when (= 200 (:status response))
                        (-> (json/parse-string (:body response) true) :choices first :message :content))
              {:keys [cron error]} (answer-of content)]
          (cond
            (and (string? cron) (jobs/valid-cron? (str/trim cron))) {:cron (str/trim cron)}
            (and (string? error) (not (str/blank? error))) {:error error}
            (not= 200 (:status response)) {:error (str "OpenRouter answered " (:status response))}
            :else {:error :not-understood}))
        (catch Exception e
          {:error (str (ex-message e))})))))

(comment
  (describe "0 9 * * 1-5" :en)
  (next-runs "0 9 * * 1-5" "Europe/Berlin" 3)
  )
