(ns pchat.genui
  "Hiccup the agent writes into an answer, shown as a part of the page: a
  list of pieces of music with a player each, a choice as buttons, a
  picture. A ```hiccup block in the markdown holds EDN hiccup,

      ```hiccup
      [:div [:p \"Which one?\"]
       [:audio {:src \"/artifacts/card/music.mp3\" :controls true}]
       [:button {:send \"The first one\"} \"Take the first\"]]
      ```

  and `children` renders it between the markdown, the way json-render
  does with JSON. The agent writes data, never markup: only the tags and
  attributes in the allow-lists get through, a link or a file points at
  the chat's own files or at https, `:send` on a button sends its text
  as the person's next message, `:open` shows an artifact. The agent
  names its files as it sees them, `/artifacts/card/music.mp3`, and the
  page serves them under the chat, `/chat/<id>/artifacts/...`: `opts`
  of `children`, `block` and `sanitize` carry the `:id` of the chat. A
  block that does not parse is shown as code with what went wrong."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [rchat.markdown :as markdown]))

(def tags
  #{:div :span :p :ul :ol :li :strong :em :b :i :s :small :h1 :h2 :h3 :h4 :a
    :img :audio :video :button :table :thead :tbody :tr :th :td :figure
    :figcaption :code :pre :br :hr :blockquote :label})

(def attributes
  #{:class :href :src :poster :title :alt :controls :preload :loop :muted
    :width :height :colspan :rowspan :download :style :send :open})

(def urls
  "Where a link, a file or a picture may point, besides the chat's own
  files: the web over https."
  ["https://" "mailto:"])

(defn- url
  "The URL when it is allowed: the chat's artifacts and uploads, written
  as the agent sees them (`artifacts/x`, `/artifacts/x`, `uploads/x`,
  `/looks/x` from earlier builds), served under `/chat/<id>/`; the web
  over https. Nil otherwise."
  [{:keys [id]} value]
  (when (string? value)
    (cond
      (re-find #"^/?artifacts/" value)
      (str "/chat/" id "/artifacts/" (str/replace value #"^/?artifacts/" ""))

      (re-find #"^/?(uploads|looks)/" value)
      (str "/chat/" id "/uploads/" (str/replace value #"^/?(uploads|looks)/" ""))

      (some #(str/starts-with? value %) urls)
      value)))

(defn- base-tag
  "`:div.card` is a div."
  [tag]
  (keyword (first (str/split (name tag) #"[.#]"))))

(defn- style
  [value]
  (when (map? value)
    (into {}
          (for [[k v] value
                :when (and (or (keyword? k) (string? k))
                           (or (string? v) (number? v)))]
            [k v]))))

(defn- send-actions
  [{:keys [id]} text]
  {:click [[:data/command {:command/kind :chat/send
                           :command/data {:id id
                                          :text (str text)}}]]})

(defn- open-actions
  [{:keys [id]} path]
  {:click [[:data/command {:command/kind :artifacts/open
                           :command/data {:id id
                                          :path (str/replace (str path) #"^/?artifacts/" "")}}]]})

(defn- attrs
  "The attributes that are allowed, the ones with a URL checked, `:send`
  and `:open` turned into the commands behind a click."
  [opts tag m]
  (reduce (fn [acc [k v]]
            (cond
              (not (contains? attributes k)) acc
              (#{:href :src :poster} k) (if-let [v (url opts v)]
                                          (cond-> (assoc acc k v)
                                            (and (= :href k) (= :a tag)) (assoc :target "_blank" :rel "noopener"))
                                          acc)
              (= :style k) (if-let [v (style v)] (assoc acc :style v) acc)
              (= :send k) (assoc acc :on (send-actions opts v))
              (= :open k) (assoc acc :on (open-actions opts v))
              (or (string? v) (number? v) (boolean? v)) (assoc acc k v)
              :else acc))
          {}
          m))

(defn sanitize
  "The hiccup with only what the allow-lists know, its files under the
  chat `:id` of `opts`."
  [hiccup opts]
  (cond
    (and (vector? hiccup) (keyword? (first hiccup)))
    (let [[tag & body] hiccup
          base (base-tag tag)
          tag (if (contains? tags base) tag :span)
          [m body] (if (map? (first body)) [(first body) (rest body)] [{} body])]
      (into [tag (attrs opts base m)] (map #(sanitize % opts) body)))

    (sequential? hiccup) (map #(sanitize % opts) hiccup)
    (or (string? hiccup) (number? hiccup) (nil? hiccup)) hiccup
    (keyword? hiccup) (name hiccup)
    :else (pr-str hiccup)))

(defn parse
  "{:hiccup ...} or {:error \"...\"}."
  [text]
  (try
    (let [value (edn/read-string {:default (fn [_ value] value)} (str text))]
      (if (and (vector? value) (keyword? (first value)))
        {:hiccup value}
        {:error "not a hiccup vector, [:tag ...]"}))
    (catch Exception e
      {:error (ex-message e)})))

(defn block
  "One block as a part of the page, or as code with what went wrong."
  [text opts]
  (let [{:keys [hiccup error]} (parse text)]
    (if error
      [:div.genui.genui-error
       [:p.error [:i18n :genui/rejected {:error error}]]
       [:pre [:code text]]]
      [:div.genui (sanitize hiccup opts)])))

(def ^:private fence
  #"(?s)```hiccup[ \t]*\n(.*?)```")

(defn children
  "The markdown `text` with its ```hiccup blocks, as hiccup children. A
  block still being written renders as code until its fence closes.
  `opts` carries the `:id` of the chat the text belongs to."
  [text opts]
  (let [text (str text)
        matcher (re-matcher fence text)]
    (loop [from 0
           out []]
      (if (.find matcher)
        (recur (.end matcher)
               (-> out
                   (into (markdown/children (subs text from (.start matcher))))
                   (conj (block (.group matcher 1) opts))))
        (or (seq (into out (markdown/children (subs text from))))
            ())))))

(comment
  (children "Two pieces:\n\n```hiccup\n[:ul [:li [:audio {:src \"artifacts/card/a.mp3\" :controls true}] [:button {:send \"Take a\"} \"Take this one\"]]]\n```\nWhich?" {:id "c"})
  (block "[:div {:onclick \"x\"} [:script \"alert(1)\"]]" {:id "c"})
  )
