(ns pchat.view
  "The page as a function of the app value: the sidebar with the chats,
  the header, the current chat (feed and composer). Classes follow rchat's
  stylesheet, which pchat ships as its own, plus pchat.css for the sidebar.
  The texts are m1p reference tuples, `[:i18n :chat/new]`, that
  `pchat.i18n/translate` turns into the language of the person."
  (:require [clojure.string :as str]
            [pchat.artifacts :as artifacts]
            [pchat.chat :as chat]
            [pchat.genui :as genui]
            [pchat.skills :as skills]
            [rchat.feed :as feed]))

(def send-actions
  "Sends the draft and the attachments, images and clips, then clears
  both."
  [[:data/command
    {:command/kind :chat/send
     :command/data {:text [:store/deref :pchat/draft]
                    :images [:store/deref :pchat/attachments]}}
    {:on-success [[:store/dissoc :pchat/draft]
                  [:store/dissoc :pchat/attachments]]}]])

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
  [index {:keys [kind text images media error handover?] :as item}]
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
        (for [{:keys [kind url]} media]
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
       [:div.text (genui/children text)]
       (when error
         [:p.error error])]
      [:div.item.step {:replicant/key index}
       [:div.text (genui/children text)]
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

(defn- clock
  "15:49 on 11 Oct, in the time of the server."
  [ms]
  (.format (java.time.format.DateTimeFormatter/ofPattern "HH:mm, d MMM")
           (java.time.LocalDateTime/ofInstant (java.time.Instant/ofEpochMilli ms)
                                              (java.time.ZoneId/systemDefault))))

(defn- run-view
  "A run of the webhook: when, how long, what it printed, what it made."
  [{:keys [n at exit seconds output made]}]
  [:div.item.step.run {:replicant/key (str "run-" n)}
   [:details.work {:class (when-not (zero? exit) "failed")}
    [:summary [:i18n :hook/ran] " · " (clock at) " · " (str seconds " s")
     (when-not (zero? exit) [:i18n :tool/failed])]
    (when (seq output)
      [:pre.output (feed/cut 2000 output)])
    (when (seq made)
      [:p.made
       (for [path made]
         [:button.link {:on {:click [[:data/command {:command/kind :artifacts/open
                                                     :command/data {:path path}}]]}}
          (artifacts/title path)])])]])

(def shown-runs
  "The last runs of the webhook under the chat."
  10)

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
  ([{:keys [status runs] :as chat} intro]
   [:section.feed {:data-stick-to-end "true"}
    intro
    (->> (with-handover chat)
         (map-indexed vector)
         (filter (fn [[_ item]] (shown? item)))
         (map (fn [[index item]] (item-view index item))))
    (map run-view (take-last shown-runs runs))
    (when (= :working status)
      [:div.item.working {:replicant/key "working"}
       [:span.dot]
       [:span [:i18n :chat/working]]])]))

