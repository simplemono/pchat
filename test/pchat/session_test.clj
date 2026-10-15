(ns pchat.session-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [pchat.i18n :as i18n]
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
        sidebar (i18n/translate (view/sidebar {:chats {(:id a) a "b" b}} {:id "b"}) :en)
        lis (filter #(and (vector? %) (= :li (first %))) (tree-seq coll? seq sidebar))]
    (is (= ["b" (:id a)] (map #(get-in % [1 :replicant/key]) lis)))
    (is (= "current working" (get-in (first lis) [1 :class])))
    (let [nodes (fn [li tag] (filter #(and (vector? %) (= tag (first %))) (tree-seq coll? seq li)))
          commands (fn [li] (->> (nodes li :data/command) (map (comp :command/kind second))))]
      (is (= "/chat/b" (get-in (first (nodes (first lis) :a.row)) [1 :href])) "a row is a link to the chat's page")
      (is (= [:job/turn :chat/delete] (commands (first lis))) "the actions of the chat are in the row's menu")
      (is (= "b" (-> (nodes (first lis) :details.actions) first (nth 2) (nth 1) (get-in [1 :on :click 0 1 :command/data :id])))
          "the menu acts on its own chat"))
    (is (some #{"New chat"} (filter string? (tree-seq coll? seq sidebar)))))
  (testing "a row whose delete is armed asks instead of linking"
    (let [b {:id "b" :items [] :status :idle :updated 3 :cost 0.0}
          sidebar (i18n/translate (view/sidebar {:chats {"b" b} :deleting "b"} {:id "b"}) :en)
          strings (filter string? (tree-seq coll? seq sidebar))]
      (is (some #{"Delete this chat for good?"} strings))
      (is (not-any? #(and (vector? %) (= :a.row (first %))) (tree-seq coll? seq sidebar)))))
  (testing "the running version stands next to the name once the updater noted it"
    (let [strings (fn [db] (filter string? (tree-seq coll? seq (view/sidebar db))))]
      (is (some #{"0.1.7"} (strings {:chats {} :update {:current "0.1.7"}})))
      (is (not-any? #{"dev"} (strings {:chats {}})) "nothing without a version"))))
