(ns pchat.skills
  "The tasks a chat can be for: the skills of an agentitos checkout,
  `PCHAT_AGENTITOS`, one folder with a SKILL.md each
  (https://agentskills.io/specification). The person picks one for a new
  chat; its pi loads that skill as its task and knows the others by name
  and description. The optional `metadata` of a SKILL.md is the
  onboarding: `title`, `summary`, `ask` (the first thing the chat says),
  `placeholder` and `inputs` (\"video,audio\"), each with a `-de`
  variant for German."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [pchat.actions :as actions]))

(defn- scalar
  "A YAML scalar as a string: double quotes as JSON, single quotes with
  '' for a quote, else as it stands."
  [s]
  (let [s (str/trim s)]
    (cond
      (and (str/starts-with? s "\"") (str/ends-with? s "\"") (< 1 (count s)))
      (json/parse-string s)

      (and (str/starts-with? s "'") (str/ends-with? s "'") (< 1 (count s)))
      (str/replace (subs s 1 (dec (count s))) "''" "'")

      :else s)))

(defn frontmatter
  "The frontmatter of a SKILL.md and the text after it:
  `{:name :description :metadata {\"title\" ...} :body ...}`. Reads the
  part of YAML skills use: `key: value` on top, one level of `key:
  value` under `metadata:`."
  [text]
  (let [[_ head body] (re-find #"(?s)^---\r?\n(.*?)\r?\n---\r?\n?(.*)$" (str text))]
    (when head
      (loop [[line & more] (str/split-lines head)
             section nil
             out {:metadata {}}]
        (cond
          (nil? line)
          (-> out
              (update :name #(some-> % not-empty))
              (assoc :body (str/trim body)))

          (or (str/blank? line) (str/starts-with? (str/trim line) "#"))
          (recur more section out)

          :else
          (let [indented? (re-find #"^\s" line)
                [k v] (str/split (str/trim line) #":\s*" 2)]
            (cond
              (and indented? (= "metadata" section))
              (recur more section (assoc-in out [:metadata k] (scalar (or v ""))))

              indented?
              (recur more section out)

              (str/blank? v)
              (recur more k out)

              :else
              (recur more nil (assoc out (keyword k) (scalar v))))))))))

(defn checkout
  "The agentitos checkout named by `PCHAT_AGENTITOS`, or nil."
  []
  (when-let [dir (not-empty (System/getenv "PCHAT_AGENTITOS"))]
    (when (fs/directory? dir)
      (str (fs/canonicalize dir)))))

(defn load-all
  "The skills of the folders in `dir` that have a SKILL.md with a name,
  by name: `[{:name :description :dir :file :metadata :body :actions}]`,
  `:actions` from the folder's actions.json (see `pchat.actions`)."
  [dir]
  (if-not dir
    []
    (->> (fs/list-dir dir)
         (filter #(fs/regular-file? (fs/file % "SKILL.md")))
         (keep (fn [folder]
                 (let [file (fs/file folder "SKILL.md")
                       skill (frontmatter (slurp file))]
                   (when (:name skill)
                     (assoc skill
                            :dir (str folder)
                            :file (str file)
                            :actions (actions/read-actions (str folder)))))))
         (sort-by :name)
         (vec))))

(defn by-name
  [skills name]
  (some #(when (= name (:name %)) %) skills))

(defn text
  "A text of the onboarding in the language of the page, English when
  the skill has none in it; for `:title` the name of the skill at
  last."
  [{:keys [metadata name]} k locale]
  (let [k (clojure.core/name k)]
    (or (not-empty (get metadata (str k "-" (clojure.core/name (or locale :en)))))
        (not-empty (get metadata k))
        (when (= "title" k) name))))

(defn inputs
  "What the skill asks to attach: #{:video :audio :image}."
  [skill]
  (into #{}
        (comp (map str/trim) (remove str/blank?) (map keyword))
        (str/split (str (get-in skill [:metadata "inputs"])) #",")))

(defn paths
  "The folders pi gets as `--skill`: all of them, so that a chat knows
  every skill by name and description, whatever its task."
  [skills]
  (mapv :dir skills))

(defn prompt
  "The part of the system prompt about skills: how a skill's work
  becomes something the person sees, and for a chat with a task, that
  skill in full, the others named."
  [skills task]
  (when (seq skills)
    (let [skill (by-name skills task)]
      (str "Skills are packaged know-how for one kind of work: a SKILL.md with the steps, and the scripts it names in its folder. "
           "Their work becomes something the person sees like everything else: a HyperFrames composition goes into an artifact made with `bin/new <name> hyperframes` (the skill writes into `artifacts/<name>/` and its `composition/`), a file into `artifacts/`, and `bin/render <name>` makes the video. "
           "A picture under `artifacts/<name>/` is shown as `/artifacts/<name>/…`; offer a choice as buttons in a ```hiccup block. "
           "Clips the person attaches are under `uploads/`. Never change the files of a skill: they are shared by every chat."
           (if skill
             (str "\n\nThis chat is for one task, chosen by the person when they started it: " (:name skill) ". "
                  "Follow the skill below for it. The other skills are listed further down by name and description; "
                  "use one only when it serves this task, and when the person wants something else entirely, say that a new chat with that task does it better."
                  "\n\n<skill name=\"" (:name skill) "\" location=\"" (:file skill) "\">\n"
                  "`<skill>` and the relative paths in it mean " (:dir skill) ".\n\n"
                  (:body skill)
                  "\n</skill>")
             "\n\nThis chat has no fixed task. When the person's wish matches a skill listed below, read its SKILL.md and follow it.")))))
