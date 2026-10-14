(ns pchat.genui-test
  (:require [clojure.test :refer [deftest is testing]]
            [pchat.genui :as genui]))

(def chat
  {:id "c"})

(deftest sanitize-test
  (testing "tags and attributes outside the allow-lists go, children stay"
    (is (= [:span {} "x"] (genui/sanitize [:script {:onclick "evil()"} "x"] chat)))
    (is (= [:div.card {:class "a"} [:p {} "Hi"]]
           (genui/sanitize [:div.card {:class "a" :onclick "x" :id "i"} [:p "Hi"]] chat))))
  (testing "a file is the chat's own, named as the agent sees it, or https"
    (is (= [:audio {:src "/chat/c/artifacts/card/music.mp3" :controls true}]
           (genui/sanitize [:audio {:src "artifacts/card/music.mp3" :controls true}] chat)))
    (is (= [:img {:src "/chat/c/artifacts/pool.png"}]
           (genui/sanitize [:img {:src "/artifacts/pool.png"}] chat)))
    (is (= [:img {:src "/chat/c/uploads/a.png"}]
           (genui/sanitize [:img {:src "uploads/a.png"}] chat)))
    (is (= [:img {:src "/chat/c/uploads/a.png"}]
           (genui/sanitize [:img {:src "/looks/a.png"}] chat))
        "the name of earlier builds")
    (is (= [:img {}] (genui/sanitize [:img {:src "/chat/d/artifacts/x.png"}] chat))
        "another chat's files are not reachable")
    (is (= [:img {}] (genui/sanitize [:img {:src "http://evil.example/x.png"}] chat)))
    (is (= [:img {}] (genui/sanitize [:img {:src "javascript:alert(1)"}] chat)))
    (is (= [:a {:href "https://example.com" :target "_blank" :rel "noopener"} "there"]
           (genui/sanitize [:a {:href "https://example.com"} "there"] chat))))
  (testing "a button sends its text as the next message of the chat"
    (is (= [:button {:on {:click [[:data/command {:command/kind :chat/send
                                                   :command/data {:id "c"
                                                                  :text "The first one"}}]]}}
            "Take the first"]
           (genui/sanitize [:button {:send "The first one"} "Take the first"] chat))))
  (testing "a button opens an artifact of the chat"
    (is (= {:id "c" :path "card"}
           (get-in (genui/sanitize [:button {:open "/artifacts/card"} "Show"] chat)
                   [1 :on :click 0 1 :command/data]))))
  (testing "a style is a map of plain values"
    (is (= [:p {:style {:color "red"}} "x"]
           (genui/sanitize [:p {:style {:color "red" :background [:fn 1]}} "x"] chat)))
    (is (= [:p {} "x"] (genui/sanitize [:p {:style "color: red"} "x"] chat))))
  (testing "what is no hiccup becomes text"
    (is (= [:p {} "x" ["a" "b"]] (genui/sanitize [:p "x" ["a" "b"]] chat)))
    (is (= [:p {} "k"] (genui/sanitize [:p :k] chat)))))

(deftest children-test
  (let [text "Two:\n\n```hiccup\n[:ul [:li [:button {:send \"a\"} \"A\"]]]\n```\n\nWhich?"
        out (vec (genui/children text chat))]
    (is (= [:p "Two:"] (first out)))
    (is (= :div.genui (first (second out))))
    (is (= [:p "Which?"] (nth out 2))))
  (testing "a block that does not parse is code with the error"
    (let [[block] (genui/children "```hiccup\n[:div {:a\n```" chat)]
      (is (= :div.genui.genui-error (first block)))
      (is (= :genui/rejected (get-in block [1 1 1])))))
  (testing "an open fence is still markdown"
    (is (= [:pre [:code "[:div\n"]] (first (genui/children "```hiccup\n[:div" chat))))
    (is (= () (genui/children "" chat)))))
