(ns pchat.view
  "The page as a function of the app value and the chat the connection
  shows: the sidebar with the chats, the header, the chat (feed and
  composer) and its artifacts. Which chat a browser tab shows is the
  tab's, not the app's: `page` takes its id in `opts`. Classes follow
  rchat's stylesheet, which pchat ships as its own, plus pchat.css for
  the sidebar. The texts are m1p reference tuples, `[:i18n :chat/new]`,
  that `pchat.i18n/translate` turns into the language of the person."
  (:require [clojure.string :as str]
            [pchat.artifacts :as artifacts]
            [pchat.chat :as chat]
            [pchat.chats :as chats]
            [pchat.genui :as genui]
            [pchat.jobs :as jobs]
            [pchat.jobs-view :as jobs-view]
            [pchat.schedule :as schedule]
            [pchat.skills :as skills]
            [rchat.feed :as feed]))

(defn draft-key
  "Where the browser keeps the draft of a chat; one per chat, so that a
  switch of the chat keeps what was typed."
  [id]
  [:pchat/draft id])

(defn attachments-key
  [id]
  [:pchat/attachments id])

(defn send-actions
  "Sends the draft and the attachments, images and clips, to the chat,
  then clears both."
  [id]
  [[:data/command
    {:command/kind :chat/send
     :command/data {:id id
                    :text [:store/deref (draft-key id)]
                    :images [:store/deref (attachments-key id)]}}
    {:on-success [[:store/dissoc (draft-key id)]
                  [:store/dissoc (attachments-key id)]]}]])

