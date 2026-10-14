(ns pchat.update-test
  "The update of pchat against a stub of GitHub's release pages and a
  fake babashka: the lookup through the redirect, the banner, the
  download with its check-run, the wait for idle, the exit code."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as server]
            [pchat.setup :as setup]
            [pchat.update :as update]
            [pchat.view :as view]))

(defn- temp-dir
  []
  (str (java.nio.file.Files/createTempDirectory
        "pchat-update"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- fake-bb
  "A babashka that answers `version` to anything."
  [dir version]
  (let [file (io/file dir "bb")]
    (spit file (str "#!/bin/sh\necho " version "\n"))
    (.setExecutable file true)
    (str file)))

(defn- wait-for
  [pred]
  (loop [n 0]
    (cond
      (pred) true
      (< n 100) (do (Thread/sleep 50) (recur (inc n)))
      :else false)))

(defn- releases
  "GitHub's release pages, as far as the update looks at them."
  [requests]
  (fn [{:keys [request-method uri] :as request}]
    (swap! requests conj [request-method uri])
    (cond
      (= "/releases/latest" uri)
      {:status 302 :headers {"Location" "http://stub/releases/tag/v0.1.4"} :body ""}

      (= "/releases/download/v0.1.4/pchat.jar" uri)
      {:status 200 :headers {"Content-Type" "application/octet-stream"} :body "the new jar"}

      :else
      {:status 404 :body "no"})))

(defmacro with-stub
  [[base requests] & body]
  `(let [~requests (atom [])
         stop# (server/run-server (releases ~requests) {:port 0 :legacy-return-value? false})
         ~base (str "http://127.0.0.1:" (server/server-port stop#))]
     (try
       ~@body
       (finally
         (server/server-stop! stop#)))))

(deftest versions-test
  (is (= "0.1.4" (update/version-of-location "https://github.com/storrito/pchat/releases/tag/v0.1.4")))
  (is (= "0.1.4" (update/version-of-location "/releases/tag/0.1.4?x=1")))
  (is (nil? (update/version-of-location nil)))
  (is (update/newer? "0.1.4" "0.1.3"))
  (is (update/newer? "0.2.0" "0.1.10"))
  (is (not (update/newer? "0.1.3" "0.1.3")))
  (is (not (update/newer? "0.1.3" "0.1.4")))
  (is (update/newer? "0.1.4" "0.1.3-2-gabc") "a build after a tag is older than the next tag")
  (is (= "https://github.com/storrito/pchat/releases/download/v0.1.4/pchat.jar"
         (update/download-url "https://github.com/storrito/pchat/releases/latest" "0.1.4"))))

(deftest new-jar-file-test
  (testing "the download keeps the .jar ending, which babashka runs a file by"
    (is (= "pchat.new.jar" (.getName (update/new-jar-file (io/file "/home/sprite/pchat/pchat.jar")))))
    (is (= "/home/sprite/pchat/pchat.new.jar" (str (update/new-jar-file "/home/sprite/pchat/pchat.jar"))))
    (is (= "app.new.jar" (.getName (update/new-jar-file "app"))))))

(deftest running-jar-test
  (testing "only PCHAT_JAR names a jar to replace; without it there is no banner"
    (is (nil? (update/running-jar nil)))
    (is (nil? (update/running-jar "")))
    (is (= "pchat.jar" (.getName (update/running-jar "/home/sprite/pchat/pchat.jar"))))))

(deftest check-test
  (with-stub [base requests]
    (testing "a newer release is offered when a jar runs"
      (let [db (atom {})
            handle (update/handle {:db db :latest-url (str base "/releases/latest") :current "0.1.3" :jar (io/file "x.jar")})]
        (is (= "0.1.4" (update/check! handle)))
        (is (= {:current "0.1.3" :available "0.1.4" :offered true} (:update @db)))
        (is (= [[:head "/releases/latest"]] @requests) "one HEAD, the redirect not followed")))
    (testing "the same version, or no jar: nothing offered"
      (let [db (atom {})]
        (update/check! (update/handle {:db db :latest-url (str base "/releases/latest") :current "0.1.4" :jar (io/file "x.jar")}))
        (is (false? (get-in @db [:update :offered]))))
      (let [db (atom {})]
        (update/check! (update/handle {:db db :latest-url (str base "/releases/latest") :current "0.1.3" :jar nil}))
        (is (false? (get-in @db [:update :offered])))))
    (testing "an address that does not answer keeps the db as it is"
      (let [db (atom {:update {:current "0.1.3" :available "0.1.4" :offered true}})]
        (is (nil? (update/check! (update/handle {:db db :latest-url "http://127.0.0.1:1/releases/latest" :current "0.1.3" :jar (io/file "x.jar")}))))
        (is (= "0.1.4" (get-in @db [:update :available])))))))

(deftest update-test
  (with-stub [base _requests]
    (let [dir (temp-dir)
          jar (io/file dir "pchat.jar")
          _ (spit jar "the old jar")
          idle (atom false)
          exits (atom [])
          db (atom {})
          handle (update/handle {:db db
                                 :latest-url (str base "/releases/latest")
                                 :current "0.1.3"
                                 :jar jar
                                 :bb (fake-bb dir "0.1.4")
                                 :idle? (fn [] @idle)
                                 :exit! (fn [code] (swap! exits conj code))})]
      (update/check! handle)
      (testing "the click: downloaded and checked, then waiting for the work to end"
        (is (= :started (update/update! handle)))
        (is (wait-for #(= :ready (get-in @db [:update :state]))))
        (is (empty? @exits) "nothing ends while a chat works")
        (is (= "the old jar" (slurp jar)) "the running jar is untouched")
        (is (= "the new jar" (slurp (io/file dir "pchat.new.jar")))))
      (testing "a second click while that runs does nothing"
        (is (nil? (update/update! handle))))
      (testing "idle: the new jar in place, the process ends with 75"
        (reset! idle true)
        (is (wait-for #(seq @exits)))
        (is (= [75] @exits))
        (is (= "the new jar" (slurp jar)))
        (is (not (.exists (io/file dir "pchat.new.jar"))))))))

(deftest check-run-test
  (with-stub [base _requests]
    (let [dir (temp-dir)
          jar (io/file dir "pchat.jar")
          _ (spit jar "the old jar")
          exits (atom [])
          db (atom {})
          handle (update/handle {:db db
                                 :latest-url (str base "/releases/latest")
                                 :current "0.1.3"
                                 :jar jar
                                 :bb (fake-bb dir "0.0.1")
                                 :exit! (fn [code] (swap! exits conj code))})]
      (update/check! handle)
      (update/update! handle)
      (is (wait-for #(= :failed (get-in @db [:update :state]))))
      (is (= "the new jar answers 0.0.1, not 0.1.4" (get-in @db [:update :error])))
      (is (empty? @exits))
      (is (= "the old jar" (slurp jar)))
      (is (not (.exists (io/file dir "pchat.new.jar"))) "the rejected download is gone")
      (testing "try again"
        (is (= :started (update/update! handle)))
        (is (wait-for #(= :failed (get-in @db [:update :state]))))))))

(deftest banner-test
  (is (nil? (view/update-banner nil)))
  (is (nil? (view/update-banner {:current "0.1.3" :available "0.1.3" :offered false})))
  (is (= [:div.banner.update [:i18n :update/available {:version "0.1.4"}] " "
          [:button.link {:on {:click [[:data/command {:command/kind :update/start}]]}} [:i18n :update/button]]]
         (view/update-banner {:current "0.1.3" :available "0.1.4" :offered true})))
  (is (= [:div.banner.update [:i18n :update/downloading {:version "0.1.4"}]]
         (view/update-banner {:available "0.1.4" :offered true :state :downloading})))
  (is (= [:div.banner.update [:i18n :update/waiting {:version "0.1.4"}]]
         (view/update-banner {:available "0.1.4" :offered true :state :ready})))
  (is (= [:i18n :update/failed {:error "no"}]
         (second (view/update-banner {:available "0.1.4" :offered true :state :failed :error "no"})))))

(deftest setup-fields-test
  (let [tools (temp-dir)
        handle (setup/handle {:db (atom {}) :env {"PATH" tools} :markers-dir (temp-dir)
                              :extra (fn [] {:version "0.1.4" :idle true})})]
    (is (= {:pi "missing" :video-tools "missing" :version "0.1.4" :idle true}
           (json/parse-string (:body (:ring/response (setup/api-handler handle {}))) true)))))
