(ns pchat.chat-test
  (:require [clojure.test :refer [deftest is testing]]
            [pchat.chat :as chat]
            [pchat.view :as view]))

(def events
  "The events of one prompt that answered with a tool call and a sentence,
  in the shape pi 0.85 emits them over rpc."
  [{:type "response" :id "p" :command "prompt" :success true}
   {:type "agent_start"}
   {:type "turn_start"}
   {:type "message_start" :message {:role "user" :content [{:type "text" :text "Make a page."}]}}
   {:type "message_end" :message {:role "user" :content [{:type "text" :text "Make a page."}]}}
   {:type "message_start" :message {:role "assistant" :content []}}
   {:type "message_update" :assistantMessageEvent {:type "toolcall_start" :id "call_1" :toolName "write"}}
   {:type "message_end" :message {:role "assistant" :content [] :stopReason "toolUse"
                                  :usage {:cost {:total 0.004}}}}
   {:type "tool_execution_start" :toolCallId "call_1" :toolName "write" :args {:path "artifacts/hello.html" :content "<h1>Hi</h1>"}}
   {:type "tool_execution_end" :toolCallId "call_1" :toolName "write" :result {:content [{:type "text" :text "Wrote 11 bytes"}]} :isError false}
   {:type "turn_end"}
   {:type "turn_start"}
   {:type "message_start" :message {:role "assistant" :content []}}
   {:type "message_update" :assistantMessageEvent {:type "text_delta" :delta "It is "}}
   {:type "message_update" :assistantMessageEvent {:type "text_delta" :delta "there."}}
   {:type "message_end" :message {:role "assistant" :content [{:type "text" :text "It is there."}] :stopReason "stop"
                                  :usage {:cost {:total 0.006}}}}
   {:type "turn_end"}
   {:type "agent_end" :messages [] :willRetry false}
   {:type "agent_settled"}])

(deftest fold-test
  (let [chat (chat/fold chat/empty-chat events)]
    (is (= :idle (:status chat)))
    (is (= 0.01 (:cost chat)))
    (is (= [:user :assistant :tool :assistant] (map :kind (:items chat))))
    (is (= "Make a page." (:text (first (:items chat)))))
    (let [[_ empty-assistant tool answer] (:items chat)]
      (is (= "" (:text empty-assistant)))
      (is (:done? empty-assistant))
      (is (= {:id "call_1" :name "write" :done? true :error? false}
             (select-keys tool [:id :name :done? :error?])))
      (is (= "Wrote 11 bytes" (chat/result-text (:result tool))))
      (is (= "It is there." (:text answer)))
      (is (:done? answer)))))

(deftest working-status-test
  (let [chat (chat/fold chat/empty-chat (take 7 events))]
    (is (= :working (:status chat)))
    (is (not (:done? (last (:items chat)))))))

(deftest error-and-exit-test
  (let [chat (chat/fold chat/empty-chat
                        [{:type "agent_start"}
                         {:type "message_start" :message {:role "assistant" :content []}}
                         {:type "message_end" :message {:role "assistant" :content [] :stopReason "error" :errorMessage "429 rate limited"}}
                         {:type "response" :command "prompt" :success false :error "agent is streaming"}
                         {:type "stderr" :line "warning"}
                         {:type "exited" :exit 1}])]
    (is (= "429 rate limited" (:error (first (:items chat)))))
    (is (= :note (:kind (second (:items chat)))))
    (is (= :idle (:status chat)))
    (is (= ["warning" "pi exited with 1"] (:log chat)))))

(defn- texts
  [hiccup]
  (->> (tree-seq coll? seq hiccup)
       (filter string?)))

(deftest page-test
  (let [chat (chat/fold chat/empty-chat events)
        page (view/page {:chat chat} {})
        feed (view/feed chat)
        items (filter #(and (vector? %) (keyword? (first %)) (clojure.string/starts-with? (name (first %)) "div.item"))
                      (tree-seq coll? seq feed))]
    (testing "the empty assistant message is not shown, the last one is the handover"
      (is (= [:div.item.user :div.item.step :div.item.handover] (map first items))))
    (testing "the tool row is collapsed and named for a person"
      (is (some #{"Wrote a file"} (texts feed)))
      (is (some #{"artifacts/hello.html"} (texts feed))))
    (is (some #{"$0.01"} (texts page)))))