(defn chat-name
  [{:keys [name items]}]
  (or name
      (some #(when (= :user (:kind %)) (first (str/split-lines (:text %)))) items)
      [:i18n :chat/untitled]))

(defn- delete-controls
  "Delete asks once: the first click arms it, the second deletes."
  [{:keys [id]} deleting]
  (if (= id deleting)
    [:span.confirm
     [:i18n :chat/delete-sure]
     [:button.link.danger {:on {:click [[:data/command {:command/kind :chat/delete
                                                        :command/data {:id id}}]]}}
      [:i18n :chat/delete-yes]]
     [:button.link {:on {:click [[:data/command {:command/kind :chat/keep}]]}}
      [:i18n :chat/keep]]]
    [:button.link.delete {:on {:click [[:data/command {:command/kind :chat/delete
                                                       :command/data {:id id}}]]}}
     [:i18n :chat/delete]]))

(defn- hook-button
  [{:keys [id hook]} hook-open]
  [:button.link.hook {:class (str (when hook "on") (when (= id hook-open) " open"))
                      :on {:click [[:data/command {:command/kind :hook/toggle
                                                   :command/data {:id id}}]]}}
   [:i18n :hook/button]])

(defn hook-panel
  "Under the header when opened: the address of the webhook to paste
  into the other service, how it went so far, on and off."
  [{:keys [id hook runs] :as chat} {:keys [hook-url]}]
  [:div.hook-panel
   (if hook
     (list
      [:input.hook-url {:replicant/key (:secret hook)
                        :value (when hook-url (hook-url chat))
                        :readonly true
                        :title [:i18n :hook/url-title]}]
      [:span.runs (if (seq runs)
                    [:i18n :hook/runs {:count (count runs)}]
                    [:i18n :hook/none])]
      [:button.link {:on {:click [[:data/command {:command/kind :hook/remove
                                                  :command/data {:id id}}]]}}
       [:i18n :hook/remove]])
     (list
      [:span.explain [:i18n :hook/explain]]
      [:button.link {:on {:click [[:data/command {:command/kind :hook/create
                                                  :command/data {:id id}}]]}}
       [:i18n :hook/create]]))])

(defn- connection-row
  "One connection: what it is, how its last test went, and what can be
  done with it. A secret is named, never shown."
  [{:keys [name address enabled? secrets tools error testing?]}]
  [:div.connection {:replicant/key name
                    :class (when-not enabled? "off")}
   [:div.about
    [:span.name name]
    [:span.address {:title address} address]
    (for [secret secrets]
      [:span.secret (str secret ": ••••")])]
   [:span.state {:class (when error "error")
                 :title (when (seq tools) (str/join ", " tools))}
    (cond
      testing? [:i18n :connections/testing]
      (not enabled?) [:i18n :connections/off]
      error error
      tools [:i18n :connections/tools {:count (count tools)}]
      :else [:i18n :connections/untested])]
   [:div.actions
    [:button.link {:on {:click [[:data/command {:command/kind :connections/test
                                                :command/data {:name name}}]]}}
     [:i18n :connections/test]]
    [:button.link {:on {:click [[:data/command {:command/kind :connections/enable
                                                :command/data {:name name
                                                               :enabled? (not enabled?)}}]]}}
     (if enabled? [:i18n :connections/turn-off] [:i18n :connections/turn-on])]
    [:button.link.danger {:on {:click [[:data/command {:command/kind :connections/remove
                                                       :command/data {:name name}}]]}}
     [:i18n :connections/remove]]]])

(defn- field
  "A labelled field of a form, with a hint under it when there is one."
  [label input & [hint]]
  [:label.field
   [:span.label label]
   input
   (when hint
     [:small.hint hint])])

(defn- connection-form
  "Name, address or program, an access line, when the agent sees the
  tools; keyed by the number of connections, so that a new one empties
  the fields."
  [connections]
  [:div.box.add {:replicant/key (str "add-" (count connections))}
   [:h2 [:i18n :connections/add-title]]
   (field [:i18n :connections/name]
          [:input {:placeholder [:i18n :connections/name-placeholder]
                   :on {:input [[:store/assoc :pchat/connection-name :event/target.value]]}}])
   (field [:i18n :connections/address]
          [:input {:placeholder [:i18n :connections/address-placeholder]
                   :on {:input [[:store/assoc :pchat/connection-address :event/target.value]]}}])
   (field [:i18n :connections/secret]
          [:input {:placeholder [:i18n :connections/secret-placeholder]
                   :on {:input [[:store/assoc :pchat/connection-secret :event/target.value]]}}]
          [:i18n :connections/secret-hint])
   (field [:i18n :connections/exposure]
          [:select {:on {:change [[:store/assoc :pchat/connection-exposure :event/target.value]]}}
           [:option {:value "deferred" :selected true} [:i18n :connections/exposure-deferred]]
           [:option {:value "direct"} [:i18n :connections/exposure-direct]]])
   [:button.primary {:on {:click [[:data/command {:command/kind :connections/add
                                                  :command/data {:name [:store/deref :pchat/connection-name]
                                                                 :address [:store/deref :pchat/connection-address]
                                                                 :secret [:store/deref :pchat/connection-secret]
                                                                 :exposure [:store/deref :pchat/connection-exposure]}}
                                   {:on-success [[:store/dissoc :pchat/connection-name]
                                                 [:store/dissoc :pchat/connection-address]
                                                 [:store/dissoc :pchat/connection-secret]]}]]}}
    [:i18n :connections/add]]])

(defn- agent-form
  "The service named, the agent finds out the rest."
  []
  [:div.box.agent
   [:h2 [:i18n :connections/agent-title]]
   [:p [:i18n :connections/agent-explain]]
   [:div.row
    [:input {:placeholder [:i18n :connections/agent-placeholder]
             :aria-label [:i18n :connections/agent-placeholder]
             :on {:input [[:store/assoc :pchat/connection-service :event/target.value]]}}]
    [:button {:on {:click [[:data/command {:command/kind :connections/agent
                                           :command/data {:service [:store/deref :pchat/connection-service]}}
                            {:on-success [[:store/dissoc :pchat/connection-service]]}]]}}
     [:i18n :connections/agent]]]])

(defn connections-tab
  "The Connections tab of the settings: what a connection is, the
  connections one row each, the form for a new one, and the way to let
  the agent do it."
  [connections]
  [:section.connections
   [:p.explain [:i18n :connections/explain]]
   [:div.list
    (if (seq connections)
      (map connection-row (vals connections))
      [:p.none [:i18n :connections/none]])]
   (when (seq connections)
     [:p.note [:i18n :connections/next-message]])
   (connection-form connections)
   (agent-form)])

(def tabs
  "The tabs of the settings, each with the view of the db it shows; the
  first one is the default. More follow here."
  [{:id :connections
    :label [:i18n :connections/button]
    :view (fn [db] (connections-tab (:connections db)))}])

(defn settings-view
  "In place of the chat: a bar with the way back, the tabs, the one
  chosen. `:settings-tab` in the db names it."
  [{:keys [settings-tab] :as db}]
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
      (some (fn [{:keys [id view]}]
              (when (= id current)
                (view db)))
            tabs)])))

