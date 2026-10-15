(ns artifact.main
  "A HyperFrames composition as an artifact. The design is
  composition/index.html with its files beside it; this process serves
  them with the HyperFrames runtime and shows the composition in the
  HyperFrames player, which loads it again when a file changes. The
  HyperFrames CLI, `hyperframes` on the PATH, renders it:
  `bin/render <name>` in the workspace.

  The variables the composition declares (`data-composition-variables`
  on its <html>) are the controls beside the player, a page of rframes:
  what the person sets is kept in variables.json and given to the
  composition as `window.__hfVariables`, the way the renderer gives it
  (`bin/render` passes variables.json), so that the preview and the
  video agree. Without declared variables the page is the player alone.
  A project that wants other controls defines `artifact.controls` in
  src/artifact/controls.clj: `controls`, a function of the declarations
  and the values to hiccup, and `register`, more entries of rframes (its
  own commands).

  Several compositions in one project: without composition/index.html,
  every folder composition/<name>/ with an index.html of its own is a
  composition, and the page shows them side by side, one player each,
  without controls (a series of shorts to look at together). Each one
  renders on its own: `hyperframes render` in its folder."
  (:require [babashka.fs :as fs]
            [babashka.nrepl.server :as nrepl]
            [cheshire.core :as json]
            [clojure.string :as str]
            [org.httpkit.server :as server]
            [rframes.command :as command]
            [rframes.http :as http]
            [rframes.replicant :as replicant]
            [rframes.sse :as sse]))

(def composition-dir
  "composition")

(def variables-file
  "The values the person set, `{\"title\": \"...\"}`: what the preview
  shows and what `bin/render` renders."
  "variables.json")

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

;;; The variables

(defn read-variables
  "The values in variables.json, {} without the file or for one that is
  not a JSON object."
  []
  (try
    (let [values (json/parse-string (slurp variables-file))]
      (if (map? values) values {}))
    (catch Exception _
      {})))

(defonce db
  (atom {:variables (read-variables)}))

