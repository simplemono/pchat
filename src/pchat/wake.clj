(ns pchat.wake
  "The alarm clock of the jobs on a machine that sleeps. pchat keeps the
  schedules (`pchat.jobs`) and knows the next minute it has to be awake
  for; this tells the machine. On a Sprite (sprites.dev, 2.0) the
  machine's own automations do it: `pchat-wake` is one automation whose
  cron line is that minute in UTC and whose action runs `bin/tick`,
  which wakes the sprite, ticks the clock and stays until the runs are
  done (an exec is what keeps a sprite awake), then lets it sleep again;
  `pchat-daily` wakes it once a day besides, so that an alarm lost to a
  crash is set again within a day. Both are set from inside the sprite
  with `sprite-env`, no token involved. Without `sprite-env` (a laptop,
  another kind of machine) nothing happens here: pchat runs on and its
  own minute ticks. `PCHAT_WAKE=off` switches it off anywhere."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def ^:dynamic *sprite-env*
  "The command; a test binds a fake."
  "sprite-env")

(def wake-name
  "pchat-wake")

(def daily-name
  "pchat-daily")

(def daily-cron
  "17 3 * * *")

(def daily-timeout-s
  600)

(def margin-s
  "What an alarm's exec may take beyond the longest run: pchat's start,
  the queue."
  300)

(defn available?
  []
  (and (not= "off" (System/getenv "PCHAT_WAKE"))
       (some? (fs/which *sprite-env*))))

(defn cron-of
  "The minute of `ms` as a five-field cron line in UTC: `30 7 16 10 *`.
  It names a minute of a day of a month; pchat sets the next one long
  before the year is round."
  [ms]
  (let [t (.atZone (java.time.Instant/ofEpochMilli ms) java.time.ZoneOffset/UTC)]
    (str (.getMinute t) " " (.getHour t) " " (.getDayOfMonth t) " " (.getMonthValue t) " *")))

(defn next-minute
  [now]
  (* 60000 (inc (quot now 60000))))

(defn- sprite-env!
  "One `sprite-env` command: `{:exit :out :err}`, never a throw."
  [& args]
  (try
    (apply process/sh {:out :string :err :string :continue true} *sprite-env* args)
    (catch Exception e
      {:exit 1 :out "" :err (str e)})))

(defn- ok?
  [{:keys [exit]}]
  (zero? exit))

(defn- say
  [& words]
  (binding [*out* *err*]
    (println "[wake]" (str/join " " words))))

(defn- automation!
  "The automation set to the cron line with the exec of `script`:
  updated when it is there, made otherwise, enabled either way."
  [name cron timeout-s script]
  (let [args ["--cron" cron "--exec" "--after" "sleep_if_woken" "--timeout" (str timeout-s) "--" script]
        updated (apply sprite-env! "automation" "update" name args)
        result (if (ok? updated)
                 updated
                 (apply sprite-env! "automation" "create" name args))]
    (if (ok? result)
      (do (sprite-env! "automation" "resume" name)
          true)
      (do (say "could not set" name "to" cron (str/trim (str (:err result) " " (:out result))))
          false))))

(defn arm!
  "The machine wakes pchat at `at` (milliseconds; now when it is past)
  for runs of up to `minutes`; without an alarm (`nil`: no scheduled
  job) the wake automation goes. `script` is `bin/tick` on the machine."
  [{:keys [script]} {:keys [at minutes]}]
  (when (available?)
    (if at
      (let [now (System/currentTimeMillis)
            when (max at (next-minute now))]
        (automation! wake-name (cron-of when) (+ (* 60 (or minutes 30)) margin-s) script))
      (do (sprite-env! "automation" "delete" wake-name)
          true))))

(defn ensure-daily!
  "The daily wake, made when it is missing."
  [{:keys [script]}]
  (when (available?)
    (or (ok? (sprite-env! "automation" "get" daily-name))
        (automation! daily-name daily-cron daily-timeout-s script))))

(comment
  (cron-of (System/currentTimeMillis))
  (available?)
  )
