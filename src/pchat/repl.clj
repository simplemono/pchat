(ns pchat.repl
  "A chat at the REPL, an experiment: the design is made the way rchat's
  REPL agent makes it (`rchat.repl`). The model answers in Clojure forms
  that this process evaluates in the `user` namespace; `design-view`
  there, a pure function of the design map (and the time, for a motion
  design), is what the pane beside the chat shows, re-rendered after
  every change. No pi, no files: the creation lives in this process and
  the log under `.pchat/repl/<chat id>/log.edn`, from which it is
  rebuilt after a restart (`rchat.repl/replay!`).

  One `user` namespace: the REPL chat that was opened or written to last
  holds it. Opening another REPL chat installs that chat's helpers and
  replays its definitions, so two REPL chats cannot work at once. Fine
  for the experiment; a namespace per chat is the step after it.

  What this namespace keeps:

      runners   {chat id -> rchat.agent/runner with :state, the chat's
                 own atom: :design, the agent's keys, :motion/t ...}
      commands  the commands the model added with set-command!
      installed the id of the chat that holds the user namespace"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [minisweagent.log :as mlog]
            [minisweagent.observation :as observation]
            [rchat.agent :as agent]
            [rchat.repl :as repl]
            [rframes.command :as command]
            [rframes.sse :as sse]))

(def cost-limit-usd
  5.0)

