(ns pchat.hyperframes-template-test
  "The HyperFrames project template's own functions: the variables its
  composition declares become controls, their values reach the
  composition the way the renderer gives them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(load-file "resources/pchat/template/hyperframes/src/artifact/main.clj")

(defn- f
  [name]
  (resolve (symbol "artifact.main" name)))

(def html
  "<!doctype html>
<html lang=\"en\" data-composition-variables='[
  {\"id\":\"title\",\"type\":\"string\",\"label\":\"Title\",\"default\":\"Hello\"},
  {\"id\":\"size\",\"type\":\"number\",\"default\":120},
  {\"id\":\"mood\",\"type\":\"string\",\"default\":\"calm\",\"options\":[\"calm\",{\"value\":\"loud\",\"label\":\"Loud\"}]},
  {\"id\":\"shout\",\"type\":\"boolean\",\"default\":false},
  {\"label\":\"no id\"}
]'>
<head><meta charset=\"utf-8\"></head>
<body><div data-composition-id=\"main\" data-width=\"1920\" data-height=\"1080\"></div></body></html>")

(deftest declarations-test
  (testing "the declarations of the <html>, the ones with an id"
    (is (= ["title" "size" "mood" "shout"] (map #(get % "id") ((f "declarations") html)))))
  (testing "double quotes with entities, as an editor writes them"
    (is (= ["a"] (map #(get % "id") ((f "declarations") "<html data-composition-variables=\"[{&quot;id&quot;:&quot;a&quot;}]\"><head></head></html>")))))
  (testing "none, or broken JSON: no controls"
    (is (= [] ((f "declarations") "<html><head></head></html>")))
    (is (= [] ((f "declarations") "<html data-composition-variables='[{'><head></head></html>")))))

(deftest size-test
  (is (= [1920 1080] ((f "size") html)))
  (is (= [1080 1920] ((f "size") "<html></html>")) "a story without"))

(deftest coerce-test
  (let [[title size mood shout] ((f "declarations") html)
        coerce (f "coerce")]
    (is (= "Wo ist der Kuchen?" (coerce title "Wo ist der Kuchen?")))
    (is (nil? (coerce title (apply str (repeat 2001 "x")))) "not endless")
    (is (= 180 (coerce size "180")) "a whole number stays whole")
    (is (= 1.5 (coerce size "1.5")))
    (is (nil? (coerce size "big")))
    (is (= "loud" (coerce mood "loud")) "an option, plain or with a label")
    (is (nil? (coerce mood "nonsense")) "nothing else")
    (is (true? (coerce shout true)))
    (is (false? (coerce shout "false")))))

(deftest with-runtime-test
  (let [page ((f "with-runtime") html {"title" "</script><b>"})]
    (testing "the values before the runtime, as the renderer sets them"
      (is (str/includes? page "window.__hfVariables = {\"title\":\"<\\/script><b>\"};"))
      (is (< (str/index-of page "__hfVariables") (str/index-of page "hyperframes-runtime.js")))))
  (testing "a page with its own runtime gets the values alone"
    (let [page ((f "with-runtime") "<html><head><script src=\"x/hyperframes-runtime.js\"></script></head></html>" {})]
      (is (= 1 (count (re-seq #"hyperframes-runtime\.js" page))))
      (is (str/includes? page "window.__hfVariables = {};")))))
