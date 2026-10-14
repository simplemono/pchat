(ns pchat.actions-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pchat.actions :as actions]
            [pchat.chat :as chat]))

(defn- temp-dir
  []
  (str (java.nio.file.Files/createTempDirectory
        "pchat-actions"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- script!
  "An executable shell script in `dir`."
  [dir name body]
  (let [file (io/file dir name)]
    (spit file (str "#!/bin/sh\n" body))
    (.setExecutable file true)
    (str file)))

(def chat-id
  "0190a5c8-1f2e-7000-8000-000000000001")

(defn- run!
  "Starts the action in the chat and waits for its end: [path entry]."
  [handle finished opts]
  (is (= (:path opts) (actions/start! handle chat-id opts)))
  (deref finished 10000 [:timeout nil]))

(deftest read-actions-test
  (let [dir (temp-dir)]
    (is (= {} (actions/read-actions dir)) "no actions.json, no actions")
    (spit (io/file dir "actions.json")
          (json/generate-string {:apply {:run ["node" "{skill}/tools/apply.mjs"] :label "Apply" :timeout 120}
                                 :check {:run ["node" "{skill}/tools/check.mjs"]}
                                 :broken {:run "rm -rf /"}
                                 :empty {:run []}}))
    (let [found (actions/read-actions dir)]
      (is (= #{"apply" "check"} (set (keys found))) "a run that is not a list of strings is no action")
      (is (= {:run ["node" "{skill}/tools/apply.mjs"] :label "Apply" :timeout 120} (get found "apply")))
      (is (= actions/default-timeout-s (get-in found ["check" :timeout])))
      (is (= ["node" (str dir "/tools/apply.mjs")] (actions/command-line (get found "apply") dir))))))

(deftest start-test
  (let [chats-dir (temp-dir)
        chat-dir (io/file chats-dir chat-id)
        _ (.mkdirs chat-dir)
        skill (temp-dir)
        finished (atom (promise))
        db (atom {})
        handle (actions/handle {:db db
                                :chats-dir chats-dir
                                :env (fn [id] {"CHAT" id})
                                :on-finish (fn [id path entry] (deliver @finished [path (assoc entry :chat id)]))})]
    (testing "the request on stdin, the progress, the last JSON line as the result"
      (let [run (script! skill "apply.sh"
                         (str "cat > got.json\n"
                              "echo \"$CHAT\" > env.txt\n"
                              "echo 'progress building'\n"
                              "echo 'some output'\n"
                              "echo '{\"ok\": true, \"note\": \"Built, 0 errors\"}'\n"))
            [path entry] (run! handle @finished {:skill-dir skill
                                                 :action-name "apply"
                                                 :action {:run [run] :label "Apply" :timeout 10}
                                                 :path "short-1"
                                                 :input {:world "noir"}})]
        (is (= "short-1" path))
        (is (= {"artifact" "short-1" "input" {"world" "noir"}}
               (json/parse-string (slurp (io/file chat-dir "got.json"))))
            "it runs in the chat's folder, the request as JSON on its stdin")
        (is (= chat-id (str/trim (slurp (io/file chat-dir "env.txt")))) "with the chat's environment")
        (is (= (dissoc entry :chat) (get-in @db [:chats chat-id :actions "short-1"])) "kept in the chat of the db")
        (is (= 0 (:exit entry)))
        (is (= {:ok true :note "Built, 0 errors"} (:result entry)))
        (is (= ["apply" "Apply" chat-id] ((juxt :action :label :chat) entry)))
        (is (nil? (:error entry)))
        (is (nil? (:progress entry)) "no progress once it is over")
        (is (= "(Action on the page: apply on short-1, done: Built, 0 errors)"
               (actions/note-line path entry)))))
    (testing "a failure: the result's error, else the last line of stderr"
      (reset! finished (promise))
      (let [run (script! skill "fail.sh" "echo '{\"ok\": false, \"error\": \"No such world\"}'\nexit 1\n")
            [_ entry] (run! handle @finished {:skill-dir skill :action-name "apply"
                                              :action {:run [run] :timeout 10}
                                              :path "short-1" :input {}})]
        (is (= 1 (:exit entry)))
        (is (= "No such world" (:error entry)))
        (is (= "(Action on the page: apply on short-1, failed: No such world)"
               (actions/note-line "short-1" entry))))
      (reset! finished (promise))
      (let [run (script! skill "crash.sh" "echo 'TypeError: x is undefined' >&2\nexit 2\n")
            [_ entry] (run! handle @finished {:skill-dir skill :action-name "apply"
                                              :action {:run [run] :timeout 10}
                                              :path "short-1" :input {}})]
        (is (= "TypeError: x is undefined" (:error entry)))))
    (testing "one at a time per artifact; one that takes too long is stopped"
      (reset! finished (promise))
      (let [run (script! skill "slow.sh" "sleep 5\n")
            opts {:skill-dir skill :action-name "slow" :action {:run [run] :timeout 1}
                  :path "short-2" :input {}}]
        (is (= "short-2" (actions/start! handle chat-id opts)))
        (is (actions/running? handle chat-id "short-2"))
        (is (actions/any-running? handle))
        (is (nil? (actions/start! handle chat-id opts)) "the second waits its turn")
        (let [[_ entry] (deref @finished 10000 [:timeout nil])]
          (is (= "It took too long and was stopped." (:error entry))))
        (is (not (actions/running? handle chat-id "short-2")))))))

(deftest note-lines-test
  (let [text (str "(Action on the page: apply on short-1, done: Built)\n"
                  "(Action on the page: apply on short-1, failed: oops)\n"
                  "Make the title bigger")]
    (is (= "Make the title bigger" (actions/without-notes text)))
    (is (= "Make the title bigger" (:text (chat/user-item text)))
        "the chat shows the person's words, not the lines for the agent")))

(deftest artifact-test
  (let [dir (temp-dir)]
    (.mkdirs (io/file dir "short-1"))
    (spit (io/file dir "card.html") "<p>")
    (is (actions/artifact? dir "short-1"))
    (is (actions/artifact? dir "card.html"))
    (is (not (actions/artifact? dir "missing")))
    (is (not (actions/artifact? dir "../short-1")) "nothing outside the folder")
    (is (not (actions/artifact? dir ".hidden")))
    (is (not (actions/artifact? dir "")))))