(defn header
  "The name of the chat is a field: a change renames it. `:task` is the
  title of the chat's task, when it has one."
  [{:keys [id status cost name] :as chat} {:keys [deleting hook-open task]}]
  [:header.bar
   ;; On a phone the sidebar is a drawer; this opens it (see the
   ;; checkbox in `page`).
   [:label.menu {:for "nav" :aria-label [:i18n :page/chats]} "\u2630"]
   [:h1
    [:input.title {:replicant/key id
                   :value (or name "")
                   :placeholder (chat-name chat)
                   :title [:i18n :chat/rename-title]
                   :on {:change [[:data/command {:command/kind :chat/rename
                                                 :command/data {:id id
                                                                :name :event/target.value}}]]}}]]
   (when task
     [:span.task-chip task])
   [:span.status {:class (clojure.core/name status)}
    (case status
      :working [:i18n :chat/working]
      [:i18n :chat/ready])]
   (when (pos? cost)
     [:span.spend (format "$%.2f" cost)])
   (hook-button chat hook-open)
   (delete-controls chat deleting)])

(defn composer
  "`:placeholder` in `opts` is the task's, the general one without."
  [{:keys [status]} {:keys [key-missing? placeholder]}]
  [:div.composer
   (when key-missing?
     [:p.error [:i18n :composer/key-missing]])
   [:ui/composer {:ui/store-key :pchat/draft
                  :ui/attachments-key :pchat/attachments
                  :ui/upload-url "/upload"
                  :ui/send send-actions
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
     [:button.stop {:on {:click [[:data/command {:command/kind :chat/abort}]]}}
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
  "The rows are labels of the drawer checkbox: a tap on a phone opens
  the chat and closes the drawer. On a desktop the checkbox changes
  nothing. `:locale` in `opts` marks the language of the page."
  ([db]
   (sidebar db {}))
  ([{:keys [chats current settings-open]} {:keys [locale]}]
  (let [chats (sort-by #(or (:updated %) 0) > (vals chats))]
    [:aside.sidebar
     [:div.brand "pchat"]
     [:label.new {:for "nav"
                  :role "button"
                  :on {:click [[:data/command {:command/kind :chat/new}]]}}
      [:i18n :chat/new]]
     ;; The list is keyed by its order: a chat that moves to the top
     ;; makes a new list. Replicant 2026.07.1 records the wrong vdom for
     ;; a row it moves to the front, and the row's next change (the
     ;; working dot going away) is not rendered.
     [:ul.chats {:replicant/key (str/join " " (map :id chats))}
      (for [{:keys [id status] :as chat} chats]
        [:li {:replicant/key id
              :class (str (when (= id current) "current")
                          (when (= :working status) " working"))}
         [:label.row {:for "nav"
                      :on {:click [[:data/command {:command/kind :chat/open
                                                   :command/data {:id id}}]]}}
          [:span.name (chat-name chat)]
          (when (= :working status)
            [:span.dot])]])]
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
  are the tabs, the stylesheet shows one of the two."
  []
  [:nav.views
   [:label {:for "view-chat"} [:i18n :page/view-chat]]
   [:label {:for "view-made"} [:i18n :page/view-made]]])

(defn setup-banner
  "Above the chat while the agent's program is not there yet (see
  `pchat.setup`): being set up, or why it could not be."
  [{:keys [pi]}]
  (case (:state pi)
    :installing [:div.banner.setup [:i18n :setup/banner-installing]]
    :failed [:div.banner.setup.failed [:i18n :setup/pi-failed {:lines (str/join " / " (:lines pi))}]]
    :missing [:div.banner.setup.failed [:i18n :setup/pi-missing]]
    nil))

(defn page
  "`opts`: `:key-missing?`, `:locale` (the language of the page, see
  `pchat.i18n`) and `:project` (see `pchat.artifacts/view`). A new chat
  shows the tasks to pick from first, when there are skills."
  [{:keys [chats current deleting hook-open settings-open setup] files :artifacts/list :as db} {:keys [locale] :as opts}]
  (let [chat (get chats current)
        skill (skills/by-name (:skills db) (:task chat))
        picking? (and (seq (:skills db)) (nil? (:task chat)) (empty? (:items chat)))
        card? (seq files)]
    [:div.pchat {:lang (some-> locale name)}
     ;; The drawer state on a phone, toggled by labels, never rendered
     ;; with :checked so that the person's choice survives a frame.
     [:input#nav.toggle {:type "checkbox"}]
     (sidebar db opts)
     [:label.backdrop {:for "nav"}]
     (if settings-open
       ;; Keyed apart from the chat: the two share no element.
       [:div.rchat.settings-open {:replicant/key "settings"}
        (setup-banner setup)
        (settings-view db)]
       [:div.rchat {:replicant/key "chat"
                    :class (when card? "with-card")}
        (when card?
          (list
           [:input#view-chat.toggle {:type "radio" :name "view" :default-checked "true"}]
           [:input#view-made.toggle {:type "radio" :name "view"}]))
        (setup-banner setup)
        (header chat {:deleting deleting
                      :hook-open hook-open
                      :task (some-> skill (skills/text :title locale))})
        (when (= current hook-open)
          (hook-panel chat opts))
        (when card?
          (views))
        [:div.body
         [:div.chat
          (if picking?
            (task-picker chat (:skills db) locale)
            (list
             (feed chat (some-> skill (intro locale)))
             (composer chat (assoc opts :placeholder (some-> skill (skills/text :placeholder locale))))))]
         (when card?
           [:section.card (artifacts/view db (select-keys opts [:project :share]))])]])]))
