(ns pchat.jobs-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cheshire.core]
            [pchat.chats :as chats]
            [pchat.jobs :as jobs]))

(defn- temp-dir
  []
  (str (fs/create-temp-dir {:prefix "pchat-jobs"})))

(defn- utc
  [y m d hh mm]
  (java.time.ZonedDateTime/of y m d hh mm 0 0 java.time.ZoneOffset/UTC))

(deftest cron-test
  (testing "five fields, stars, lists, ranges and steps"
    (is (= [#{0} #{9} (set (range 1 32)) (set (range 1 13)) #{1 2 3 4 5}]
           (jobs/parse-cron "0 9 * * 1-5")))
    (is (= #{0 15 30 45} (first (jobs/parse-cron "*/15 * * * *"))))
    (is (= #{1 3 5} (first (jobs/parse-cron "1,3,5 * * * *"))))
    (is (= #{10 25 40 55} (first (jobs/parse-cron "10/15 * * * *"))))
    (is (= #{0 6} (nth (jobs/parse-cron "0 0 * * 0,6") 4)))
    (is (= #{0} (nth (jobs/parse-cron "0 0 * * 7") 4)) "7 is Sunday like 0"))
  (testing "what is no cron line"
    (is (nil? (jobs/parse-cron "0 9 * *")))
    (is (nil? (jobs/parse-cron "61 * * * *")))
    (is (nil? (jobs/parse-cron "a b c d e")))
    (is (nil? (jobs/parse-cron "")))
    (is (nil? (jobs/parse-cron "*/0 * * * *"))))
  (testing "due and the next run"
    (is (jobs/due? "30 14 * * *" (utc 2026 10 14 14 30)))
    (is (not (jobs/due? "30 14 * * *" (utc 2026 10 14 14 31))))
    (is (jobs/due? "0 9 * * 1-5" (utc 2026 10 14 9 0)) "a Wednesday")
    (is (not (jobs/due? "0 9 * * 1-5" (utc 2026 10 17 9 0))) "a Saturday")
    (is (= "2026-10-15 09:00Z" (jobs/minute-text (jobs/next-run "0 9 * * 1-5" (utc 2026 10 14 9 0)))))
    (is (= "2026-10-19 09:00Z" (jobs/minute-text (jobs/next-run "0 9 * * 1-5" (utc 2026 10 16 9 0)))) "over the weekend")
    (is (= "2026-10-14 14:45Z" (jobs/minute-text (jobs/next-run "*/15 * * * *" (utc 2026 10 14 14 31)))))
    (is (= "2027-02-28 00:00Z" (jobs/minute-text (jobs/next-run "0 0 28 2 *" (utc 2026 3 1 0 0)))))
    (is (nil? (jobs/next-run "0 0 31 2 *" (utc 2026 3 1 0 0))) "a day that never comes, within a year"))
  (testing "in a zone: the line means the zone's clock, across the change of the clocks"
    (let [berlin (java.time.ZoneId/of "Europe/Berlin")
          at (fn [y m d hh mm] (java.time.ZonedDateTime/of y m d hh mm 0 0 berlin))]
      (is (= "2026-10-24T09:00+02:00[Europe/Berlin]" (str (jobs/next-run "0 9 * * *" (at 2026 10 23 9 0)))))
      (is (= "2026-10-25T09:00+01:00[Europe/Berlin]" (str (jobs/next-run "0 9 * * *" (at 2026 10 24 9 0)))) "the clocks went back, 09:00 stays 09:00")
      (is (= "UTC" (str (jobs/zone {:tz nil}))))
      (is (= "UTC" (str (jobs/zone {:tz "Nowhere/Town"}))))
      (is (= "Europe/Berlin" (str (jobs/zone {:tz "Europe/Berlin"})))))))

(defn- pr-str-json
  [value]
  (cheshire.core/generate-string value))

(defn- chat-folder!
  "A chat folder like `pchat.chats/create!` makes it, with a file in
  its artifacts, an upload, a session and a hook log."
  [workspace id]
  (chats/create! workspace id)
  (let [dir (chats/dir (io/file workspace "chats") id)]
    (spit (io/file dir "artifacts" "card.html") "<h1>card</h1>")
    (spit (io/file dir "uploads" "logo.png") "png")
    (spit (io/file dir ".session" "s.jsonl") "{}")
    (spit (io/file dir "hook.log") "old")
    (spit (io/file dir ".chat.edn") (pr-str {:name "Source"}))
    (spit (io/file dir "notes.md") "notes")
    dir))

(defn- handle!
  "A handle over a workspace with stubs: the messages sent and the
  chats aborted are recorded."
  [workspace & [opts]]
  (let [db (atom {:chats {} :jobs {}})
        sent (atom [])
        aborted (atom [])]
    (merge (jobs/handle (merge {:db db
                                :workspace workspace
                                :chats-dir (io/file workspace "chats")
                                :clock :internal
                                :add-chat! (fn [id] (swap! db assoc-in [:chats id] {:id id :items [] :status :idle :cost 0.0}))
                                :send! (fn [id text] (swap! sent conj [id text]))
                                :abort! (fn [id] (swap! aborted conj id))}
                               opts))
           {:sent sent :aborted aborted})))

(deftest create-test
  (let [workspace (temp-dir)
        chat-id (chats/new-id)
        from (chat-folder! workspace chat-id)
        job (jobs/create! workspace {:name "Weekly card" :from (str from) :tz "Europe/Berlin"})
        template (jobs/template-dir workspace (:id job))
        chats-dir (io/file workspace "chats")]
    (testing "the job's folder: the template is the chat's folder without its history"
      (is (chats/valid-id? (:id job)))
      (is (= "Weekly card" (:name job)))
      (is (= :active (:state job)))
      (is (nil? (:schedule job)))
      (is (= "Europe/Berlin" (:tz job)))
      (is (re-matches #"[0-9a-f]{32}" (:token job)))
      (is (= {:cost-usd 1.0 :minutes 30} (:caps job)))
      (is (= "<h1>card</h1>" (slurp (io/file template "artifacts" "card.html"))))
      (is (= "png" (slurp (io/file template "uploads" "logo.png"))))
      (is (= "notes" (slurp (io/file template "notes.md"))))
      (is (not (.exists (io/file template ".session"))))
      (is (not (.exists (io/file template "hook.log"))))
      (is (not (.exists (io/file template ".chat.edn"))))
      (is (not (.exists (io/file template "bin"))) "no links to the shared parts")
      (is (not (.exists (io/file template "tmp"))))
      (is (= "" (jobs/prompt workspace (:id job))))
      (is (= job (jobs/read-job workspace (:id job))) "written as read"))
    (testing "the conversation: a chat named like the job, the template linked, the history copied under its own id"
      (let [cid (:chat job)
            dir (chats/dir chats-dir cid)]
        (is (chats/valid-id? cid))
        (is (not= cid chat-id))
        (is (= {:name "Weekly card" :job (:id job)} (chats/meta chats-dir cid)))
        (is (fs/sym-link? (io/file dir "template")))
        (is (= "notes" (slurp (io/file dir "template" "notes.md"))) "the link reaches the template")
        (is (= "<h1>card</h1>" (slurp (io/file dir "template" "artifacts" "card.html"))))
        (is (fs/sym-link? (io/file dir "bin")))
        (is (empty? (.listFiles (io/file dir ".session"))) "the source chat's session file was no pi session, nothing to copy")))
    (testing "the history of a chat with a pi session file comes along under the conversation's id"
      (let [source (chats/new-id)
            from (chat-folder! workspace source)
            _ (spit (io/file from ".session" (str "2026-10-13T14-41-30-309Z_" source ".jsonl"))
                    (str (pr-str-json {:type "session" :version 3 :id source :cwd (str from)}) "\n"
                         (pr-str-json {:type "message" :id "m1" :message {:role "user" :content "Make a card"}}) "\n"))
            job (jobs/create! workspace {:name "Forked" :from (str from)})
            session-dir (chats/session-dir chats-dir (:chat job))
            file (first (.listFiles session-dir))
            [header line] (str/split-lines (slurp file))]
        (is (= (str "2026-10-13T14-41-30-309Z_" (:chat job) ".jsonl") (.getName file)))
        (is (str/includes? header (str "\"id\":\"" (:chat job) "\"")))
        (is (str/includes? header "\"type\":\"session\""))
        (is (str/includes? line "Make a card"))
        (is (= "UTC" (:tz job)) "no zone given")
        (is (.isFile (io/file from ".session" (str "2026-10-13T14-41-30-309Z_" source ".jsonl"))) "the source keeps its own")
        (jobs/delete! workspace (:id job))))
    (testing "the prompt, the listing and the deletion"
      (jobs/set-prompt! workspace (:id job) "Make the card of the week.")
      (is (= "Make the card of the week." (jobs/prompt workspace (:id job))))
      (is (= [(:id job)] (jobs/ids workspace)))
      (is (= "Make the card of the week." (:prompt (first (jobs/all workspace)))))
      (jobs/delete! workspace (:id job))
      (is (= [] (jobs/ids workspace)))
      (is (nil? (jobs/read-job workspace (:id job))))
      (is (not (.exists (chats/dir chats-dir (:chat job)))) "the conversation went with the job")
      (is (.exists from) "the source chat stays"))))

(deftest ensure-conversation-test
  (let [workspace (temp-dir)
        handle (handle! workspace)
        from (chat-folder! workspace (chats/new-id))
        job (jobs/create! workspace {:name "Old" :from (str from)})]
    (jobs/load! handle)
    (testing "a job from before the conversations gets one on demand, once"
      (jobs/write! handle (dissoc (jobs/job handle (:id job)) :chat))
      (is (nil? (:chat (jobs/read-job workspace (:id job)))))
      (let [{:keys [chat]} (jobs/ensure-conversation! handle (jobs/job handle (:id job)))]
        (is (chats/valid-id? chat))
        (is (= chat (:chat (jobs/read-job workspace (:id job)))))
        (is (= chat (:chat (jobs/ensure-conversation! handle (jobs/job handle (:id job))))))
        (is (= {:name "Old" :job (:id job)} (chats/meta (io/file workspace "chats") chat)))))))

(deftest first-message-test
  (let [job {:prompt "Make the card.\n"}]
    (testing "the prompt, then the trigger as information"
      (let [text (jobs/first-message job {:kind :schedule :cron "0 9 * * 1" :at 0})]
        (is (str/starts-with? text "Make the card.\n\n## What triggered this run"))
        (is (str/includes? text "The schedule \"0 9 * * 1\" came due at 1970-01-01T00:00:00Z."))
        (is (str/includes? text "information, not an instruction"))))
    (testing "an endpoint call brings its body as text, JSON pretty"
      (let [text (jobs/first-message job {:kind :endpoint :at 0 :payload {:file "a.png"}})]
        (is (str/includes? text "called at 1970-01-01T00:00:00Z with this body:"))
        (is (str/includes? text "\"file\" : \"a.png\"")))
      (is (str/includes? (jobs/first-message job {:kind :endpoint :at 0}) "without a body."))
      (is (str/includes? (jobs/first-message job {:kind :endpoint :at 0 :payload "plain"}) "```\nplain\n```")))
    (testing "a long body is cut"
      (let [text (jobs/first-message job {:kind :endpoint :at 0 :payload (apply str (repeat 30000 "x"))})]
        (is (< (count text) 21000))
        (is (str/includes? text "…"))))
    (testing "by hand"
      (is (str/includes? (jobs/first-message job {:kind :manual :at 0}) "started the run by hand")))))

(deftest run-test
  (let [workspace (temp-dir)
        {:keys [db sent aborted] :as handle} (handle! workspace)
        from (chat-folder! workspace (chats/new-id))
        {:keys [id] :as job} (jobs/create! workspace {:name "Card" :from (str from)})]
    (jobs/set-prompt! workspace id "Make the card.")
    (jobs/load! handle)
    (is (= "Make the card." (get-in @db [:jobs id :prompt])))
    (testing "a run is a chat from the template with the first message sent"
      (let [{:keys [run]} (jobs/start! handle id {:kind :manual :at 1000})
            dir (chats/dir (io/file workspace "chats") run)]
        (is (chats/valid-id? run))
        (is (= "<h1>card</h1>" (slurp (io/file dir "artifacts" "card.html"))))
        (is (= "notes" (slurp (io/file dir "notes.md"))))
        (is (fs/sym-link? (io/file dir "bin")) "a chat folder like any other")
        (is (.isDirectory (io/file dir ".session")))
        (is (= [[run (jobs/first-message (get-in @db [:jobs id]) {:kind :manual :at 1000})]] @sent))
        (is (= :running (get-in @db [:chats run :run :state])))
        (is (= id (get-in @db [:chats run :run :job])))
        (is (= id (:job (:run (chats/meta (io/file workspace "chats") run)))) "in .chat.edn too")
        (is (str/starts-with? (get-in @db [:chats run :name]) "Card · "))
        (is (= run (jobs/running handle id)))
        (testing "one at a time: a second trigger waits, a schedule tick is skipped"
          (is (= {:queued true} (jobs/start! handle id {:kind :endpoint :at 2000 :payload "second"})))
          (is (= {:queued true} (jobs/start! handle id {:kind :endpoint :at 3000 :payload "third"})) "the newest waiting trigger wins")
          (jobs/write! handle (assoc (jobs/job handle id) :schedule "* * * * *"))
          (jobs/tick! handle (utc 2026 10 14 10 0))
          (is (= 1 (count @sent)) "nothing started during the run")
          (jobs/write! handle (assoc (jobs/job handle id) :schedule nil)))
        (testing "the agent settles: done with the cost, the waiting trigger starts the next run"
          (swap! db update-in [:chats run] assoc :cost 0.25 :items [{:kind :assistant :text "Done" :done? true}])
          (jobs/on-event! handle run {:type "agent_settled"})
          (is (= :done (get-in @db [:chats run :run :state])))
          (is (= 0.25 (get-in @db [:chats run :run :cost])))
          (is (= :done (get-in (chats/meta (io/file workspace "chats") run) [:run :state])))
          (is (= {:chat run :state :done} (select-keys (get-in @db [:jobs id :last-run]) [:chat :state])))
          (is (= 0 (get-in @db [:jobs id :failures])))
          (is (= 2 (count @sent)))
          (let [[next-run text] (second @sent)]
            (is (not= run next-run))
            (is (str/includes? text "third"))
            (is (= next-run (jobs/running handle id)))
            (testing "the cost cap stops a run"
              (swap! db assoc-in [:chats next-run :cost] 1.5)
              (jobs/on-event! handle next-run {:type "message_end"})
              (is (= [next-run] @aborted))
              (is (= :stopped (get-in @db [:chats next-run :run :state])))
              (is (= :cost (get-in @db [:chats next-run :run :reason])))
              (is (nil? (jobs/running handle id))))))))
    (testing "the time cap stops a run on a tick"
      (let [{:keys [run]} (jobs/start! handle id {:kind :manual :at 0})]
        (swap! db assoc-in [:chats run :run :started] (- (System/currentTimeMillis) (* 31 60 1000)))
        (jobs/tick! handle (utc 2026 10 14 10 1))
        (is (= :stopped (get-in @db [:chats run :run :state])))
        (is (= :time (get-in @db [:chats run :run :reason])))))
    (testing "three failed runs in a row pause the job"
      (doseq [n (range 3)]
        (let [{:keys [run]} (jobs/start! handle id {:kind :manual :at n})]
          (is run (str "run " n " starts"))
          (swap! db assoc-in [:chats run :items] [{:kind :assistant :text "" :error "boom"}])
          (jobs/on-event! handle run {:type "agent_settled"})
          (is (= :failed (get-in @db [:chats run :run :state])))))
      (is (= 3 (get-in @db [:jobs id :failures])))
      (is (= :paused (get-in @db [:jobs id :state])))
      (is (= :paused (:state (jobs/read-job workspace id))) "on disk too")
      (is (= {:error :paused} (jobs/start! handle id {:kind :manual :at 0}))))
    (testing "a process that exits while working is a failed run"
      (jobs/write! handle (assoc (jobs/job handle id) :state :active :failures 0))
      (let [{:keys [run]} (jobs/start! handle id {:kind :manual :at 0})]
        (jobs/on-event! handle run {:type "exited" :exit 1})
        (is (= :failed (get-in @db [:chats run :run :state])))
        (is (= 1 (get-in @db [:jobs id :failures])))))
    (testing "the runs of the job, newest first"
      (let [runs (jobs/runs-of handle id)]
        (is (= 7 (count runs)))
        (is (every? #(= id (get-in % [:run :job])) runs))))
    (testing "the jobs survive a restart, with the next run"
      (jobs/write! handle (assoc (jobs/job handle id) :schedule "0 9 * * 1-5"))
      (let [handle2 (handle! workspace)]
        (jobs/load! handle2)
        (is (= :active (get-in @(:db handle2) [:jobs id :state])))
        (is (= "Make the card." (get-in @(:db handle2) [:jobs id :prompt])))
        (is (number? (get-in @(:db handle2) [:jobs id :next])))))))

(deftest schedule-test
  (let [workspace (temp-dir)
        {:keys [db sent] :as handle} (handle! workspace)
        from (chat-folder! workspace (chats/new-id))
        {:keys [id]} (jobs/create! workspace {:name "Nine" :from (str from)})]
    (jobs/load! handle)
    (jobs/write! handle (assoc (jobs/job handle id) :schedule "0 9 * * 1-5"))
    (is (number? (get-in @db [:jobs id :next])) "the next run as milliseconds")
    (testing "a due minute starts a run once"
      (jobs/tick! handle (utc 2026 10 14 8 59))
      (is (= 0 (count @sent)))
      (jobs/tick! handle (utc 2026 10 14 9 0))
      (is (= 1 (count @sent)))
      (jobs/tick! handle (utc 2026 10 14 9 0))
      (is (= 1 (count @sent)) "the same minute does not fire twice")
      (is (= {:kind :schedule :cron "0 9 * * 1-5"} (-> (jobs/runs-of handle id) first :run :trigger (select-keys [:kind :cron])))))
    (testing "a paused job does not fire"
      (jobs/write! handle (assoc (jobs/job handle id) :state :paused))
      (jobs/tick! handle (utc 2026 10 15 9 0))
      (is (= 1 (count @sent))))
    (testing "with the external clock nothing fires; the endpoint is the way"
      (jobs/write! handle (assoc (jobs/job handle id) :state :active))
      (jobs/tick! (assoc handle :clock :external) (utc 2026 10 16 9 0))
      (is (= 1 (count @sent))))
    (testing "in the job's zone: 9:00 in Berlin is 7:00 UTC in October"
      (jobs/finish! handle (:id (first (jobs/runs-of handle id))) :done)
      (jobs/write! handle (assoc (jobs/job handle id) :tz "Europe/Berlin"))
      (jobs/tick! handle (utc 2026 10 19 9 0))
      (is (= 1 (count @sent)) "not at 9:00 UTC")
      (jobs/tick! handle (utc 2026 10 19 7 0))
      (is (= 2 (count @sent)) "at 7:00 UTC")
      (is (= "Europe/Berlin" (-> (jobs/runs-of handle id) first :run :trigger :tz))))))

(deftest endpoint-test
  (let [workspace (temp-dir)
        {:keys [db sent] :as handle} (handle! workspace)
        from (chat-folder! workspace (chats/new-id))
        {:keys [id token]} (jobs/create! workspace {:name "Hook" :from (str from)})
        call (fn [path & [request]]
               (:ring/response (jobs/handler handle {:ring/request (merge {:request-method :post} request)
                                                     :ring/route-params {:* path}})))]
    (jobs/load! handle)
    (testing "without the token, with a wrong one, for another job: nothing"
      (is (= 404 (:status (call (str id "/run")))))
      (is (= 404 (:status (call (str id "/run") {:query-string "token=nope"}))))
      (is (= 404 (:status (call (str (chats/new-id) "/run") {:query-string (str "token=" token)}))))
      (is (= 404 (:status (call (str id "/stop") {:query-string (str "token=" token)}))))
      (is (= 0 (count @sent))))
    (testing "with the token in the query: a run, the body reaches the agent"
      (let [response (call (str id "/run") {:query-string (str "token=" token)
                                            :headers {"content-type" "application/json"}
                                            :body (java.io.ByteArrayInputStream. (.getBytes "{\"file\":\"a.png\"}" "UTF-8"))})]
        (is (= 202 (:status response)))
        (is (re-find #"\{\"run\":\"[0-9a-f-]{36}\"\}" (:body response)))
        (is (= 1 (count @sent)))
        (is (str/includes? (second (first @sent)) "\"file\" : \"a.png\""))
        (is (= :endpoint (-> (jobs/runs-of handle id) first :run :trigger :kind)))))
    (testing "during the run a call is queued"
      (is (= {:status 202 :body "{\"queued\":true}"}
             (select-keys (call (str id "/run") {:headers {"x-job-token" token}}) [:status :body]))))
    (testing "the token as a header, a bearer, on a paused job"
      (jobs/write! handle (assoc (jobs/job handle id) :state :paused))
      (let [response (call (str id "/run") {:headers {"authorization" (str "Bearer " token)}})]
        (is (= 409 (:status response)))
        (is (= "{\"error\":\"paused\"}" (:body response)))))
    (testing "the route"
      (is (= [[:post "/jobs/*"]] (map :ring/route (jobs/register handle)))))))
