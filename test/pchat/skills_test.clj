(ns pchat.skills-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [pchat.chat :as chat]
            [pchat.i18n :as i18n]
            [pchat.skills :as skills]
            [pchat.view :as view]))

(def skill-md
  "---
name: kinetic-lyrics
description: Turn a clip of people talking into a kinetic short: every word big.
license: MIT
metadata:
  title: \"Kinetic shorts\"
  title-de: \"Kinetic Shorts\"
  ask: \"Attach a clip of people talking: \\\"a podcast\\\".\"
  ask-de: 'Hänge einen Clip an, it''s fine.'
  inputs: \"video,audio\"
---

# kinetic-lyrics

`<skill>/tools/short.mjs new <dir> <clip>`
")

(deftest frontmatter-test
  (let [{:keys [name description metadata body]} (skills/frontmatter skill-md)]
    (is (= "kinetic-lyrics" name))
    (testing "a colon in a plain value stays"
      (is (= "Turn a clip of people talking into a kinetic short: every word big." description)))
    (testing "double quotes as JSON, single quotes with '' for a quote"
      (is (= "Attach a clip of people talking: \"a podcast\"." (get metadata "ask")))
      (is (= "Hänge einen Clip an, it's fine." (get metadata "ask-de"))))
    (is (str/starts-with? body "# kinetic-lyrics")))
  (is (nil? (skills/frontmatter "# no frontmatter"))))

(defn- checkout!
  "A folder with one skill and one folder that is none."
  []
  (let [dir (fs/create-temp-dir)]
    (fs/create-dirs (fs/file dir "kinetic-lyrics"))
    (spit (fs/file dir "kinetic-lyrics" "SKILL.md") skill-md)
    (fs/create-dirs (fs/file dir "notes"))
    (str dir)))

(deftest load-test
  (let [dir (checkout!)
        [skill :as all] (skills/load-all dir)]
    (is (= ["kinetic-lyrics"] (map :name all)))
    (is (= (str (fs/file dir "kinetic-lyrics")) (:dir skill)))
    (is (= [] (skills/load-all nil)))
    (testing "the onboarding in the language of the page, English when there is none"
      (is (= "Kinetic Shorts" (skills/text skill :title :de)))
      (is (= "Kinetic shorts" (skills/text skill :title :en)))
      (is (nil? (skills/text skill :placeholder :de)))
      (is (= #{:video :audio} (skills/inputs skill))))
    (testing "the prompt: the chat's skill in full, else only the contract"
      (let [task (skills/prompt all "kinetic-lyrics")
            free (skills/prompt all :free)]
        (is (str/includes? task "<skill name=\"kinetic-lyrics\""))
        (is (str/includes? task (str "`<skill>` and the relative paths in it mean " (:dir skill))))
        (is (str/includes? task "short.mjs new"))
        (is (not (str/includes? free "short.mjs")))
        (is (str/includes? free "bin/new <name> hyperframes"))
        (is (nil? (skills/prompt [] "kinetic-lyrics")))))))

(defn- texts
  [hiccup]
  (filter string? (tree-seq coll? seq hiccup)))

(deftest picker-test
  (let [all (skills/load-all (checkout!))
        page (fn [chat] (i18n/translate (view/page {:chats {"a" chat} :skills all} {:id "a" :locale :de}) :de))]
    (testing "a new chat shows the tasks and no composer"
      (let [shown (page chat/empty-chat)]
        (is (some #{"Kinetic Shorts"} (texts shown)))
        (is (some #{"Freier Chat"} (texts shown)))
        (is (not (some #(= :ui/composer %) (tree-seq coll? seq shown))))))
    (testing "a chat with a task opens with what to bring and a button for it"
      (let [shown (page (assoc chat/empty-chat :task "kinetic-lyrics"))]
        (is (some #{"Hänge einen Clip an, it's fine."} (texts shown)))
        (is (some #{"Video oder Audio anhängen"} (texts shown)))
        (is (some #(= :ui/composer %) (tree-seq coll? seq shown)))))
    (testing "a chat that has started is never asked again, nor is a free one"
      (is (not (some #{"Freier Chat"} (texts (page (assoc chat/empty-chat :items [{:kind :user :text "Hi"}]))))))
      (is (not (some #{"Freier Chat"} (texts (page (assoc chat/empty-chat :task :free)))))))))
