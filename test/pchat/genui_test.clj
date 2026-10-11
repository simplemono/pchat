(ns pchat.genui-test
  (:require [clojure.test :refer [deftest is testing]]
            [pchat.genui :as genui]))

(deftest sanitize-test
  (testing "tags and attributes outside the allow-lists go, children stay"
    (is (= [:span {} "x"] (genui/sanitize [:script {:onclick "evil()"} "x"])))
    (is (= [:div.card {:class "a"} [:p {} "Hi"]]
           (genui/sanitize [:div.card {:class "a" :onclick "x" :id "i"} [:p "Hi"]]))))
  (testing "a file points at this host or https"
    (is (= [:audio {:src "/artifacts/card/music.mp3" :controls true}]
           (genui/sanitize [:audio {:src "artifacts/card/music.mp3" :controls true}])))
    (is (= [:img {}] (genui/sanitize [:img {:src "http://evil.example/x.png"}])))
    (is (= [:img {}] (genui/sanitize [:img {:src "javascript:alert(1)"}])))
    (is (= [:a {:href "https://example.com" :target "_blank" :rel "noopener"} "there"]
           (genui/sanitize [:a {:href "https://example.com"} "there"]))))
  (testing "a button sends its text as the next message"
    (is (= [:button {:on {:click [[:data/command {:command/kind :chat/send
                                                   :command/data {:text "The first one"}}]]}}
            "Take the first"]
           (genui/sanitize [:button {:send "The first one"} "Take the first"]))))
  (testing "a button opens an artifact"
    (is (= {:path "card"}
           (get-in (genui/sanitize [:button {:open "/artifacts/card"} "Show"])
                   [1 :on :click 0 1 :command/data]))))
  (testing "a style is a map of plain values"
    (is (= [:p {:style {:color "red"}} "x"]
           (genui/sanitize [:p {:style {:color "red" :background [:fn 1]}} "x"])))
    (is (= [:p {} "x"] (genui/sanitize [:p {:style "color: red"} "x"]))))
  (testing "what is no hiccup becomes text"
    (is (= [:p {} "x" ["a" "b"]] (genui/sanitize [:p "x" ["a" "b"]])))
    (is (= [:p {} "k"] (genui/sanitize [:p :k])))))

(deftest children-test
  (let [text "Two:\n\n```hiccup\n[:ul [:li [:button {:send \"a\"} \"A\"]]]\n```\n\nWhich?"
        out (vec (genui/children text))]
    (is (= [:p "Two:"] (first out)))
    (is (= :div.genui (first (second out))))
    (is (= [:p "Which?"] (nth out 2))))
  (testing "a block that does not parse is code with the error"
    (let [[block] (genui/children "```hiccup\n[:div {:a\n```")]
      (is (= :div.genui.genui-error (first block)))
      (is (= :genui/rejected (get-in block [1 1 1])))))
  (testing "an open fence is still markdown"
    (is (= [:pre [:code "[:div\n"]] (first (genui/children "```hiccup\n[:div"))))
    (is (= () (genui/children "")))))