(defn handle
  "`{:db the app's atom, :dir where the chats' logs live, :looks-dir
  pchat's looks (the uploads), :model \"openrouter/<id>\", :on-change
  (fn [id]) called after every change of a chat's state, so that the
  page gets a frame}`."
  [opts]
  (assoc opts
         :runners (atom {})
         :commands (atom {})
         :installed (atom nil)))

(defn- chat-dir
  [{:keys [dir]} id]
  (io/file dir (str id)))

(defn chat-ids
  "The ids of the REPL chats: the directories under `:dir`."
  [{:keys [dir]}]
  (->> (.listFiles (io/file dir))
       (filter #(.isDirectory ^java.io.File %))
       (map #(.getName ^java.io.File %))
       (sort)
       (vec)))

(defn log-file
  [handle id]
  (io/file (chat-dir handle id) "log.edn"))

(defn- config
  [{:keys [model]} dir]
  (agent/config {:model model
                 :cwd (str dir)
                 :cost-limit cost-limit-usd
                 :text-based? true
                 :system-template (slurp (io/resource "rchat/prompts/repl-system.md"))
                 :instance-template (slurp (io/resource "rchat/prompts/repl-task.md"))}))

(defn runner!
  "The runner of the chat, made on first use: its state atom, its log
  loaded, a watch that tells the page about every change."
  [{:keys [runners on-change] :as handle} id]
  (or (get @runners id)
      (let [dir (chat-dir handle id)
            state (atom {:design {}})
            runner (agent/runner {:db state
                                  :dir dir
                                  :step repl/step
                                  :harness/snapshot (fn [] (:design @state))
                                  :config-fn (fn [] (config handle dir))})]
        (.mkdirs dir)
        (add-watch state ::change (fn [_ _ _ _]
                                    (when on-change
                                      (on-change id))))
        (agent/load! runner)
        (let [runner (assoc runner :state state)]
          (swap! runners assoc id runner)
          runner))))

(defn- design-view
  []
  (some-> (ns-resolve 'user 'design-view) deref))

(defn activate!
  "Gives the `user` namespace to the chat: the helpers over its state,
  `set-command!`, `frame-at`, and its definitions replayed from the log.
  Nothing happens when the chat holds it already."
  [{:keys [installed commands] :as handle} id]
  (when (not= @installed id)
    (let [{:keys [state] :as runner} (runner! handle id)]
      (reset! installed id)
      (reset! commands {})
      (ns-unmap 'user 'design-view)
      (repl/install! (merge (repl/design-helpers state)
                            {'db state
                             'set-command! (fn [kind f]
                                             (swap! commands assoc kind f)
                                             kind)
                             'frame-at (fn [t]
                                         (when-let [view-fn (design-view)]
                                           (view-fn (:design @state) t)))}))
      (repl/replay! (:agent/log @state)
                    (fn [design]
                      (swap! state assoc :design design)))
      runner)))

(defn ensure!
  "A REPL chat exists from now on: its directory, its runner."
  [handle id]
  (runner! handle id)
  (activate! handle id))

(defn send!
  "The text and the attached images (names in pchat's looks directory)
  to the chat's agent: the images are copied into the agent's own looks
  directory, which is where it reads them from."
  [{:keys [looks-dir] :as handle} id text names]
  (let [{:keys [looks-dir] :as runner} (let [runner (activate! handle id)]
                                         (or runner (runner! handle id)))
        agent-looks (io/file (str looks-dir))]
    (.mkdirs agent-looks)
    (doseq [name names
            :let [source (io/file (str (:looks-dir handle)) (str name))]
            :when (and (not (str/includes? (str name) "/"))
                       (.isFile source))]
      (io/copy source (io/file agent-looks (str name))))
    (agent/send! runner {:text text
                         :images (vec names)})))

(defn stop!
  [{:keys [runners]} id]
  (some-> (get @runners id) agent/stop!))

(defn delete!
  "The chat is gone: its agent stopped, its directory deleted."
  [{:keys [runners installed] :as handle} id]
  (stop! handle id)
  (swap! runners dissoc id)
  (when (= @installed id)
    (reset! installed nil))
  (let [dir (chat-dir handle id)]
    (doseq [f (reverse (file-seq dir))]
      (io/delete-file f true))))

;;; The log as a chat

(defn- user-text
  [content]
  (if (string? content)
    content
    (str/join " " (keep :text content))))

(defn- image-urls
  [content]
  (when (vector? content)
    (vec (keep (fn [{:keys [type source]}]
                 (when (= "image" type)
                   (str "/looks/" (.getName (io/file (str (:path source)))))))
               content))))

(defn- last-index
  [items pred]
  (last (keep-indexed (fn [i item] (when (pred item) i)) items)))

(defn- handover?
  [output]
  (str/starts-with? (str output) observation/submit-command))

(defn- handover-text
  [output]
  (str/trim (subs (str output) (count observation/submit-command))))

(defn items
  "The log of the agent as the items of a pchat chat: the person's
  messages, the model's comments, every evaluated form as a tool item
  named `eval` with the REPL output as its result, a handover as the
  last message."
  [log]
  (loop [entries (seq log)
         items []
         pending []]
    (if-let [{:keys [type] :as e} (first entries)]
      (case type
        :run/started
        (recur (rest entries) (conj items {:kind :user :text (str (:task e))}) pending)

        :user/attached
        (let [urls (image-urls (get-in e [:message :content]))
              i (last-index items #(= :user (:kind %)))]
          (recur (rest entries)
                 (if (and i (seq urls))
                   (update-in items [i :images] (fnil into []) urls)
                   items)
                 pending))

        :user/interrupted
        (let [content (get-in e [:message :content])
              text (str/replace (user-text content) #"^The user added a new task: " "")
              urls (image-urls content)]
          (recur (rest entries)
                 (conj items (cond-> {:kind :user :text text}
                               (seq urls) (assoc :images urls)))
                 pending))

        :model/responded
        (let [note (:note e)
              items (cond-> items
                      (not (str/blank? note)) (conj {:kind :assistant :text note :thinking "" :done? true}))
              [items pending] (reduce (fn [[items pending] {:keys [command]}]
                                        (if (str/blank? command)
                                          [items (conj pending nil)]
                                          (let [i (count items)]
                                            [(conj items {:kind :tool :id (str i) :name "eval" :args {:command command}})
                                             (conj pending i)])))
                                      [items []]
                                      (:actions e))]
          (recur (rest entries) items pending))

        :actions/observed
        (let [items (reduce (fn [items [i {:keys [output returncode]}]]
                              (cond
                                (nil? i) items
                                (handover? output) (assoc items i {:kind :assistant
                                                                   :text (handover-text output)
                                                                   :thinking ""
                                                                   :done? true})
                                :else (update items i assoc
                                              :done? true
                                              :result (str output)
                                              :error? (not (zero? (or returncode 0))))))
                            items
                            (map vector pending (:outputs e)))]
          (recur (rest entries) items []))

        :run/crashed
        (recur (rest entries) (conj items {:kind :note :text (str "The agent crashed: " (or (:error e) (:message e)))}) pending)

        (recur (rest entries) items pending))
      items)))

(defn- status-of
  [state]
  (if (= :working (:agent/status state))
    :working
    :idle))

(defn chat-value
  "What pchat's chat shows of a REPL chat: its items, status and cost."
  [{:keys [runners]} id]
  (if-let [{:keys [state]} (get @runners id)]
    (let [{:keys [agent/log] :as s} @state]
      {:mode :repl
       :items (items log)
       :status (status-of s)
       :cost (double (or (mlog/cost log) 0.0))})
    {:mode :repl
     :items []
     :status :idle
     :cost 0.0}))

;;; The creation

(defn- data-only
  "A view the model wrote may hold a function by mistake; a function
  would drop the whole frame. It becomes nothing."
  [hiccup]
  (walk/postwalk (fn [x] (if (fn? x) nil x)) hiccup))

(defn render
  "The hiccup of the creation: `view-fn` over the design, with the time
  for a motion design; an error as a line, never a dropped frame."
  ([view-fn design]
   (try
     (data-only (view-fn design))
     (catch Exception e
       [:p.error [:i18n :repl/view-failed {:error (ex-message e)}]])))
  ([view-fn design t]
   (try
     (data-only (view-fn design t))
     (catch Exception e
       [:p.error [:i18n :repl/frame-failed {:error (ex-message e)}]]))))

(defn snapshot
  "What the pane of the chat renders: the design, its view function when
  this chat holds the `user` namespace, the motion state, the code."
  [{:keys [runners installed]} id]
  (when-let [{:keys [state]} (get @runners id)]
    (let [{:keys [design agent/log] :as s} @state]
      {:design design
       :duration (:duration design)
       :view-fn (when (= @installed id) (design-view))
       :motion/t (:motion/t s 0.0)
       :motion/playing? (boolean (:motion/playing? s))
       :source (repl/source-of log "design-view")})))

(defn enrich
  "The app's value with every REPL chat's items, status and cost in
  place, and the pane of the current one under `:repl/pane`."
  [handle value]
  (let [ids (filter #(= :repl (get-in value [:chats % :mode])) (keys (:chats value)))
        value (reduce (fn [value id]
                        (update-in value [:chats id] merge (chat-value handle id)))
                      value
                      ids)]
    (if (= :repl (get-in value [:chats (:current value) :mode]))
      (assoc value :repl/pane (snapshot handle (:current value)))
      value)))

;;; Motion, the server-rendered player: t in the chat's state, a tick

(def tick-ms
  16)

(defn- duration-of
  [state]
  (double (get-in state [:design :duration] 10)))

(defn- tick-loop!
  [state]
  (let [started (System/nanoTime)
        t0 (:motion/t @state 0.0)]
    (loop []
      (when (:motion/playing? @state)
        (let [duration (duration-of @state)
              t (+ t0 (/ (- (System/nanoTime) started) 1e9))]
          (if (>= t duration)
            (swap! state assoc :motion/t duration :motion/playing? false)
            (do (swap! state assoc :motion/t t)
                (sse/refresh-all!)
                (Thread/sleep tick-ms)
                (recur))))))))

(defn- current-state
  [{:keys [db runners]}]
  (some-> (get @runners (:current @db)) :state))

(defn- coerce
  [current value]
  (if (and (number? current) (string? value))
    (or (parse-long value) (parse-double value) value)
    value))

(defn- design-set
  [handle w]
  (let [{:keys [path value]} (command/command-data w)
        state (current-state handle)
        path (when (and (vector? path) (seq path))
               (into [:design] path))]
    (if (and state path)
      (do (swap! state (fn [s] (assoc-in s path (coerce (get-in s path) value))))
          (command/accepted w))
      (command/rejected w :not-a-design-path))))

(defn- motion-toggle
  [handle w]
  (if-let [state (current-state handle)]
    (let [playing? (not (:motion/playing? @state))
          t (:motion/t @state 0.0)]
      (swap! state assoc
             :motion/playing? playing?
             :motion/t (if (and playing? (>= t (duration-of @state))) 0.0 t))
      (when playing?
        (future (tick-loop! state)))
      (command/accepted w))
    (command/rejected w :no-repl-chat)))

(defn- motion-seek
  [handle w]
  (if-let [state (current-state handle)]
    (let [value (or (parse-double (str (:value (command/command-data w)))) 0.0)]
      (swap! state assoc
             :motion/playing? false
             :motion/t (* (duration-of @state) (/ value 1000.0)))
      (sse/refresh-all!)
      (command/accepted w))
    (command/rejected w :no-repl-chat)))

(defn register
  "The commands of the pane, and the ones the model added with
  `set-command!`; called per request, so a new command is there at
  once."
  [{:keys [commands] :as handle}]
  (into [{:command/kind :design/set
          :command/fn (fn [w] (design-set handle w))}
         {:command/kind :repl/toggle
          :command/fn (fn [w] (motion-toggle handle w))}
         {:command/kind :repl/seek
          :command/fn (fn [w] (motion-seek handle w))}]
        (for [[kind f] @commands]
          {:command/kind kind
           :command/fn f})))
