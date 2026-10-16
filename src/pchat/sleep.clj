(ns pchat.sleep
  "The machine put to sleep when there is nothing to do. A Sprite
  (sprites.dev, 2.0) made with `auto_idle: false` runs until someone
  stops it, and pchat does, with `sprite-env stop`, once the machine
  has idled for a while: no chat works, no video renders, no task of
  `bin/background` runs, no run of a job is going, no page is open, and
  nothing is being installed from outside (the markers of
  `pchat.setup`, and `~/.local/provisioning`, which whoever sets the
  machine up touches while working on it; one older than an hour counts
  for nothing, that work has died). The next request to the page, the
  alarm of the jobs (`pchat.wake`) or a command from outside starts the
  machine again, in about a second, with everything as it was. Without
  `sprite-env` (a laptop) nothing happens here; `PCHAT_SLEEP=off`
  switches it off anywhere, `PCHAT_SLEEP_AFTER_MIN` is the idle time,
  ten minutes by default. A machine whose auto idle is on sleeps by
  itself long before, so this is nothing to it."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def ^:dynamic *sprite-env*
  "The command; a test binds a fake."
  "sprite-env")

(def default-after-min
  10)

(def provisioning-max-age-ms
  (* 60 60 1000))

(defn after-ms
  []
  (* 60000 (or (some-> (System/getenv "PCHAT_SLEEP_AFTER_MIN") parse-long)
               default-after-min)))

(defn available?
  []
  (and (not= "off" (System/getenv "PCHAT_SLEEP"))
       (some? (fs/which *sprite-env*))))

(defn provisioning?
  "Whether someone works on the machine from outside: `provisioning` in
  `markers-dir`, touched within the hour."
  [markers-dir now]
  (let [f (fs/file markers-dir "provisioning")]
    (and (fs/exists? f)
         (< (- now (fs/file-time->millis (fs/last-modified-time f))) provisioning-max-age-ms))))

(defn step
  "The idle time kept and the decision, `{:idle-since :stop?}`: work
  resets the idle time, `after-ms` of idling is the moment to stop."
  [idle-since busy? now after-ms]
  (if busy?
    {:idle-since nil :stop? false}
    (let [since (or idle-since now)]
      {:idle-since since :stop? (<= after-ms (- now since))})))

(defn stop!
  "`sprite-env stop`: true when the machine took it."
  []
  (try
    (let [{:keys [exit err]} (process/sh {:out :string :err :string :continue true} *sprite-env* "stop")]
      (when-not (zero? exit)
        (binding [*out* *err*]
          (println "[sleep] sprite-env stop failed:" (str/trim (str err)))))
      (zero? exit))
    (catch Exception e
      (binding [*out* *err*]
        (println "[sleep]" (str e)))
      false)))

(defn watch!
  "Every half minute: the machine stopped once it has idled for
  `after-ms` while `busy?` answers false. A warm stop freezes the
  process with the machine and the wake resumes it, so the idle time
  starts afresh after a stop; a stop that was refused is tried again
  on the next round."
  ([busy?]
   (watch! busy? {}))
  ([busy? {:keys [every-ms after-ms stop! available?]
           :or {every-ms 30000 after-ms (after-ms) stop! stop! available? available?}}]
   (when (available?)
     (future
       (loop [idle-since nil]
         (Thread/sleep every-ms)
         (recur
          (try
            (let [{:keys [idle-since stop?]} (step idle-since (busy?) (System/currentTimeMillis) after-ms)]
              (if stop?
                (do (println "[sleep] idle for" (quot after-ms 60000) "minutes: the machine stops")
                    (stop!)
                    nil)
                idle-since))
            (catch Exception e
              (binding [*out* *err*]
                (println "[sleep]" (str e)))
              idle-since))))))))
