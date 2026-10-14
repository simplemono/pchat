(ns pchat.jobs-view
  "The page of a job (see `pchat.jobs`), in place of the chat: the
  prompt to review, the schedule, the endpoint to paste into a service,
  Run now, Pause, Delete, and the runs, each a link to its chat."
  (:require [clojure.string :as str]
            [pchat.chats :as chats]
            [pchat.jobs :as jobs]))

(defn- time-text
  "A moment in UTC, like the cron lines."
  [ms]
  (when ms
    (jobs/minute-text (.atZone (java.time.Instant/ofEpochMilli ms) java.time.ZoneOffset/UTC))))

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
  [runs]
  (if (empty? runs)
    [:p.note [:i18n :job/no-runs]]
    [:table.runs
     [:tbody
      (for [{:keys [id run]} runs]
        [:tr {:replicant/key id}
         [:td [:a {:href (chats/url id) :data-chat id} (time-text (:started run))]]
         [:td [:span.state {:class (name (:state run))} (run-label run)]]
         [:td (when-let [cost (:cost run)]
                (when (pos? cost) (format "$%.2f" cost)))]])]]))

(defn page
  "`opts`: `:job`, `:runs` (the chats of the runs, newest first),
  `:endpoint` (the full address), `:clock`, `:error` (under the schedule
  field), `:deleting`."
  [{:keys [job runs endpoint clock error deleting]}]
  (let [{:keys [id name state schedule next failures caps prompt]} job
        running? (some #(= :running (get-in % [:run :state])) runs)]
    [:div.rchat.job-open {:replicant/key "job"}
     [:header.bar
      [:label.menu {:for "nav" :aria-label [:i18n :page/chats]} "☰"]
      [:h1 name]
      [:span.status {:class (if running? "working" "idle")}
       (state-label state)]]
     [:div.job
      (when (and (= :paused state) (<= jobs/failures-to-pause (or failures 0)))
        [:p.note [:i18n :job/paused-note]])
      [:div.actions
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
       (delete-controls job deleting)
       (when running?
         [:span.note [:i18n :job/queued]])]
      [:h2 [:i18n :job/prompt-heading]]
      [:p.note [:i18n :job/prompt-note]]
      [:textarea.prompt {:replicant/key id
                         :value (or prompt "")
                         :on {:change [[:data/command {:command/kind :job/prompt
                                                       :command/data {:id id
                                                                      :text :event/target.value}}]]}}]
      [:h2 [:i18n :job/schedule-heading]]
      [:p.note [:i18n :job/schedule-note]]
      [:input.line {:replicant/key (str id "-schedule")
                    :value (or schedule "")
                    :placeholder "0 9 * * 1-5"
                    :on {:change [[:data/command {:command/kind :job/schedule
                                                  :command/data {:id id
                                                                 :cron :event/target.value}}]]}}]
      (when error
        [:p.error error])
      (when (and schedule next)
        [:p.note [:i18n :job/next {:at next}]])
      (when (and schedule (= :external clock))
        [:p.note [:i18n :job/external-clock]])
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
      (runs-table runs)]]))
