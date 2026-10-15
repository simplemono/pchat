(ns pchat.env-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [pchat.env :as env]))

(deftest parse-test
  (testing "a line a variable, quotes and export allowed, comments and blanks skipped"
    (is (= {"A" "1" "B" "two words" "C" "it's" "D" "x=y" "E" ""}
           (env/parse "A=1\n# a comment\n\nexport B=\"two words\"\nC='it''s'\n  D = x=y \nE=\nnot a line"))))
  (testing "a lone quote stays"
    (is (= {"A" "\""} (env/parse "A=\"")))
    (is (= {"A" "\"x'"} (env/parse "A=\"x'")))))

(deftest files-test
  (let [dir (str (fs/create-temp-dir {:prefix "pchat-env"}))]
    (is (= {} (env/files dir)) "no files, no variables")
    (spit (io/file dir ".env") "OPENROUTER_API_KEY=\"sk-or-x\"\nPCHAT_MODEL=a\n")
    (is (= {"OPENROUTER_API_KEY" "sk-or-x" "PCHAT_MODEL" "a"} (env/files dir)))
    (spit (io/file dir ".env.local") "PCHAT_MODEL=b\n")
    (is (= "b" (get (env/files dir) "PCHAT_MODEL")) ".env.local over .env")))

(deftest get-test
  (is (= (System/getenv "PATH") (env/get "PATH")) "the process's own")
  (is (= "fallback" (env/get "PCHAT_NO_SUCH_VARIABLE_" "fallback")))
  (is (nil? (env/get "PCHAT_NO_SUCH_VARIABLE_")))
  (is (not (contains? (env/below) "PATH")) "what the process has is not repeated"))