(defn- tool-label
  "The row of a tool call, named for a person; a tool of a connection,
  mcp__<connection>__<tool>, by its connection."
  [{:keys [name]}]
  (let [[_ connection tool] (re-matches #"mcp__(.+?)__(.+)" (str name))]
    (cond
      (= "bash" name) [:i18n :tool/bash]
      (= "read" name) [:i18n :tool/read]
      (= "write" name) [:i18n :tool/write]
      (= "edit" name) [:i18n :tool/edit]
      (= "tool_search" name) [:i18n :tool/search]
      (= "eval" name) [:i18n :tool/eval]
      connection [:i18n :tool/connection {:tool tool :connection connection}]
      :else [:i18n :tool/other {:name name}])))

(defn- tool-detail
  [{:keys [args]}]
  (or (:command args)
      (:path args)
      (:file_path args)
      (:query args)
      (pr-str args)))

(defn- tool-view
  [{:keys [done? error? result] :as item}]
  [:details.work {:class (when error? "failed")}
   [:summary (tool-label item)
    (cond
      (not done?) [:i18n :tool/running]
      error? [:i18n :tool/failed])]
   [:pre.command (tool-detail item)]
   (when done?
     (let [text (chat/result-text result)]
       (when (seq text)
         [:pre.output (feed/cut 2000 text)])))])

(defn- item-view
  [index {:keys [kind text images media error handover?] :as item} opts]
  (case kind
    :user
    [:div.item.user {:replicant/key index}
     [:div.label [:i18n :chat/you]]
     (when (seq text)
       [:p.text text])
     (when (seq images)
       [:div.looks
        (for [url images
              :when url]
          [:img {:src url
                 :loading "lazy"}])])
     (when (seq media)
       [:div.looks.clips
        (for [{:keys [kind name]} media
              :let [url (str (chats/url (:id opts)) "/uploads/" name)]]
          (if (= :audio kind)
            [:audio {:src url
                     :controls true
                     :preload "metadata"}]
            [:video {:src url
                     :controls true
                     :preload "metadata"}]))])]

    :assistant
    (if handover?
      [:div.item.handover {:replicant/key index}
       [:div.label [:i18n :chat/handover]]
       [:div.text (genui/children text opts)]
       (when error
         [:p.error error])]
      [:div.item.step {:replicant/key index}
       [:div.text (genui/children text opts)]
       (when error
         [:p.error error])])

    :tool
    [:div.item.step {:replicant/key index}
     (tool-view item)]

    :note
    [:div.item.note {:replicant/key index}
     [:p.text text]]))

(defn- with-handover
  "The last message of a finished run is the one the user reads: it gets
  the handover style, the ones before it are progress notes."
  [{:keys [items status]}]
  (let [last-index (dec (count items))]
    (map-indexed (fn [i item]
                   (cond-> item
                     (and (= i last-index)
                          (= :assistant (:kind item))
                          (:done? item)
                          (= :idle status))
                     (assoc :handover? true)))
                 items)))

(defn- shown?
  "An assistant message that only carried tool calls has no text and is
  not an item on the page."
  [{:keys [kind text error done?]}]
  (not (and (= :assistant kind)
            done?
            (str/blank? text)
            (nil? error))))

(def attach-input-id
  "The id of the composer's file input: a label elsewhere on the page
  opens the file picker with it."
  "pchat-attach")

(defn- attach-label
  "What the button of the intro asks for, by the inputs of the skill."
  [kinds]
  (cond
    (= #{:video :audio} kinds) [:i18n :task/attach-clip]
    (= #{:video} kinds) [:i18n :task/attach-video]
    (= #{:audio} kinds) [:i18n :task/attach-audio]
    (= #{:image} kinds) [:i18n :task/attach-image]
    :else [:i18n :task/attach]))

(defn intro
  "The first thing a chat with a task says: the task and what to bring,
  with a button that opens the file picker when the skill takes files."
  [skill locale]
  [:div.item.handover.intro {:replicant/key "intro"}
   [:div.label (skills/text skill :title locale)]
   [:p.text (skills/text skill :ask locale)]
   (when-let [kinds (not-empty (skills/inputs skill))]
     [:label.attach-big {:for attach-input-id}
      (attach-label kinds)])])

(defn task-picker
  "A new chat starts with what it is for: a card for each skill, and a
  chat without a fixed task."
  [{:keys [id]} skills locale]
  (let [pick (fn [task]
               {:on {:click [[:data/command {:command/kind :chat/task
                                             :command/data {:id id
                                                            :task task}}]]}})]
    [:section.tasks
     [:h2 [:i18n :task/heading]]
     [:div.task-list
      (for [{:keys [name description] :as skill} skills]
        [:button.task (assoc (pick name) :replicant/key name)
         [:strong (skills/text skill :title locale)]
         [:span (or (skills/text skill :summary locale) description)]])
      [:button.task.free (assoc (pick "free") :replicant/key "free")
       [:strong [:i18n :task/free]]
       [:span [:i18n :task/free-summary]]]]]))

(defn feed
  ([chat]
   (feed chat nil))
  ([{:keys [id status] :as chat} intro]
   [:section.feed {:data-stick-to-end "true"}
    intro
    (->> (with-handover chat)
         (map-indexed vector)
         (filter (fn [[_ item]] (shown? item)))
         (map (fn [[index item]] (item-view index item {:id id}))))
    (when (= :working status)
      [:div.item.working {:replicant/key "working"}
       [:span.dot]
       [:span [:i18n :chat/working]]])]))

(defn chat-name
  [{:keys [name items]}]
  (or name
      (some #(when (= :user (:kind %)) (first (str/split-lines (:text %)))) items)
      [:i18n :chat/untitled]))

(defn- delete-confirm
  "The row of a chat whose deletion is armed (see `:chat/delete`): the
  question, then delete for good or keep."
  [id]
  [:div.confirm
   [:span [:i18n :chat/delete-sure]]
   [:span.choices
    [:button.link.danger {:on {:click [[:data/command {:command/kind :chat/delete
                                                       :command/data {:id id}}]]}}
     [:i18n :chat/delete-yes]]
    [:button.link {:on {:click [[:data/command {:command/kind :chat/keep}]]}}
     [:i18n :chat/keep]]]])

(defn- chat-actions
  "The menu behind the three dots of a sidebar row: what one can do with
  that chat, like the menu of a chat on claude.ai. A <details>, so the
  browser opens and closes it; the runtime (`pchat.client`) closes it
  on a click elsewhere. The job (see `pchat.jobs`) names the tab, so
  that the job's page opens here; a delete arms the question in the
  row. `:pchat/timezone` is the browser's time zone, the job's from then
  on."
  [{:keys [id]}]
  [:details.actions
   [:summary {:aria-label [:i18n :chat/actions]
              :title [:i18n :chat/actions]}
    "\u22ee"]
   [:div.items
    [:button.item.job {:on {:click [[:data/command {:command/kind :job/turn
                                                    :command/data {:id id
                                                                   :tab :pchat/tabid
                                                                   :tz :pchat/timezone}}]]}}
     [:i18n :job/turn]]
    [:button.item.danger {:on {:click [[:data/command {:command/kind :chat/delete
                                                       :command/data {:id id}}]]}}
     [:i18n :chat/delete]]]])

(defn connections-tab
  "The Connections tab of the settings: what a connection is, the
  connections one row each, the form for a new one, and the way to let
  the agent of the chat `id` do it."
  [connections id]
  [:section.connections
   [:p.explain [:i18n :connections/explain]]
   [:div.list
    (if (seq connections)
      (map connection-row (vals connections))
      [:p.none [:i18n :connections/none]])]
   (when (seq connections)
     [:p.note [:i18n :connections/next-message]])
   (connection-form connections)
   (agent-form id)])

(defn appearance-tab
  "The Appearance tab of the settings: the scheme of the page, light,
  dark or the system's. The choice lives in the browser (`:ui/theme`
  in `pchat.client` keeps it in localStorage), not in the db; the
  server only names the three choices."
  []
  [:section.appearance
   [:p.explain [:i18n :theme/explain]]
   [:ui/theme {:ui/labels {:system [:i18n :theme/system]
                           :light [:i18n :theme/light]
                           :dark [:i18n :theme/dark]}}]])

(defn- update-button
  [label]
  [:button.link {:on {:click [[:data/command {:command/kind :update/start}]]}} label])

(defn updates-tab
  "The Updates tab of the settings: the running version, a button that
  looks the newest release up right now (`pchat.update/check-now!`,
  the daily lookup keeps its rhythm), and what the lookup found: the
  newer version with the Update button, the newest already, or that
  the lookup failed."
  [{:keys [current available offered checking checked-at lookup]}]
  [:section.updates
   [:p.explain [:i18n :updates/current {:version (or current "dev")}]]
   [:p
    [:button.link {:on {:click [[:data/command {:command/kind :update/check}]]}
                   :disabled (boolean checking)}
     [:i18n :updates/check]]]
   (cond
     checking [:p.result [:i18n :updates/checking]]
     offered [:p.result
              [:i18n :update/available {:version available}]
              " "
              (update-button [:i18n :update/button])]
     (= :failed lookup) [:p.result.failed [:i18n :updates/lookup-failed]]
     checked-at [:p.result [:i18n :updates/newest]]
     :else nil)])

(def tabs
  "The tabs of the settings, each with the view of the db and the id of
  the chat the tab shows; the first one is the default. More follow
  here."
  [{:id :connections
    :label [:i18n :connections/button]
    :view (fn [db id] (connections-tab (:connections db) id))}
   {:id :appearance
    :label [:i18n :theme/tab]
    :view (fn [_db _id] (appearance-tab))}
   {:id :updates
    :label [:i18n :updates/tab]
    :view (fn [db _id] (updates-tab (:update db)))}])

(defn settings-view
  "In place of the chat: a bar with the way back, the tabs, the one
  chosen. `:settings-tab` in the db names it; `id` is the chat the
  tab shows, for what the settings do with a chat."
  [{:keys [settings-tab] :as db} id]
  (let [current (or settings-tab (:id (first tabs)))]
    (list
     [:header.bar
      [:label.menu {:for "nav" :aria-label [:i18n :page/chats]} "\u2630"]
      [:h1 [:i18n :settings/title]]
      [:button.link.close {:on {:click [[:data/command {:command/kind :settings/close}]]}}
       [:i18n :settings/close]]]
     [:nav.tabs
      (for [{:keys [id label]} tabs]
        [:button.tab {:replicant/key id
                      :class (when (= id current) "current")
                      :on {:click [[:data/command {:command/kind :settings/tab
                                                   :command/data {:tab (clojure.core/name id)}}]]}}
         label])]
     [:div.settings
      (some (fn [{:keys [view] tab-id :id}]
              (when (= tab-id current)
                (view db id)))
            tabs)])))

(defn header
  "The name of the chat is a field: a change renames it. `:task` is the
  title of the chat's task, when it has one. The actions on the chat
  are in its sidebar row (`chat-actions`). On a job's page (`:job`, the
  job) the chat is the job's conversation: the field renames the job."
  [{:keys [id status cost name] :as chat} {:keys [task job]}]
  [:header.bar
   ;; On a phone the sidebar is a drawer; this opens it (see the
   ;; checkbox in `page`).
   [:label.menu {:for "nav" :aria-label [:i18n :page/chats]} "\u2630"]
   [:h1
    (if job
      [:input.title {:replicant/key (str "job-" (:id job))
                     :value (or (:name job) "")
                     :title [:i18n :job/rename-title]
                     :on {:change [[:data/command {:command/kind :job/rename
                                                   :command/data {:id (:id job)
                                                                  :name :event/target.value}}]]}}]
      [:input.title {:replicant/key id
                     :value (or name "")
                     :placeholder (chat-name chat)
                     :title [:i18n :chat/rename-title]
                     :on {:change [[:data/command {:command/kind :chat/rename
                                                   :command/data {:id id
                                                                  :name :event/target.value}}]]}}])]
   (when task
     [:span.task-chip task])
   [:span.status {:class (clojure.core/name status)}
    (case status
      :working [:i18n :chat/working]
      [:i18n :chat/ready])]
   (when (pos? cost)
     [:span.spend (format "$%.2f" cost)])])

(defn composer
  "`:placeholder` in `opts` is the task's, the general one without. The
  uploads go to the chat's own folder."
  [{:keys [id status]} {:keys [key-missing? placeholder]}]
  [:div.composer
   (when key-missing?
     [:p.error [:i18n :composer/key-missing]])
   [:ui/composer {:ui/store-key (draft-key id)
                  :ui/attachments-key (attachments-key id)
                  :ui/upload-url (str (chats/url id) "/upload")
                  :ui/send (send-actions id)
                  :ui/send-label [:i18n :composer/send]
                  :ui/attach-title [:i18n :composer/attach]
                  :ui/remove-title [:i18n :composer/remove]
                  :ui/uploading-label [:i18n :composer/uploading]
                  :ui/failed-label [:i18n :composer/upload-failed]
                  :ui/disabled? (boolean key-missing?)
                  :ui/attach-id attach-input-id
                  :rows 3
                  :placeholder (or placeholder [:i18n :composer/placeholder])}]
   (when (= :working status)
     [:button.stop {:on {:click [[:data/command {:command/kind :chat/abort
                                                 :command/data {:id id}}]]}}
      [:i18n :composer/stop]])])

(defn- languages
  "The languages of the page, the current one marked: a link reloads
  the page with `?lang=`."
  [locale]
  [:nav.languages
   (for [l [:en :de]]
     [:a {:href (str "?lang=" (clojure.core/name l))
          :class (when (= l locale) "current")}
      (str/upper-case (clojure.core/name l))])])

(defn sidebar
  "The rows are links to the chats' pages, `/chat/<id>`: the browser
  runtime (`pchat.client`) follows them without a reload and tells the
  server which chat this tab shows; a new tab or a reload lands on the
  URL. `:id` in `opts` is the chat this tab shows, `:locale` the
  language of the page. The button for a new chat names the tab
  (`:pchat/tabid`, filled in by the runtime), so that the new chat
  opens here."
  ([db]
   (sidebar db {}))
  ([{:keys [chats jobs deleting settings-open update]} {:keys [id locale] :as opts}]
  (let [shown id
        shown-job (get-in opts [:job :job :id])
        ;; The conversations of the jobs are on the jobs' pages.
        chats (sort-by #(or (:updated %) 0) > (remove :job (vals chats)))
        jobs (sort-by #(or (:created %) 0) (vals jobs))]
    [:aside.sidebar
     [:div.brand "pchat"
      ;; The running version, so that a person sees what an update did.
      (when-let [version (:current update)]
        [:span.version {:title "pchat version"} version])]
     [:label.new {:for "nav"
                  :role "button"
                  :on {:click [[:data/command {:command/kind :chat/new
                                               :command/data {:tab :pchat/tabid}}]]}}
      [:i18n :chat/new]]
     ;; The list is keyed by its order: a chat that moves to the top
     ;; makes a new list. Replicant 2026.07.1 records the wrong vdom for
     ;; a row it moves to the front, and the row's next change (the
     ;; working dot going away) is not rendered.
     [:ul.chats {:replicant/key (str/join " " (map :id chats))}
      (for [{:keys [id status] :as chat} chats]
        [:li {:replicant/key id
              :class (str (when (= id shown) "current")
                          (when (= :working status) " working"))}
         (if (= id deleting)
           (delete-confirm id)
           (list
            [:a.row {:href (chats/url id)
                     :data-chat id}
             [:span.name (chat-name chat)]
             (when (= :working status)
               [:span.dot])]
            (chat-actions chat)))])]
     ;; The jobs under the chats: name, state, the next run.
     (when (seq jobs)
       (list
        [:div.jobs-heading [:i18n :job/heading]]
        [:ul.jobs
         (for [{:keys [id name state schedule next tz]} jobs]
           [:li {:replicant/key id
                 :class (when (= id shown-job) "current")}
            [:a.row {:href (jobs/url id)
                     :data-job id}
             [:span.name name]
             [:span.meta
              (jobs-view/state-label state)
              (when (= :active state)
                (list ", "
                      (if (and schedule next)
                        [:i18n :job/next {:at (schedule/run-text (schedule/at-ms next tz) locale)}]
                        [:i18n :job/no-schedule])))]]])]))
     ;; The bottom of the sidebar: the settings, in place of the chat
     ;; when opened, and the languages.
     [:div.bottom
      [:label.settings {:for "nav"
                        :role "button"
                        :class (when settings-open "current")
                        :on {:click [[:data/command {:command/kind :settings/open}]]}}
       [:i18n :settings/button]]
      (languages locale)]])))

(defn views
  "On a phone the chat and the card take turns: two radios, the labels
  are the tabs, the stylesheet shows one of the two. `made` is the
  label of the card."
  [made]
  [:nav.views
   [:label {:for "view-chat"} [:i18n :page/view-chat]]
   [:label {:for "view-made"} made]])

(defn setup-banner
  "Above the chat while the agent's program is not there yet (see
  `pchat.setup`): being set up, or why it could not be."
  [{:keys [pi]}]
  (case (:state pi)
    :installing [:div.banner.setup [:i18n :setup/banner-installing]]
    :failed [:div.banner.setup.failed [:i18n :setup/pi-failed {:lines (str/join " / " (:lines pi))}]]
    :missing [:div.banner.setup.failed [:i18n :setup/pi-missing]]
    nil))

(defn update-banner
  "Above the chat when a newer pchat is there (see `pchat.update`): the
  version and the button, then what the update is doing."
  [{:keys [offered available state error]}]
  (when offered
    (case state
      :downloading [:div.banner.update [:i18n :update/downloading {:version available}]]
      :ready [:div.banner.update [:i18n :update/waiting {:version available}]]
      :failed [:div.banner.update.failed
               [:i18n :update/failed {:error (str error)}]
               " "
               (update-button [:i18n :update/retry])]
      [:div.banner.update
       [:i18n :update/available {:version available}]
       " "
       (update-button [:i18n :update/button])])))

(defn migrate-banner
  "Above the chat when the workspace holds chats from before 0.2.0 (see
  `pchat.migrate`): the offer to bring them back, then what the script
  is doing, how many came back, or why it failed."
  [{:keys [needed? state progress count error]}]
  (let [start (fn [label]
                [:button.link {:on {:click [[:data/command {:command/kind :migrate/start}]]}} label])]
    (case state
      :running [:div.banner.migrate [:i18n :migrate/running {:progress (or progress "")}]]
      :done [:div.banner.migrate
             [:i18n :migrate/done {:count (or count 0)}]
             " "
             [:button.link {:on {:click [[:data/command {:command/kind :migrate/dismiss}]]}}
              [:i18n :migrate/close]]]
      :failed [:div.banner.migrate.failed
               [:i18n :migrate/failed {:error (str error)}]
               " "
               (start [:i18n :migrate/retry])]
      (when needed?
        [:div.banner.migrate
         [:i18n :migrate/found]
         " "
         (start [:i18n :migrate/start])]))))

(defn page
  "`opts`: `:id`, the chat this connection shows; `:note`, a line above
  the chat when it is not the one the URL named; `:key-missing?`;
  `:locale` (the language of the page, see `pchat.i18n`); `:project`
  and `:share` (see `pchat.artifacts/view`). A new chat shows the tasks to pick from first, when
  there are skills. `[:ui/url]` tells the browser the page of the chat
  shown, for the address bar."
  [{:keys [chats deleting settings-open setup update migration] :as db} {:keys [id note locale job] :as opts}]
  (let [chat (get chats id)
        files (:artifacts/list chat)
        skill (skills/by-name (:skills db) (:task chat))
        job? (some? job)
        picking? (and (not job?) (seq (:skills db)) (nil? (:task chat)) (empty? (:items chat)))
        ;; A job's page: the job beside its conversation, in place of the card.
        card? (or job? (seq files))]
    [:div.pchat {:lang (some-> locale name)
                 :data-chat id}
     [:ui/url {:ui/path (if job (jobs/url (get-in job [:job :id])) (chats/url id))}]
     ;; The drawer state on a phone, toggled by labels, never rendered
     ;; with :checked so that the person's choice survives a frame.
     [:input#nav.toggle {:type "checkbox"}]
     (sidebar db opts)
     [:label.backdrop {:for "nav"}]
     (cond
       settings-open
       ;; Keyed apart from the chat: the two share no element.
       [:div.rchat.settings-open {:replicant/key "settings"}
        (setup-banner setup)
        (update-banner update)
        (migrate-banner migration)
        (settings-view db id)]

       :else
       [:div.rchat {:replicant/key (if job? "job" "chat")
                    :class (str (when card? "with-card") (when job? " job-open"))}
        (when card?
          (list
           [:input#view-chat.toggle {:type "radio" :name "view" :default-checked "true"}]
           [:input#view-made.toggle {:type "radio" :name "view"}]))
        (setup-banner setup)
        (update-banner update)
        (migrate-banner migration)
        (when note
          [:div.banner.note note])
        (header chat {:task (some-> skill (skills/text :title locale))
                      :job (:job job)})
        (when card?
          (views (if job? [:i18n :job/view-job] [:i18n :page/view-made])))
        [:div.body
         [:div.chat
          (if picking?
            (task-picker chat (:skills db) locale)
            (list
             (feed chat (some-> skill (intro locale)))
             (composer chat (assoc opts :placeholder (some-> skill (skills/text :placeholder locale))))))]
         (when card?
           [:section.card {:class (when job? "job")}
            (if job?
              (jobs-view/panel job)
              (artifacts/view chat (select-keys opts [:project :share])))])]])]))
