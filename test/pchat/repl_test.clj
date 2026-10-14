(ns pchat.repl-test
  "A chat at the REPL: the agent's log as pchat's items, the pane of the
  creation with a view the model defined, the way in from the sidebar."
  (:require [clojure.test :refer [deftest is testing]]
            [pchat.i18n :as i18n]
            [pchat.repl :as repl]
            [pchat.view :as view]))

(def log
  [{:type :run/started :task "copy it faithfully"}
   {:type :user/attached
    :message {:role "user"
              :content [{:type "text" :text "The images the user attached:"}
                        {:type "image" :source {:type "look" :path "/tmp/x/looks/ab12.png"}}]}}
   {:type :model/responded
    :note "I start with the card."
    :actions [{:command "(defn design-view [d] [:div (:title d)])"}
              {:command "(done \"A card is there.\")"}]}
   {:type :actions/observed
    :outputs [{:output "$1 => #'user/design-view\n" :returncode 0 :evaluated? true}
              {:output "COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT\nA card is there." :returncode 0 :evaluated? true}]}
   {:type :user/interrupted
    :interrupt-type "UserNewTask"
    :message {:role "user" :content "The user added a new task: make it blue"}}
   {:type :model/responded
    :note ""
    :actions [{:command "(oops"} {:command "" :reader-error "EOF"}]}
   {:type :actions/observed
    :outputs [{:output ";; Reader error" :returncode 1 :evaluated? true}
              {:output ";; Reader error: EOF" :returncode 1}]}])

(deftest items-test
  (let [items (repl/items log)]
    (testing "the task with its picture, the comment, the form as an eval, the handover, the next task"
      (is (= [:user :assistant :tool :assistant :user :tool]
             (mapv :kind items)))
      (is (= {:kind :user :text "copy it faithfully" :images ["/looks/ab12.png"]} (first items)))
      (is (= "I start with the card." (:text (nth items 1))))
      (is (= {:name "eval" :args {:command "(defn design-view [d] [:div (:title d)])"} :done? true :result "$1 => #'user/design-view\n" :error? false}
             (select-keys (nth items 2) [:name :args :done? :result :error?])))
      (is (= "A card is there." (:text (nth items 3))) "the done output is the handover message")
      (is (= "make it blue" (:text (nth items 4))))
      (is (true? (:error? (nth items 5))) "a form that failed is marked"))))

(deftest pane-test
  (testing "a design with its view, rendered; a function in the hiccup is dropped, an error becomes a line"
    (let [view-fn (fn [d] [:div.card [:h1 (:title d)] [:button {:on (fn [] :nope)} "x"]])
          pane (i18n/translate (view/repl-pane {:view-fn view-fn :design {:title "Hello"} :source "(defn design-view [d] ...)"}) :en)
          strings (filter string? (tree-seq coll? seq pane))]
      (is (some #{"Hello"} strings))
      (is (some #{"The code of your creation"} strings))
      (is (not-any? fn? (tree-seq coll? seq pane)) "no function survives into the frame")
      (is (= [:p.error "The view failed: boom"]
             (i18n/translate (repl/render (fn [_] (throw (ex-info "boom" {}))) {}) :en)))))
  (testing "a motion design gets the player"
    (let [pane (i18n/translate (view/repl-pane {:view-fn (fn [d t] [:div (str (:title d) "@" t)]) :design {:title "Clip" :duration 4} :duration 4 :motion/t 1.0 :motion/playing? false}) :en)
          strings (filter string? (tree-seq coll? seq pane))]
      (is (some #{"Clip@1.0"} strings))
      (is (some #{"Play"} strings))
      (is (some #{"0:01.0 / 0:04.0"} strings))))
  (testing "nothing defined yet"
    (is (some #{"Your creation will appear here, with controls to play with it."}
              (filter string? (tree-seq coll? seq (i18n/translate (view/repl-pane nil) :en)))))))

(deftest sidebar-and-page-test
  (testing "the sidebar offers the REPL chat"
    (is (some #{"Design at the REPL (experiment)"}
              (filter string? (tree-seq coll? seq (i18n/translate (view/sidebar {:chats {}}) :en))))))
  (testing "a REPL chat's page shows the creation pane, not the artifacts"
    (let [db {:chats {"r" {:id "r" :mode :repl :items [] :status :idle :cost 0.0}} :current "r"
              :repl/pane {:view-fn (fn [d] [:div (:title d)]) :design {:title "Mine"}}}
          page (i18n/translate (view/page db {:locale :en}) :en)
          strings (filter string? (tree-seq coll? seq page))]
      (is (some #{"Mine"} strings))
      (is (some #{"Your creation"} strings)))))
