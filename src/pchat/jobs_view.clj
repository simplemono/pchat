(ns pchat.jobs-view
  "The job beside its conversation on the job's page (see `pchat.jobs`):
  the state and the actions, when it runs (in the person's words, with
  the next runs in the job's time zone), what it does every time, the
  endpoint to paste into a service, and the runs, each a link to its
  chat."
  (:require [clojure.string :as str]
            [pchat.chats :as chats]
            [pchat.jobs :as jobs]
            [pchat.schedule :as schedule]))

(defn state-label
  [state]
  (case state
    :paused [:i18n :job/state-paused]
    [:i18n :job/state-active]))

(defn- run-label
  [{:keys [state reason]}]
  (case state
    :running [:i18n :job/run-running]
    :done [:i18n :job/run-done]
    :failed [:i18n :job/run-failed]
    :stopped (case reason
               :cost [:i18n :job/run-stopped-cost]
               :time [:i18n :job/run-stopped-time]
               [:i18n :job/run-stopped])
    (str state)))

(defn- delete-controls
  [{:keys [id]} deleting]
  (if (= id deleting)
    [:span.confirm
     [:i18n :job/delete-sure]
     [:button.link.danger {:on {:click [[:data/command {:command/kind :job/delete
                                                        :command/data {:id id}}]]}}
      [:i18n :job/delete-yes]]
     [:button.link {:on {:click [[:data/command {:command/kind :job/keep}]]}}
      [:i18n :job/keep]]]
    [:button.link.delete {:on {:click [[:data/command {:command/kind :job/delete
                                                       :command/data {:id id}}]]}}
     [:i18n :job/delete]]))

(defn- runs-table
  [runs tz locale]
  (if (empty? runs)
    [:p.note [:i18n :job/no-runs]]
    [:table.runs
     [:tbody
      (for [{:keys [id run]} runs]
        [:tr {:replicant/key id}
         [:td [:a {:href (chats/url id) :data-chat id}
               (schedule/run-text (schedule/at-ms (:started run) tz) locale)]]
         [:td [:span.state {:class (name (:state run))} (run-label run)]]
         [:td (when-let [cost (:cost run)]
                (when (pos? cost) (format "$%.2f" cost)))]])]]))

(defn- next-runs-text
  [cron tz locale]
  (str/join ", " (map #(schedule/run-text % locale) (schedule/next-runs cron tz 3))))

(defn- words-command
  [id text]
  [[:data/command {:command/kind :job/words
                   :command/data {:id id
                                  :text text
                                  :tz :pchat/timezone}}]])

(defn- proposal-view
  "What the person's words became: the wait, the line as words with
  the next runs and Save, or why there is no line."
  [{:keys [id]} {:keys [pending error cron]} tz locale]
  (cond
    pending [:p.note [:i18n :job/working-out]]
    error [:p.error (case error
                      :no-key [:i18n :job/no-key]
                      :not-understood [:i18n :job/not-understood]
                      (str error))]
    cron [:div.proposal
          [:p [:i18n :job/understood {:words (or (schedule/describe cron locale) cron)}]
           " "
           [:code.cron cron]]
          [:p.note [:i18n :job/next-runs {:runs (next-runs-text cron tz locale)}]]
          [:div.actions
           [:button.link.save {:on {:click [[:data/command {:command/kind :job/schedule-save
                                                            :command/data {:id id}}]]}}
            [:i18n :job/save]]
           [:button.link {:on {:click [[:data/command {:command/kind :job/schedule-cancel
                                                       :command/data {:id id}}]]}}
            [:i18n :job/cancel]]]]
    :else nil))

(defn- schedule-view
  "When the job runs: the schedule as words with the next runs, the
  field for new words with four quick picks, the proposal, the time
  zone, the way to turn it off."
  [{:keys [id schedule schedule-text] :as job} {:keys [proposal locale]} tz]
  (list
   (if schedule
     (list
      [:p.schedule-now
       (or (schedule/describe schedule locale) schedule)
       " "
       [:code.cron schedule]]
      [:p.note [:i18n :job/next-runs {:runs (next-runs-text schedule tz locale)}]])
     [:p.note [:i18n :job/no-schedule-note]])
   [:p.note [:i18n :job/words-note]]
   [:input.line.words {:replicant/key (str id "-words")
                       :value (or (:text proposal) schedule-text "")
                       :placeholder [:i18n :job/words-placeholder]
                       :on {:change (words-command id :event/target.value)}}]
   [:div.picks
    [:button.link {:on {:click (words-command id [:i18n :job/pick-daily])}} [:i18n :job/pick-daily]]
    [:button.link {:on {:click (words-command id [:i18n :job/pick-weekdays])}} [:i18n :job/pick-weekdays]]
    [:button.link {:on {:click (words-command id [:i18n :job/pick-monday])}} [:i18n :job/pick-monday]]
    [:button.link {:on {:click (words-command id [:i18n :job/pick-monthly])}} [:i18n :job/pick-monthly]]]
   (proposal-view job proposal tz locale)
   [:p.note.zone
    [:i18n :job/zone-label]
    " "
    [:select {:replicant/key (str id "-tz")
              :on {:change [[:data/command {:command/kind :job/tz
                                            :command/data {:id id
                                                           :tz :event/target.value}}]]}}
     ;; Chosen on the option: a value on the select itself is set before
     ;; the options are there and lands on the first.
     (for [zone (if (some #{tz} schedule/zones) schedule/zones (cons tz schedule/zones))]
       [:option {:replicant/key zone :value zone :selected (= zone tz)} zone])]
    (when schedule
      (list
       " "
       [:button.link.off {:on {:click [[:data/command {:command/kind :job/schedule-off
                                                       :command/data {:id id}}]]}}
        [:i18n :job/off]]))]
   (when schedule
     [:p.note [:i18n :job/external-clock]])))

(defn panel
  "`opts`: `:job`, `:runs` (the chats of the runs, newest first),
  `:endpoint` (the full address), `:proposal` (the answer to the
  person's words, see `pchat.schedule`), `:deleting`, `:locale`."
  [{:keys [job runs endpoint deleting locale] :as opts}]
  (let [{:keys [id state failures caps prompt tz]} job
        tz (or (not-empty (str tz)) "UTC")
        running? (some #(= :running (get-in % [:run :state])) runs)]
    [:div.job
     [:div.actions
      [:span.state {:class (if running? "working" "idle")}
       (state-label state)]
      [:button.link {:on {:click [[:data/command {:command/kind :job/run
                                                  :command/data {:id id}}]]}}
       [:i18n :job/run-now]]
      (if (= :paused state)
        [:button.link {:on {:click [[:data/command {:command/kind :job/resume
                                                    :command/data {:id id}}]]}}
         [:i18n :job/resume]]
        [:button.link {:on {:click [[:data/command {:command/kind :job/pause
                                                    :command/data {:id id}}]]}}
         [:i18n :job/pause]])
      (delete-controls job deleting)]
     (when running?
       [:p.note [:i18n :job/queued]])
     (when (and (= :paused state) (<= jobs/failures-to-pause (or failures 0)))
       [:p.note [:i18n :job/paused-note]])
     [:h2 [:i18n :job/when-heading]]
     (schedule-view job opts tz)
     [:h2 [:i18n :job/prompt-heading]]
     [:p.note [:i18n :job/prompt-note]]
     [:textarea.prompt {:replicant/key id
                        :value (or prompt "")
                        :on {:change [[:data/command {:command/kind :job/prompt
                                                      :command/data {:id id
                                                                     :text :event/target.value}}]]}}]
     [:h2 [:i18n :job/endpoint-heading]]
     [:p.note [:i18n :job/endpoint-note]]
     [:div.actions
      [:input.line.endpoint {:replicant/key (str id "-endpoint")
                             :value endpoint
                             :readonly true}]
      [:button.link {:on {:click [[:dom/copy endpoint]]}}
       [:i18n :job/copy]]]
     [:p.note [:i18n :job/caps {:cost (str (:cost-usd caps)) :minutes (str (:minutes caps))}]]
     [:h2 [:i18n :job/runs-heading]]
     (runs-table runs tz locale)]))
