(ns pchat.chat
  "A chat as a value: what the events of its pi process add up to.
  `event` folds one event into the chat, the view renders the chat.

      {:items  [{:kind :user :text ... :images [url ...]}
                {:kind :assistant :text ... :thinking ... :done? ...}
                {:kind :tool :id ... :name ... :args ... :result ... :done? ...}
                {:kind :note :text ...}]
       :status :idle | :working
       :cost   USD so far
       :log    stderr lines of pi, for debugging}"
  (:require [clojure.string :as str]))

(def empty-chat
  {:items []
   :status :idle
   :cost 0.0
   :log []})

(defn- text-of
  [content]
  (if (string? content)
    content
    (->> content
         (filter #(= "text" (:type %)))
         (map :text)
         (str/join ""))))

(defn- images-of
  "The URLs of the image blocks, after `pchat.looks/intern`."
  [content]
  (if (string? content)
    []
    (into []
          (comp (filter #(= "image" (:type %)))
                (keep :url))
          content)))

(defn- last-index
  [items pred]
  (last (keep-indexed (fn [i item]
                        (when (pred item)
                          i))
                      items)))

(defn- update-last
  [chat pred f]
  (if-let [i (last-index (:items chat) pred)]
    (update-in chat [:items i] f)
    chat))

(defn result-text
  "The text of a tool result, whatever shape pi gave it."
  [result]
  (cond
    (nil? result) ""
    (string? result) result
    (sequential? (:content result)) (text-of (:content result))
    (string? (:output result)) (:output result)
    :else (pr-str result)))

(defn event
  "The chat after one event of pi."
  [chat {:keys [type] :as e}]
  (case type
    "agent_start" (assoc chat :status :working)
    "agent_settled" (assoc chat :status :idle)
    "message_start" (let [{:keys [role content]} (:message e)]
                      (case role
                        "user" (update chat :items conj {:kind :user
                                                         :text (text-of content)
                                                         :images (images-of content)})
                        "assistant" (update chat :items conj {:kind :assistant
                                                              :text ""
                                                              :thinking ""})
                        chat))
    "message_update" (let [{:keys [type delta]} (:assistantMessageEvent e)]
                       (case type
                         "text_delta" (update-last chat #(= :assistant (:kind %)) #(update % :text str delta))
                         "thinking_delta" (update-last chat #(= :assistant (:kind %)) #(update % :thinking str delta))
                         chat))
    "message_end" (let [{:keys [role usage stopReason errorMessage]} (:message e)]
                    (if (= "assistant" role)
                      (-> chat
                          (update-last #(= :assistant (:kind %))
                                       #(cond-> (assoc % :done? true)
                                          (= "error" stopReason) (assoc :error (or errorMessage "The model call failed."))))
                          (update :cost + (or (get-in usage [:cost :total]) 0.0)))
                      chat))
    "tool_execution_start" (update chat :items conj {:kind :tool
                                                     :id (:toolCallId e)
                                                     :name (:toolName e)
                                                     :args (:args e)})
    "tool_execution_end" (update-last chat #(and (= :tool (:kind %)) (= (:toolCallId e) (:id %)))
                                      #(assoc % :done? true
                                              :result (:result e)
                                              :error? (boolean (:isError e))))
    "response" (if (false? (:success e))
                 (update chat :items conj {:kind :note
                                           :text (str "That did not go through: " (:error e))})
                 chat)
    "stderr" (update chat :log conj (:line e))
    "exited" (-> chat
                 (assoc :status :idle)
                 (update :log conj (str "pi exited with " (:exit e))))
    chat))

(defn fold
  [chat events]
  (reduce event chat events))