(defn- entities
  "The text of an HTML attribute value."
  [s]
  (-> s
      (str/replace "&quot;" "\"")
      (str/replace "&#39;" "'")
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&amp;" "&")))

(defn- index-html
  []
  (let [file (fs/file composition-dir "index.html")]
    (when (fs/exists? file)
      (slurp file))))

(defn declarations
  "The variables composition/index.html declares on its <html>:
  `[{\"id\" \"type\" \"label\" \"default\" \"options\"}]`, the documented
  types string, number, color and boolean, `options` a list to pick
  from."
  ([] (declarations (index-html)))
  ([html]
   (let [[_ _ value] (re-find #"(?s)<html[^>]*?\sdata-composition-variables\s*=\s*(['\"])(.*?)\1" (str html))]
     (try
       (->> (json/parse-string (entities (str value)))
            (filter #(and (map? %) (string? (get % "id"))))
            (vec))
       (catch Exception _
         [])))))

(defn size
  "Width and height of the composition's root, 1080 by 1920 without."
  ([] (size (index-html)))
  ([html]
   (let [number (fn [attribute fallback]
                  (or (some-> (re-find (re-pattern (str attribute "=[\"'](\\d+)")) (str html)) second parse-long)
                      fallback))]
     [(number "data-width" 1080) (number "data-height" 1920)])))

(defn- write-variables!
  [values]
  (if (empty? values)
    (fs/delete-if-exists variables-file)
    (spit variables-file (json/generate-string values {:pretty true}))))

(defn coerce
  "The value for the declaration from the text of a control, or nil when
  it does not fit: a number for a number, one of the options when there
  are options, a text of at most 2000 characters."
  [{:strs [type options]} value]
  (let [value (case type
                "number" (let [n (if (number? value) value (parse-double (str/trim (str value))))]
                           ;; 180, not 180.0
                           (if (and n (== n (Math/rint n)) (< (abs n) 1e15)) (long n) n))
                "boolean" (if (boolean? value) value (= "true" (str value)))
                (str value))
        allowed (map #(if (map? %) (get % "value") %) options)]
    (cond
      (nil? value) nil
      (and (string? value) (< 2000 (count value))) nil
      (and (seq options) (not (some #(= (str %) (str value)) allowed))) nil
      :else value)))

(defn- set-command
  "`{:id \"title\" :value \"...\"}`: the value of a declared variable."
  [w]
  (let [{:keys [id value]} (command/command-data w)
        declaration (some #(when (= (str id) (get % "id")) %) (declarations))
        value (when declaration (coerce declaration value))]
    (if (nil? value)
      (command/rejected w "That value does not fit.")
      (do (swap! db assoc-in [:variables (str id)] value)
          (command/accepted w)))))

(defn- reset-command
  "`{:id \"title\"}` back to the declared default, `{}` all of them."
  [w]
  (let [{:keys [id]} (command/command-data w)]
    (swap! db update :variables #(if id (dissoc % (str id)) {}))
    (command/accepted w)))

(defn- persist!
  "variables.json follows the values; a file the agent wrote follows
  into the values."
  []
  (add-watch db ::persist
             (fn [_ _ old new]
               (when (not= (:variables old) (:variables new))
                 (write-variables! (:variables new)))))
  (future
    (loop [seen (when (fs/exists? variables-file) (fs/last-modified-time variables-file))]
      (Thread/sleep 1000)
      (let [now (when (fs/exists? variables-file) (fs/last-modified-time variables-file))]
        (when (and (not= seen now) (not= (read-variables) (:variables @db)))
          (swap! db assoc :variables (read-variables)))
        (recur now)))))

;;; The composition

(defn compositions
  "The folders of composition/ that are compositions of their own, when
  there is no composition/index.html: [\"short-1\" \"short-2\"], else []."
  []
  (if (fs/exists? (fs/file composition-dir "index.html"))
    []
    (->> (fs/list-dir composition-dir)
         (filter #(and (fs/directory? %)
                       (not (str/starts-with? (str (fs/file-name %)) "."))
                       (fs/exists? (fs/file % "index.html"))))
         (map #(str (fs/file-name %)))
         (sort)
         (vec))))

(defn title
  "The <title> of a composition's page, else nil."
  [html]
  (some-> (re-find #"(?is)<title[^>]*>(.*?)</title>" (str html)) second str/trim not-empty))

(defn rev
  "The newest change of the composition and of the values, so that the
  page knows to load it again."
  []
  (->> (concat (file-seq (fs/file composition-dir))
               [(fs/file variables-file)])
       (filter #(.isFile %))
       (remove #(str/starts-with? (.getName %) "."))
       (map #(.lastModified %))
       (reduce max 0)))

(defn variables-script
  "The values for the composition, as the renderer sets them."
  [values]
  (str "<script>window.__hfVariables = "
       (str/replace (json/generate-string values) "</" "<\\/")
       ";</script>"))

(defn with-runtime
  "The composition page for the player: the values, the registry of the
  timelines defined before the composition's script, the runtime in the
  head. A page that has the runtime already gets the values alone.
  `subpath` is where the page lies in composition/, for the way back to
  vendor/."
  ([html values] (with-runtime html values "index.html"))
  ([html values subpath]
   (let [runtime? (str/includes? html "hyperframes-runtime.js")
         up (apply str (repeat (inc (count (re-seq #"/" (str subpath)))) "../"))
         tags (str (variables-script values)
                   (when-not runtime?
                     (str "<script>window.__timelines = window.__timelines || {};</script>"
                          "<script src=\"" up "vendor/hyperframes-runtime.js\"></script>")))]
     (if (re-find #"(?i)<head[^>]*>" html)
       (str/replace-first html #"(?i)<head[^>]*>" #(str % tags))
       (str tags html)))))

(defn- html-response
  [body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"
             "Cache-Control" "no-cache"}
   :body body})

(defn- composition-handler
  "A file of the composition; its HTML with the runtime, the root
  composition with the values too."
  [w]
  (let [subpath (str (get-in w [:ring/route-params :*]))
        file (when-not (re-find #"(^|/)\." subpath)
               (http/resolve-file composition-dir subpath))]
    (assoc w :ring/response
           (cond
             (nil? file) http/not-found
             (= "index.html" subpath) (html-response (with-runtime (slurp file) (:variables @db) subpath))
             (str/ends-with? (str file) ".html") (html-response (with-runtime (slurp file) {} subpath))
             :else (http/file-response (:ring/request w) file)))))

(defn- stills-handler
  "A picture of the composition at a moment, stills/<t>.png, for the chat."
  [w]
  (let [file (http/resolve-file "stills" (get-in w [:ring/route-params :*]))]
    (assoc w :ring/response (if file
                              (http/file-response (:ring/request w) file)
                              http/not-found))))

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

;;; The page

(defn- send-value
  [id value]
  [[:data/command {:command/kind :variables/set
                   :command/data {:id id :value value}}]])

(defn control
  "One declared variable as a control, its value or the default."
  [values {:strs [id type label options min max step default]}]
  (let [value (get values id default)]
    [:div.control {:replicant/key id}
     [:label {:for (str "v-" id)} (or label id)]
     (cond
       (seq options)
       [:select {:id (str "v-" id) :on {:change (send-value id :event/target.value)}}
        (for [option options
              :let [[v text] (if (map? option)
                               [(get option "value") (or (get option "label") (get option "value"))]
                               [option option])]]
          [:option {:replicant/key (str v) :value (str v) :selected (= (str v) (str value))} (str text)])]

       (= "color" type)
       [:input {:id (str "v-" id) :type "color" :value (str value) :on {:change (send-value id :event/target.value)}}]

       (= "boolean" type)
       [:input {:id (str "v-" id) :type "checkbox" :checked (true? value) :on {:change (send-value id (not (true? value)))}}]

       (= "number" type)
       [:input {:id (str "v-" id) :type "number" :value (str value) :min min :max max :step (or step "any")
                :on {:change (send-value id :event/target.value)}}]

       :else
       [:input {:id (str "v-" id) :type "text" :value (str value) :on {:change (send-value id :event/target.value)}}])
     (if (contains? values id)
       [:button.reset {:title "Back to the default"
                       :on {:click [[:data/command {:command/kind :variables/reset :command/data {:id id}}]]}}
        "↺"]
       [:span.reset])]))

(defn controls
  "The controls of the declared variables."
  [declarations values]
  [:div.controls
   (for [declaration declarations]
     (control values declaration))
   [:p.hint "The video is rendered with these settings."]])

(defn- custom
  "A var of `artifact.controls`, the project's own controls, when it has
  them."
  [sym]
  (when (fs/exists? "src/artifact/controls.clj")
    (try
      (require 'artifact.controls)
      (resolve (symbol "artifact.controls" (name sym)))
      (catch Exception e
        (binding [*out* *err*]
          (println "artifact.controls:" (ex-message e)))
        nil))))

(defn- player
  "A HyperFrames player of the composition at `src`, sized for `html`."
  [src html height-vh]
  (let [[width height] (size html)]
    [:hyperframes-player {:replicant/key src
                          :src src
                          :controls true
                          ;; a width of its own, not a share of the figure, which takes the
                          ;; width of what it holds
                          :style {:aspect-ratio (str width " / " height)
                                  :width (str "calc(" height-vh "vh * " width " / " height ")")
                                  :max-width "100%"}}]))

(defn series-view
  "Several compositions side by side, one player each."
  [names]
  [:div.series
   (for [name names
         :let [html (slurp (fs/file composition-dir name "index.html"))]]
     [:figure {:replicant/key name}
      (player (str "composition/" name "/index.html") html 62)
      [:figcaption (or (title html) name)]])])

(declare single-view)

(defn page-view
  [_w]
  (if-let [names (not-empty (compositions))]
    (series-view names)
    (single-view)))

(defn single-view
  []
  (let [html (index-html)
        declarations (declarations html)
        values (:variables @db)
        [width height] (size html)]
    [:div.artifact
     [:hyperframes-player {:id "player"
                           :replicant/key "player"
                           :src "composition/index.html"
                           :controls true
                           :style {:aspect-ratio (str width " / " height)
                                   :width (str "min(100%, calc(70vh * " width " / " height "))")}}]
     (if-let [own (custom 'controls)]
       (own declarations values)
       (when (seq declarations)
         (controls declarations values)))]))

(def head
  ;; The player loads the composition again at the same position when a
  ;; file of it or a value changes (it watches its src attribute). The
  ;; URLs are relative: pchat shows the page under .../artifacts/<name>/.
  (str "<title>artifact</title>"
       "<style>"
       "body { margin: 0; padding: 16px; font: 15px/1.5 system-ui, sans-serif; color: #111; background: #fff; }"
       ".artifact { display: flex; flex-wrap: wrap; gap: 20px; align-items: flex-start; }"
       "hyperframes-player { display: block; flex: none; }"
       ".controls { flex: 1 1 240px; min-width: 220px; display: grid; gap: 10px; }"
       ".control { display: grid; grid-template-columns: minmax(80px, 34%) 1fr 28px; gap: 8px; align-items: center; }"
       ".control label { color: #444; font-size: 14px; }"
       ".control input[type=text], .control input[type=number], .control select { font: inherit; padding: 6px 8px; border: 1px solid #ccc; border-radius: 8px; min-width: 0; }"
       ".control input[type=color] { width: 44px; height: 32px; padding: 0 2px; border: 1px solid #ccc; border-radius: 8px; background: #fff; }"
       ".control input[type=checkbox] { justify-self: start; width: 18px; height: 18px; }"
       ".reset { width: 28px; height: 28px; border: none; background: none; color: #888; cursor: pointer; font-size: 16px; }"
       ".hint { margin: 4px 0 0; color: #888; font-size: 13px; }"
       ".series { display: flex; flex-wrap: wrap; gap: 20px; align-items: flex-start; }"
       ".series figure { margin: 0; display: flex; flex-direction: column; gap: 6px; }"
       ".series figcaption { font-size: 13px; color: #555; max-width: 240px; }"
       "@media (prefers-color-scheme: dark) {"
       " body { color: #eee; background: #18181b; } .control label { color: #bbb; }"
       " .control input[type=text], .control input[type=number], .control select { background: #27272a; color: #eee; border-color: #3f3f46; } }"
       "</style>"
       "<script src=\"vendor/hyperframes-player.js\"></script>"
       "<script>(function () {"
       " var rev = null;"
       " setInterval(function () {"
       "  var players = document.querySelectorAll('hyperframes-player'); if (!players.length) return;"
       "  fetch('rev').then(function (r) { return r.text(); }).then(function (now) {"
       "   if (rev === null) { rev = now; return; }"
       "   if (now !== rev) {"
       "    rev = now;"
       "    players.forEach(function (player) {"
       "     var t = player.currentTime || 0, src = player.getAttribute('src').replace(/\\?rev=.*$/, '');"
       "     player.addEventListener('ready', function () { player.seek(t); }, {once: true});"
       "     player.setAttribute('src', src + '?rev=' + now); }); }"
       "  }).catch(function () {}); }, 1000);"
       "})();</script>"
       ;; pchat serves the browser runtime of rframes; this page lives
       ;; under its origin.
       "<script defer src=\"/js/main.js\"></script>"))

(def files
  [{:ring/route [:get "/rev"]
    :ring/handler (fn [w] (assoc w :ring/response {:status 200
                                                   :headers {"Content-Type" "text/plain"
                                                             "Cache-Control" "no-cache"}
                                                   :body (str (rev))}))}
   {:ring/route [:get "/composition/*"]
    :ring/handler #'composition-handler}
   {:ring/route [:get "/stills/*"]
    :ring/handler #'stills-handler}
   {:ring/route [:get "/vendor/hyperframes-player.js"]
    :ring/handler (vendor-handler player-js)}
   {:ring/route [:get "/vendor/hyperframes-runtime.js"]
    :ring/handler (vendor-handler runtime-js)}])

(defn get-register
  []
  (replicant/expand
   (concat [{:replicant/shim "/"
             :head head}
            {:replicant/render "/"
             :render/fn #'page-view}
            {:command/kind :variables/set
             :command/fn #'set-command}
            {:command/kind :variables/reset
             :command/fn #'reset-command}]
           (some-> (custom 'register) deref)
           command/register
           files)))

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
    (persist!)
    (sse/watch! db)
    (server/run-server (http/ring-handler get-register) {:port port})
    (println (str "artifact on http://localhost:" port ", nREPL on " nrepl-port
                  (if @node-modules
                    (str ", HyperFrames in " @node-modules)
                    ", hyperframes is not on the PATH")))
    @(promise)))
