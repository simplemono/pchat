(ns artifact.main
  "A HyperFrames composition as an artifact. The design is
  composition/index.html with its files beside it; this process serves
  them with the HyperFrames runtime and shows the composition in the
  HyperFrames player, which loads it again when a file changes. The
  HyperFrames CLI, `hyperframes` on the PATH, renders it:
  `bin/render <name>` in the workspace."
  (:require [babashka.fs :as fs]
            [babashka.nrepl.server :as nrepl]
            [clojure.string :as str]
            [org.httpkit.server :as server]
            [rframes.http :as http]))

(def composition-dir
  "composition")

(def node-modules
  "The node_modules of the HyperFrames install the `hyperframes` on the
  PATH belongs to: the player and the runtime are in there."
  (delay
    (when-let [bin (fs/which "hyperframes")]
      (->> (iterate fs/parent (fs/real-path bin))
           (take-while some?)
           (some #(when (= "node_modules" (str (fs/file-name %))) %))))))

(def player-js
  "@hyperframes/player/dist/hyperframes-player.global.js")

(def runtime-js
  "hyperframes/dist/hyperframe.runtime.iife.js")

(defn rev
  "The newest change of the composition, so that the page knows to load
  it again."
  []
  (->> (file-seq (fs/file composition-dir))
       (filter #(.isFile %))
       (remove #(str/starts-with? (.getName %) "."))
       (map #(.lastModified %))
       (reduce max 0)))

(defn with-runtime
  "The composition page for the player: the registry of the timelines
  defined before the composition's script, the runtime in the head. A
  page that has the runtime already is left alone."
  [html]
  (let [tags (str "<script>window.__timelines = window.__timelines || {};</script>"
                  "<script src=\"../vendor/hyperframes-runtime.js\"></script>")]
    (cond
      (str/includes? html "hyperframes-runtime.js") html
      (re-find #"(?i)<head[^>]*>" html) (str/replace-first html #"(?i)<head[^>]*>" #(str % tags))
      :else (str tags html))))

(def page
  "The page beside the chat: the HyperFrames player over the composition,
  loaded again at the same position when a file of it changes (the
  player watches its src attribute, not a property). The URLs are
  relative: pchat shows the page under /artifacts/<name>/."
  (str "<!doctype html><html><head><meta charset=\"utf-8\"><title>artifact</title>"
       "<style>body { margin: 0; padding: 16px; background: #fff; }"
       " hyperframes-player { display: block; width: min(100%, calc(70vh * 9 / 16)); aspect-ratio: 9 / 16; }</style>"
       "<script src=\"vendor/hyperframes-player.js\"></script></head><body>"
       "<hyperframes-player id=\"player\" src=\"composition/index.html\" controls></hyperframes-player>"
       "<script>(function () {"
       " var player = document.getElementById('player'); var rev = null;"
       " player.addEventListener('ready', function () {"
       "  var w = player.compositionWidth || 1080, h = player.compositionHeight || 1920;"
       "  player.style.aspectRatio = w + ' / ' + h;"
       "  player.style.width = 'min(100%, calc(70vh * ' + w + ' / ' + h + '))'; });"
       " setInterval(function () {"
       "  fetch('rev').then(function (r) { return r.text(); }).then(function (now) {"
       "   if (rev === null) { rev = now; return; }"
       "   if (now !== rev) {"
       "    rev = now; var t = player.currentTime || 0;"
       "    player.addEventListener('ready', function () { player.seek(t); }, {once: true});"
       "    player.setAttribute('src', 'composition/index.html?rev=' + now); }"
       "  }).catch(function () {}); }, 1000);"
       "})();</script></body></html>"))

(defn- html-response
  [body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"
             "Cache-Control" "no-cache"}
   :body body})

(defn- composition-handler
  "A file of the composition; its HTML with the runtime."
  [w]
  (let [subpath (get-in w [:ring/route-params :*])
        file (when-not (re-find #"(^|/)\." (str subpath))
               (http/resolve-file composition-dir subpath))]
    (assoc w :ring/response
           (cond
             (nil? file) http/not-found
             (str/ends-with? (str file) ".html") (html-response (with-runtime (slurp file)))
             :else (http/file-response (:ring/request w) file)))))

(defn- vendor-handler
  [relative]
  (fn [w]
    (let [file (when-let [dir @node-modules] (fs/file dir relative))]
      (assoc w :ring/response
             (if (and file (fs/exists? file))
               (http/file-response (:ring/request w) file)
               {:status 404
                :headers {"Content-Type" "text/plain"}
                :body "hyperframes is not installed: the player and the runtime come with its npm package."})))))

(def register
  [{:ring/route [:get "/"]
    :ring/handler (fn [w] (assoc w :ring/response (html-response page)))}
   {:ring/route [:get "/rev"]
    :ring/handler (fn [w] (assoc w :ring/response {:status 200
                                                   :headers {"Content-Type" "text/plain"
                                                             "Cache-Control" "no-cache"}
                                                   :body (str (rev))}))}
   {:ring/route [:get "/composition/*"]
    :ring/handler #'composition-handler}
   {:ring/route [:get "/vendor/hyperframes-player.js"]
    :ring/handler (vendor-handler player-js)}
   {:ring/route [:get "/vendor/hyperframes-runtime.js"]
    :ring/handler (vendor-handler runtime-js)}])

(defn- exit-with-parent!
  "pchat started this process; when pchat is gone, so is this."
  []
  (when-let [pid (some-> (System/getenv "PCHAT_PID") parse-long)]
    (future
      (loop []
        (Thread/sleep 2000)
        (if (some-> (.orElse (java.lang.ProcessHandle/of pid) nil) .isAlive)
          (recur)
          (System/exit 0))))))

(defn -main
  [& _args]
  (exit-with-parent!)
  (let [port (parse-long (or (System/getenv "PORT") "0"))
        nrepl-port (parse-long (or (System/getenv "NREPL_PORT") "0"))
        nrepl (nrepl/start-server! {:host "127.0.0.1" :port nrepl-port :quiet true})
        nrepl-port (.getLocalPort (:socket nrepl))]
    (spit ".nrepl-port" (str nrepl-port))
    (server/run-server (http/ring-handler (fn [] register)) {:port port})
    (println (str "artifact on http://localhost:" port ", nREPL on " nrepl-port
                  (if @node-modules
                    (str ", HyperFrames in " @node-modules)
                    ", hyperframes is not on the PATH")))
    @(promise)))
