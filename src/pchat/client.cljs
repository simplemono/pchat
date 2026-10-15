(ns pchat.client
  "The browser bundle: the rframes runtime with rchat's composer and
  canvas aliases, and pchat's own composer over rchat's: it takes videos
  and audio files as well as images. An image goes the way rchat sends
  it, scaled and in one request; a clip goes up in pieces of
  `piece-bytes`, with its progress on its chip, and the send button waits
  until every clip is in (`pchat.uploads` on the server).

  The chat a tab shows is in its URL, `/chat/<id>`: a click on a chat in
  the sidebar changes the URL without a reload (`pushState`) and tells
  the server which chat this tab shows now (`:chat/show` with the tab's
  id); the back button does the same for the URL it returns to; the
  `:ui/url` alias of the page corrects the address bar when the server
  showed another chat than the URL named."
  (:require [clojure.string :as str]
            [cognitect.transit :as t]
            [rchat.client :as rchat]
            [rframes.client :as rframes]
            [rframes.client.state :as state]
            [rframes.client.ui :as ui]))

(def max-side
  "Pixels on the long side of an uploaded image."
  1600)

(def piece-bytes
  "The size of one request of a clip upload."
  (* 8 1024 1024))

(def clip-types
  "The media type of a clip by its extension, for a browser that names
  none (an .mkv, a .flac) and for the type the server knows."
  {"mp4" "video/mp4"
   "m4v" "video/mp4"
   "mov" "video/quicktime"
   "webm" "video/webm"
   "mkv" "video/x-matroska"
   "mp3" "audio/mpeg"
   "wav" "audio/wav"
   "m4a" "audio/mp4"
   "aac" "audio/aac"
   "ogg" "audio/ogg"
   "oga" "audio/ogg"
   "flac" "audio/flac"})

(defn- coarse-pointer?
  []
  (boolean (some-> (js/window.matchMedia "(pointer: coarse)")
                   (.-matches))))

(defn- image-file?
  [^js file]
  (and file (str/starts-with? (str (.-type file)) "image/")))

