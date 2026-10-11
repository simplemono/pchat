(ns pchat.i18n-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [m1p.analysis :as analysis]
            [m1p.validation :as validation]
            [pchat.i18n :as i18n]))

(deftest parity-test
  (let [dicts i18n/dictionaries
        problems (concat (validation/find-non-kw-keys dicts)
                         (validation/find-unqualified-keys dicts)
                         (validation/find-missing-keys dicts)
                         (validation/find-misplaced-interpolations dicts)
                         (validation/find-type-discrepancies dicts)
                         (validation/find-interpolation-discrepancies dicts)
                         (validation/find-fn-get-param-discrepancies dicts))]
    (is (empty? problems) (validation/format-report dicts problems))))

(deftest sources-test
  (testing "the source uses only keys the dictionaries have, with the params they take, and every key"
    (let [problems (analysis/find-interpolation-problems i18n/dictionaries
                                                         (file-seq (io/file "src/pchat"))
                                                         {:dictionary-ids #{:i18n}})]
      (is (empty? problems) (analysis/format-problems problems)))))

(deftest locale-test
  (is (= :en (i18n/locale {})))
  (is (= :de (i18n/locale {:headers {"accept-language" "de-DE,de;q=0.9,en;q=0.8"}})))
  (is (= :en (i18n/locale {:headers {"accept-language" "de;q=0.5,en;q=0.9"}})))
  (is (= :en (i18n/locale {:headers {"accept-language" "fr-FR,fr;q=0.9,*;q=0.1"}})))
  (testing "?lang= on the page wins"
    (is (= :de (i18n/locale {:query-string "lang=de" :headers {"accept-language" "en"}})))
    (is (= :en (i18n/locale {:query-string "token=x&lang=en" :headers {"accept-language" "de"}})))
    (is (= :de (i18n/locale {:query-string "lang=fr" :headers {"accept-language" "de"}}))))
  (testing "a command posted from a page with ?lang= follows the page"
    (is (= :de (i18n/locale {:headers {"referer" "http://x/?token=a&lang=de" "accept-language" "en"}})))
    (is (= :en (i18n/locale {:headers {"referer" "http://x/" "accept-language" "en"}})))))

(deftest translate-test
  (is (= [:ul {:lang "de"} [:li "Neuer Chat"] [:li "fetch benutzt"]]
         (i18n/translate [:ul {:lang "de"}
                          [:li [:i18n :chat/new]]
                          [:li [:i18n :tool/other {:name "fetch"}]]]
                         :de)))
  (testing "a locale we do not have reads as English"
    (is (= "New chat" (i18n/translate [:i18n :chat/new] :fr)))))
