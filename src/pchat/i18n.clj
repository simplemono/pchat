(ns pchat.i18n
  "The texts of the page in the language of the person. The page is hiccup
  with an m1p reference tuple where a text goes, `[:i18n :chat/new]` or
  `[:i18n :tool/other {:name \"fetch\"}]`, and `translate` folds the
  dictionary of a locale into it before the frame leaves the server. The
  dictionaries are EDN files under `pchat/i18n/`, one per locale, English
  the reference; `pchat.i18n-test` checks that they have the same keys and
  that the source uses only keys they have.

  The locale of a connection comes from its request: `?lang=de` on the
  page beats the browser's Accept-Language, English when neither names a
  language we have."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [m1p.core :as m1p]))

(def locales
  [:en :de])

(def default-locale
  :en)

(defn- read-dictionary
  [locale]
  (edn/read-string (slurp (io/resource (str "pchat/i18n/" (name locale) ".edn")))))

(def dictionaries
  "The prepared dictionary per locale."
  (into {}
        (for [locale locales]
          [locale (m1p/prepare-dictionary (read-dictionary locale))])))

(defn- accepted-languages
  "The primary language subtags of an Accept-Language header, the
  preferred one first."
  [header]
  (->> (str/split (str header) #",")
       (map str/trim)
       (remove str/blank?)
       (map (fn [entry]
              (let [[tag & params] (map str/trim (str/split entry #";"))
                    q (some #(when (str/starts-with? % "q=")
                               (parse-double (subs % 2)))
                            params)]
                [(str/lower-case (first (str/split tag #"-"))) (or q 1.0)])))
       (sort-by second >)
       (map first)))

(defn- query-lang
  [query-string]
  (some-> (re-find #"(?:^|&)lang=([A-Za-z-]+)" (str query-string))
          second
          str/lower-case))

(defn locale
  "The locale of a ring request, see the namespace."
  [request]
  (let [known (set (map name locales))
        wanted (cons (query-lang (:query-string request))
                     (accepted-languages (get-in request [:headers "accept-language"])))]
    (or (some-> (some known wanted) keyword)
        default-locale)))

(defn- missing-key
  "The English text for a key the dictionary of the locale lacks, the key
  itself when English lacks it too, so that the gap shows on the page."
  [opt params k]
  (m1p/lookup (assoc opt :on-missing-dictionary-key (fn [_ _ k] (str k)))
              (get dictionaries default-locale)
              k
              params))

(defn translate
  "The hiccup with every `[:i18n k params]` replaced by the text of
  `locale`."
  [hiccup locale]
  (m1p/interpolate hiccup {:dictionaries {:i18n (get dictionaries locale (get dictionaries default-locale))}
                           :on-missing-dictionary-key missing-key}))

(comment
  (translate [:button [:i18n :chat/new]] :de)
  (translate [:p [:i18n :tool/other {:name "fetch"}]] :en)
  (locale {:headers {"accept-language" "de-DE,de;q=0.9,en;q=0.8"}})
  (locale {:query-string "lang=en" :headers {"accept-language" "de"}})
  )
