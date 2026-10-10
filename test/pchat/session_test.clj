(ns pchat.session-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [pchat.session :as session]
            [pchat.view :as view]))

(def fixture
  "A session pi 0.85 wrote: a one-word reply, then a page written with
  the write tool."
  (io/file "test/pchat/session-fixture.jsonl"))

(deftest id-of-test
  (is (= "45a549c4-5c50-4113-8d9e-a3114f8a81bb"
         (session/id-of "2026-10-10T07-41-20-470Z_45a549c4-5c50-4113-8d9e-a3114f8a81bb.jsonl")))
  (is (nil? (session/id-of "notes.txt"))))

(deftest items-test
  (let [items (session/items (session/entries fixture))]
    (is (= [:user :assistant :user :assistant :tool :assistant] (map :kind items)))
    (is (= "pong" (:text (second items))))
    (let [tool (nth items 4)]
      (is (= "write" (:name tool)))
      (is (= "artifacts/hello-harry.html" (get-in tool [:args :path])))
      (is (:done? tool))
      (is (false? (:error? tool)))
      (is (seq (get-in tool [:result :content]))))
    (is (every? :done? (filter #(= :assistant (:kind %)) items)))))

(deftest summary-and-load-test
  (let [{:keys [id created updated] chat-name :name} (session/summary fixture)
        chat (session/load-chat fixture)]
    (is (= "45a549c4-5c50-4113-8d9e-a3114f8a81bb" id))
    (is (= "Reply with the single word pong. Do not use any…" chat-name))
    (is (< created updated))
    (is (= :idle (:status chat)))
    (is (:loaded? chat))
    (is (< 0.008 (:cost chat) 0.01))
    (testing "the loaded chat renders like a live one: the empty tool-call message hidden, the last answer the handover"
      (let [feed (view/feed chat)
            kinds (->> (tree-seq coll? seq feed)
                       (filter #(and (vector? %) (keyword? (first %))))
                       (map first)
                       (filter #(clojure.string/starts-with? (name %) "div.item")))]
        (is (= [:div.item.user :div.item.step :div.item.user :div.item.step :div.item.handover] kinds))))))

(deftest sidebar-test
  (let [a (merge (session/load-chat fixture) {:updated 2})
        b {:id "b" :items [] :status :working :updated 3 :cost 0.0}
        sidebar (view/sidebar {:chats {(:id a) a "b" b} :current "b"})
        lis (filter #(and (vector? %) (= :li (first %))) (tree-seq coll? seq sidebar))]
    (is (= ["b" (:id a)] (map #(get-in % [1 :replicant/key]) lis)))
    (is (= "current working" (get-in (first lis) [1 :class])))
    (is (some #{"New chat"} (filter string? (tree-seq coll? seq sidebar))))))
