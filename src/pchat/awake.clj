(ns pchat.awake
  "The machine kept awake while there is work. A Sprite (sprites.dev)
  pauses some 30 seconds after the last request from outside, frozen
  with everything on it: an agent that is still working, or a video on
  its way, would stand still until someone opens the page again. Calls
  the agent makes to the web do not count. While `busy?` answers true,
  a task through the Sprite's API on `socket` holds it, refreshed every
  minute with five minutes to live (a crashed pchat leaves it to run
  out), and let go once the work is done. Without the socket, anywhere
  but on a Sprite, nothing happens."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]))

(def socket
  "/.sprite/api.sock")

(def task
  "The task's name: one for all of pchat's work."
  "pchat")

(def refresh-ms
  60000)

(defn- api!
  "One call to the Sprite's API; true when it answered with success."
  [socket method path body]
  (zero? (:exit (apply process/shell
                       {:out :string :err :string :continue true}
                       "curl" "-s" "-f" "--max-time" "10" "--unix-socket" socket
                       "-H" "Content-Type: application/json" "-X" method
                       (str "http://sprite" path)
                       (when body ["-d" body])))))

(defn hold!
  [socket]
  (api! socket "PUT" (str "/v1/tasks/" task) "{\"expire\":\"5m\"}"))

(defn release!
  [socket]
  (api! socket "DELETE" (str "/v1/tasks/" task) nil))

(defn step
  "What to do now, given when the task was last held (nil: it is not):
  :hold when there is work and the hold is missing or a minute old,
  :release when the work is done, else nil."
  [held-at busy? now]
  (cond
    (and busy? (or (nil? held-at) (<= refresh-ms (- now held-at)))) :hold
    (and (not busy?) held-at) :release))

(defn watch!
  "Every few seconds: holds the machine while `busy?` answers true. A
  call that fails is tried again on the next round."
  ([busy?]
   (watch! busy? {}))
  ([busy? {:keys [socket hold! release! every-ms]
           :or {socket socket hold! hold! release! release! every-ms 5000}}]
   (when (fs/exists? socket)
     (future
       (loop [held-at nil]
         (Thread/sleep every-ms)
         (recur
          (try
            (let [now (System/currentTimeMillis)]
              (case (step held-at (busy?) now)
                :hold (if (hold! socket) now held-at)
                :release (if (release! socket) nil held-at)
                held-at))
            (catch Exception e
              (binding [*out* *err*]
                (println "[awake]" (str e)))
              held-at))))))))