(defn- extension
  [^js file]
  (str/lower-case (or (second (re-find #"\.([^./]+)$" (str (.-name file)))) "")))

(defn- clip-type
  "The media type to send a clip with, or nil when the file is no clip."
  [^js file]
  (let [type (str (.-type file))]
    (or (get clip-types (extension file))
        (when (re-find #"^(video|audio)/" type)
          type))))

(defn- scaled
  "Calls `callback` with the image as a blob of at most `max-side` pixels on
  its long side: the file itself when it is small enough, else a scaled
  copy from a canvas, PNG for a PNG and JPEG for the rest."
  [^js file callback]
  (let [url (js/URL.createObjectURL file)
        image (js/Image.)]
    (set! (.-onload image)
          (fn [_]
            (js/URL.revokeObjectURL url)
            (let [w (.-naturalWidth image)
                  h (.-naturalHeight image)
                  scale (min 1 (/ max-side (max w h 1)))]
              (if (= 1 scale)
                (callback file)
                (let [canvas (js/document.createElement "canvas")
                      cw (js/Math.round (* w scale))
                      ch (js/Math.round (* h scale))
                      png? (= "image/png" (.-type file))]
                  (set! (.-width canvas) cw)
                  (set! (.-height canvas) ch)
                  (.drawImage (.getContext canvas "2d") image 0 0 cw ch)
                  (.toBlob canvas
                           (fn [blob]
                             (callback (or blob file)))
                           (if png? "image/png" "image/jpeg")
                           0.85))))))
    (set! (.-onerror image)
          (fn [_]
            (js/URL.revokeObjectURL url)
            (js/console.error "[upload] not an image the browser can read")))
    (set! (.-src image) url)))

(defn- read-answer
  [text]
  (t/read (t/reader :json) text))

(defn- upload-image!
  "Uploads the image and adds `{:name :url}` of the stored file to the
  attachments in the store."
  [store attachments-key upload-url ^js file]
  (scaled file
          (fn [^js blob]
            (-> (js/fetch upload-url #js {:method "POST"
                                          :headers #js {"Content-Type" (.-type blob)}
                                          :body blob})
                (.then (fn [^js response]
                         (if (.-ok response)
                           (.text response)
                           (throw (js/Error. (str "upload failed with " (.-status response)))))))
                (.then (fn [text]
                         (let [{:keys [name url]} (read-answer text)]
                           (swap! store update attachments-key
                                  (fn [attachments]
                                    (if (some #(= name (:name %)) attachments)
                                      attachments
                                      (conj (vec attachments) {:name name
                                                               :url url})))))))
                (.catch (fn [error]
                          (js/console.error "[upload]" error)))))))

(defn- random-id
  "32 hex digits, the name of a clip while it goes up."
  []
  (let [bytes (js/Uint8Array. 16)]
    (js/crypto.getRandomValues bytes)
    (apply str (map #(.padStart (.toString % 16) 2 "0") (array-seq bytes)))))

(defn- send-piece!
  "POSTs one piece with an XMLHttpRequest, which reports the progress of
  its upload: `on-progress` with the bytes of the piece sent so far,
  `on-answer` with the server's answer, `on-error` with the status."
  [url ^js blob type {:keys [on-progress on-answer on-error]}]
  (let [xhr (js/XMLHttpRequest.)]
    (.open xhr "POST" url)
    (.setRequestHeader xhr "Content-Type" type)
    (set! (.. xhr -upload -onprogress) (fn [^js event]
                                         (on-progress (.-loaded event))))
    (set! (.-onload xhr) (fn [_]
                           (if (<= 200 (.-status xhr) 299)
                             (on-answer (read-answer (.-responseText xhr)))
                             (on-error (.-status xhr)))))
    (set! (.-onerror xhr) (fn [_]
                            (on-error 0)))
    (.send xhr blob)))

(defn- upload-clip!
  "Uploads a video or an audio file in pieces. Its chip is in the
  attachments from the start, `:uploading?` with its `:progress`; the last
  answer turns it into `{:name :url :kind :size}` of the stored file. A
  chip the person removes stops the upload after the piece under way."
  [store attachments-key upload-url ^js file]
  (let [type (clip-type file)
        id (random-id)
        total (.-size file)
        chip (str "uploading-" id)
        present? (fn []
                   (some #(= chip (:chip %)) (get @store attachments-key)))
        update-chip! (fn [f]
                       (swap! store update attachments-key
                              (fn [attachments]
                                (mapv #(if (= chip (:chip %)) (f %) %) attachments))))]
    (swap! store update attachments-key
           (fn [attachments]
             (conj (vec attachments) {:name chip
                                      :chip chip
                                      :kind (if (str/starts-with? type "audio/") :audio :video)
                                      :file-name (.-name file)
                                      :size total
                                      :uploading? true
                                      :progress 0})))
    ((fn step [offset]
       (let [end (min total (+ offset piece-bytes))]
         (send-piece! (str upload-url "?id=" id "&offset=" offset "&total=" total)
                      (.slice file offset end)
                      type
                      {:on-progress (fn [loaded]
                                      (update-chip! #(assoc % :progress (/ (+ offset loaded) (max total 1)))))
                       :on-answer (fn [{:keys [url] :as answer}]
                                    (cond
                                      url (update-chip! #(-> %
                                                             (merge (select-keys answer [:name :url :kind :size]))
                                                             (dissoc :uploading? :progress)))
                                      (present?) (step end)))
                       :on-error (fn [status]
                                   (js/console.error "[upload] a piece failed with" status)
                                   (update-chip! #(-> %
                                                      (dissoc :uploading? :progress)
                                                      (assoc :failed? true))))})))
     0)))

(defn- upload-all!
  [store attachments-key upload-url files]
  (doseq [^js file files]
    (cond
      (clip-type file) (upload-clip! store attachments-key upload-url file)
      (image-file? file) (upload-image! store attachments-key upload-url file))))

(defn- pasted-files
  "The images and clips of a paste."
  [^js event]
  (->> (array-seq (or (some-> event .-clipboardData .-items) #js []))
       (filter #(= "file" (.-kind ^js %)))
       (map #(.getAsFile ^js %))
       (remove nil?)
       (filter #(or (image-file? %) (clip-type %)))))

(defn- short-name
  "A file name of at most 28 characters, the end kept."
  [file-name]
  (let [file-name (str file-name)]
    (if (< 28 (count file-name))
      (str (subs file-name 0 16) "…" (subs file-name (- (count file-name) 10)))
      file-name)))

(defn- megabytes
  [size]
  (let [mb (/ size 1024 1024)]
    (str (if (< mb 10) (.toFixed mb 1) (js/Math.round mb)) " MB")))

(defn- clip-chip
  [{:keys [kind file-name size uploading? progress failed?]} uploading-label failed-label]
  [:span.clip
   [:span.icon (if (= :audio kind) "🎵" "🎬")]
   [:span.file (short-name file-name)]
   [:small (megabytes size)
    (cond
      uploading? (str " · " (or uploading-label "Uploading") " "
                      (js/Math.round (* 100 progress)) " %")
      failed? (str " · " (or failed-label "The upload failed")))]])

(defn- attachments-view
  [store attachments-key attachments {:keys [remove-title uploading-label failed-label]}]
  (when (seq attachments)
    [:div.attachments
     (for [{:keys [name url kind failed?] :as attachment} attachments]
       [:span.attachment {:replicant/key name
                          :class (when failed? "failed")}
        (if kind
          (clip-chip attachment uploading-label failed-label)
          [:img {:src url
                 :alt "attached image"}])
        [:button.remove {:type "button"
                         :title (or remove-title "Remove")
                         :on {:click (fn [_event]
                                       (swap! store update attachments-key
                                              (fn [attachments]
                                                (vec (remove #(= name (:name %)) attachments)))))}}
         "×"]])]))

(defn composer
  "rchat's composer, with clips: the same attributes, plus
  `:ui/uploading-label` and `:ui/failed-label` for the chip of a clip and
  `:ui/attach-id`, the id of the file input, for a label elsewhere on the
  page that opens the file picker."
  [{:keys [ui/store-key ui/attachments-key ui/upload-url ui/send ui/send-label
           ui/attach-title ui/remove-title ui/uploading-label ui/failed-label
           ui/disabled? ui/state ui/attach-id]
    :as attrs}
   _children]
  (let [store state/store
        attachments-key (or attachments-key :rchat/attachments)
        upload-url (or upload-url "/upload")
        draft (str (get state store-key ""))
        attachments (get state attachments-key [])
        uploading? (some :uploading? attachments)
        ready? (and (not disabled?)
                    (not uploading?)
                    (or (not (str/blank? draft))
                        (some #(not (:failed? %)) attachments))
                    (not (ui/kind-issued? state :agent/send)))
        send! (fn []
                (when ready?
                  (state/dispatch! send)))]
    [:div.composer-input
     (attachments-view store attachments-key attachments {:remove-title remove-title
                                                          :uploading-label uploading-label
                                                          :failed-label failed-label})
     [:div.row
      [:textarea
       (-> (dissoc attrs :ui/store-key :ui/attachments-key :ui/upload-url :ui/send
                   :ui/send-label :ui/attach-title :ui/remove-title :ui/uploading-label
                   :ui/failed-label :ui/disabled? :ui/state)
           (assoc :value draft
                  :disabled (boolean disabled?)
                  :on {:input [[:store/assoc store-key :event/target.value]]
                       :paste (fn [^js event]
                                (let [files (pasted-files event)]
                                  (when (seq files)
                                    (.preventDefault event)
                                    (upload-all! store attachments-key upload-url files))))
                       :dragover (fn [^js event]
                                   (.preventDefault event))
                       :drop (fn [^js event]
                               (.preventDefault event)
                               (upload-all! store attachments-key upload-url
                                            (array-seq (.. event -dataTransfer -files))))
                       :keydown (fn [^js event]
                                  (when (and (= "Enter" (.-key event))
                                             (not (.-shiftKey event))
                                             (not (.-isComposing event))
                                             (not (coarse-pointer?)))
                                    (.preventDefault event)
                                    (send!)))}))]
      [:label.attach {:title (or attach-title "Attach an image, a video or an audio file")}
       "📎"
       [:input {:type "file"
                :id attach-id
                :accept "image/*,video/*,audio/*,.mkv,.flac,.m4a"
                :multiple true
                :disabled (boolean disabled?)
                :style {:display "none"}
                :on {:change (fn [^js event]
                               (upload-all! store attachments-key upload-url
                                            (array-seq (.. event -target -files)))
                               (set! (.. event -target -value) ""))}}]]
      [:button.primary {:disabled (not ready?)
                        :on {:click (fn [_event]
                                      (send!))}}
       (or send-label "Send")]]]))

(def theme-key
  "Where the browser keeps the person's scheme: `light`, `dark` or
  `system`. The page's head script reads the same key before the first
  paint."
  "pchat-theme")

(def themes
  [:system :light :dark])

(defn stored-theme
  "The scheme the browser keeps, `:light` without one."
  []
  (let [stored (try (js/localStorage.getItem theme-key) (catch :default _ nil))]
    (or (some #{(keyword stored)} themes) :light)))

(defn apply-theme!
  "Puts the scheme on <html> (the stylesheets key on `theme-light`,
  `theme-dark` and `theme-system`) and keeps it in the browser."
  [theme]
  (let [classes (.-classList js/document.documentElement)]
    (doseq [t themes]
      (.remove classes (str "theme-" (name t))))
    (.add classes (str "theme-" (name theme)))
    (try (js/localStorage.setItem theme-key (name theme)) (catch :default _ nil))
    (swap! state/store assoc :pchat/theme theme)))

(defn theme-toggle
  "The Appearance choice of the settings: three buttons, the current one
  marked. `:ui/labels` names them, `:ui/state` (the client state, which
  the runtime puts here) carries the current choice."
  [{:keys [ui/labels ui/state]} _children]
  (let [current (or (:pchat/theme state) (stored-theme))]
    [:div.theme
     (for [theme themes]
       [:button.choice {:replicant/key theme
                        :class (when (= theme current) "current")
                        :aria-pressed (if (= theme current) "true" "false")
                        :on {:click (fn [_event] (apply-theme! theme))}}
        (get labels theme (name theme))])]))

(defn chat-id-of
  "The chat id of a page path, `/chat/<id>`; nil for any other path."
  [path]
  (second (re-matches #"/chat/([0-9a-f-]{36})/?" (str path))))

(defn- show!
  "Tells the server which chat this tab shows now: `id`, or nil for
  whatever the URL names."
  [id]
  (state/dispatch! [[:data/command {:command/kind :chat/show
                                    :command/data {:id id
                                                   :tab state/tabid}}]]))

(defn- close-drawer!
  "On a phone the sidebar is a drawer over a checkbox; a tap on a chat
  closes it."
  []
  (when-let [^js nav (js/document.getElementById "nav")]
    (set! (.-checked nav) false)))

(defn- go!
  "To the chat's page without a reload."
  [id]
  (js/history.pushState nil "" (str "/chat/" id js/location.search))
  (close-drawer!)
  (show! id))

(defn- plain-click?
  "A left click without a modifier: the ones that open in a new tab or
  window stay the browser's."
  [^js event]
  (and (zero? (.-button event))
       (not (or (.-metaKey event) (.-ctrlKey event) (.-shiftKey event) (.-altKey event)))))

(defn- watch-chat-links!
  "A click on a link to a chat (`a[data-chat]`, the sidebar rows) stays
  on the page; the back and forward buttons show the chat of the URL
  they return to."
  []
  (js/document.addEventListener
   "click"
   (fn [^js event]
     (when-let [^js link (some-> (.-target event) (.closest "a[data-chat]"))]
       (when (plain-click? event)
         (.preventDefault event)
         (go! (.getAttribute link "data-chat"))))))
  (js/window.addEventListener
   "popstate"
   (fn [_]
     (show! (chat-id-of js/location.pathname)))))

(defn url-alias
  "`[:ui/url {:ui/path \"/chat/<id>\"}]` in a frame: the page of the chat
  the server shows; when the address bar says another, it is corrected
  in place, no entry in the history. The base of the document follows,
  so that a relative link in an answer, `artifacts/card.html`, is the
  chat's file. Renders nothing."
  [{:keys [ui/path]} _children]
  (when (seq path)
    (when (not= path js/location.pathname)
      (js/history.replaceState nil "" (str path js/location.search)))
    (when-let [^js base (js/document.querySelector "base")]
      (let [href (str path "/")]
        (when (not= href (.getAttribute base "href"))
          (.setAttribute base "href" href)))))
  [:span.url {:hidden true}])

(def register
  "rchat's aliases, then pchat's composer, which replaces rchat's: the
  later registration of a kind wins; the theme choice; the URL of the
  chat; and `:pchat/tabid` in a command, the id of this tab."
  (concat rchat/register
          [{:ui.alias/kind :ui/composer
            :ui.alias/fn composer}
           {:ui.alias/kind :ui/theme
            :ui.alias/fn theme-toggle}
           {:ui.alias/kind :ui/url
            :ui.alias/fn url-alias}
           {:ui.action-enricher/kind :pchat/tabid
            :ui.action-enricher/fn (fn [{:keys [ui.action/x]}]
                                     (when (= :pchat/tabid x)
                                       state/tabid))}]))

(defn init!
  []
  (swap! state/store assoc :pchat/theme (stored-theme))
  (watch-chat-links!)
  ;; The commands go to /command whatever the page's path: rframes
  ;; posts them next to the page, which for a page opened at
  ;; /chat/<id> would be /chat/command, nothing.
  (rframes/init! {:register register
                  :ui/command-endpoint "/command"}))
