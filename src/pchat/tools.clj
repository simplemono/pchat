(ns pchat.tools
  "The programs the artifacts need beside babashka: a Chrome and ffmpeg
  for a video, HyperFrames for its compositions. Looked up on the PATH at
  start; the agent's shell gets them as environment variables, so that
  HyperFrames uses the Chrome of the machine and downloads none."
  (:require [pchat.env :as env]
            [babashka.fs :as fs]))

(def chrome-names
  ["google-chrome" "google-chrome-stable" "chromium" "chromium-browser" "chrome"])

(defn chrome
  "`CHROME` in the environment, else the first of the usual names on the
  PATH, else nil."
  []
  (or (not-empty (env/get "CHROME"))
      (some #(some-> (fs/which %) str) chrome-names)))

(defn env
  "The environment of the agent's shell: what .env holds and the process
  does not (see `pchat.env`), the tools' settings, Chrome."
  []
  (let [chrome (chrome)]
    (cond-> (assoc (env/below)
                   "HYPERFRAMES_NO_TELEMETRY" "1"
                   "HYPERFRAMES_SKIP_SKILLS" "1"
                   "PUPPETEER_SKIP_DOWNLOAD" "1")
      chrome (assoc "CHROME" chrome
                    "HYPERFRAMES_BROWSER_PATH" chrome
                    "PRODUCER_HEADLESS_SHELL_PATH" chrome))))

(defn report
  "One line on what is there and what is missing."
  []
  (let [found (fn [name path]
                (str name (if path (str " at " path) " missing")))]
    (str "videos: "
         (found "Chrome" (chrome)) ", "
         (found "ffmpeg" (some-> (fs/which "ffmpeg") str)) ", "
         (found "hyperframes" (some-> (fs/which "hyperframes") str)))))
