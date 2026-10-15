(ns pchat.env
  "The environment of pchat: the process's own, and under it the lines
  of `.env` and `.env.local` in the working directory, read the way
  `bb start` and the sprite's start script read them (`KEY=value` a
  line, quotes around the value and `export` in front allowed, `#` a
  comment, `.env.local` over `.env`), so that a hand start, `bb -m
  pchat.main` or `bb pchat.jar` next to the file, finds the key too.
  The process's own variables win."
  (:refer-clojure :exclude [get])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn parse
  "The map of a dotenv text."
  [text]
  (into {}
        (for [line (str/split-lines (str text))
              :let [line (str/trim (str/replace line #"^export\s+" ""))]
              :when (and (seq line)
                         (not (str/starts-with? line "#"))
                         (str/includes? line "="))
              :let [[k v] (str/split line #"=" 2)
                    v (str/trim (str v))
                    v (if (and (<= 2 (count v))
                               (#{\" \'} (first v))
                               (= (first v) (last v)))
                        (subs v 1 (dec (count v)))
                        v)]]
          [(str/trim k) v])))

(def file-names
  [".env" ".env.local"])

(defn files
  "The variables of the files in `dir`, the later file over the earlier."
  [dir]
  (reduce (fn [vars name]
            (let [file (io/file dir name)]
              (if (.isFile file)
                (merge vars (parse (slurp file)))
                vars)))
          {}
          file-names))

(def ^:private here
  (delay (files ".")))

(defn get
  "The variable: the process's own when it is set and not empty, else
  the files', else `default`."
  ([name]
   (get name nil))
  ([name default]
   (or (not-empty (System/getenv name))
       (clojure.core/get @here name)
       default)))

(defn below
  "The files' variables the process does not have: what the processes
  pchat starts get on top of their inherited environment."
  []
  (into {}
        (remove (fn [[k _]] (not-empty (System/getenv k))))
        @here))
