(ns pchat.view
  "The page as a function of the app value: the sidebar with the chats,
  the header, the current chat (feed and composer). Classes follow rchat's
  stylesheet, which pchat ships as its own, plus pchat.css for the sidebar."
  (:require [clojure.string :as str]
            [pchat.artifacts :as artifacts]
            [pchat.chat :as chat]
            [rchat.feed :as feed]
            [rchat.markdown :as markdown]))

(def send-actions
  "Sends the draft and the attached images, then clears both."
  [[:data/command
    {:command/kind :chat/send
     :command/data {:text [:store/deref :pchat/draft]
                    :images [:store/deref :pchat/attachments]}}
    {:on-success [[:store/dissoc :pchat/draft]
                  [:store/dissoc :pchat/attachments]]}]])

(defn- tool-label
  [{:keys [name]}]
  (case name
    "bash" "Ran a command"
    "read" "Looked at a file"
    "write" "Wrote a file"
    "edit" "Changed a file"
    (str "Used " name)))

(defn- tool-detail
  [{:keys [args]}]
  (or (:command args)
      (:path args)
      (:file_path args)
      (pr-str args)))

(defn- tool-view
  [{:keys [done? error? result] :as item}]
  [:details.work {:class (when error? "failed")}
   [:summary (str (tool-label item)
                  (cond
                    (not done?) " …"
                    error? " · did not work"
                    :else ""))]
   [:pre.command (tool-detail item)]
   (when done?
     (let [text (chat/result-text result)]
       (when (seq text)
         [:pre.output (feed/cut 2000 text)])))])

(defn- item-view
  [index {:keys [kind text images error handover?] :as item}]
  (case kind
    :user
    [:div.item.user {:replicant/key index}
     [:div.label "You"]
     (when (seq text)
       [:p.text text])
     (when (seq images)
       [:div.looks
        (for [url images
              :when url]
          [:img {:src url
                 :loading "lazy"}])])]

    :assistant
    (if handover?
      [:div.item.handover {:replicant/key index}
       [:div.label "Ready for you"]
       [:div.text (markdown/children text)]
       (when error
         [:p.error error])]
      [:div.item.step {:replicant/key index}
       [:div.text (markdown/children text)]
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

(defn feed
  [{:keys [status] :as chat}]
  [:section.feed {:data-stick-to-end "true"}
   (->> (with-handover chat)
        (map-indexed vector)
        (filter (fn [[_ item]] (shown? item)))
        (map (fn [[index item]] (item-view index item))))
   (when (= :working status)
     [:div.item.working {:replicant/key "working"}
      [:span.dot]
      [:span "Working"]])])

(defn chat-name
  [{:keys [name items]}]
  (or name
      (some #(when (= :user (:kind %)) (first (str/split-lines (:text %)))) items)
      "New chat"))

(defn header
  [{:keys [status cost] :as chat}]
  [:header.bar
   ;; On a phone the sidebar is a drawer; this opens it (see the
   ;; checkbox in `page`).
   [:label.menu {:for "nav" :aria-label "Chats"} "\u2630"]
   [:h1 (chat-name chat)]
   [:span.status {:class (name status)}
    (case status
      :working "Working"
      "Ready")]
   (when (pos? cost)
     [:span.spend (format "$%.2f" cost)])])

(defn composer
  [{:keys [status]} {:keys [key-missing?]}]
  [:div.composer
   (when key-missing?
     [:p.error "The API key of the model is not set in the environment of the server."])
   [:ui/composer {:ui/store-key :pchat/draft
                  :ui/attachments-key :pchat/attachments
                  :ui/upload-url "/upload"
                  :ui/send send-actions
                  :ui/send-label "Send"
                  :ui/disabled? (boolean key-missing?)
                  :rows 3
                  :placeholder "What should we make?"}]
   (when (= :working status)
     [:button.stop {:on {:click [[:data/command {:command/kind :chat/abort}]]}}
      "Stop"])])

(defn sidebar
  "The rows are labels of the drawer checkbox: a tap on a phone opens
  the chat and closes the drawer. On a desktop the checkbox changes
  nothing."
  [{:keys [chats current]}]
  (let [chats (sort-by #(or (:updated %) 0) > (vals chats))]
    [:aside.sidebar
     [:div.brand "pchat"]
     [:label.new {:for "nav"
                  :role "button"
                  :on {:click [[:data/command {:command/kind :chat/new}]]}}
      "New chat"]
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
            [:span.dot])]])]]))

(defn views
  "On a phone the chat and the card take turns: two radios, the labels
  are the tabs, the stylesheet shows one of the two."
  []
  [:nav.views
   [:label {:for "view-chat"} "Chat"]
   [:label {:for "view-made"} "What we made"]])

(defn page
  "`opts`: `:key-missing?` and `:project` (see `pchat.artifacts/view`)."
  [{:keys [chats current] files :artifacts/list :as db} opts]
  (let [chat (get chats current)
        card? (seq files)]
    [:div.pchat
     ;; The drawer state on a phone, toggled by labels, never rendered
     ;; with :checked so that the person's choice survives a frame.
     [:input#nav.toggle {:type "checkbox"}]
     (sidebar db)
     [:label.backdrop {:for "nav"}]
     [:div.rchat {:class (when card? "with-card")}
      (when card?
        (list
         [:input#view-chat.toggle {:type "radio" :name "view" :default-checked "true"}]
         [:input#view-made.toggle {:type "radio" :name "view"}]))
      (header chat)
      (when card?
        (views))
      [:div.body
       [:div.chat
        (feed chat)
        (composer chat opts)]
       (when card?
         [:section.card (artifacts/view db (select-keys opts [:project]))])]]]))
