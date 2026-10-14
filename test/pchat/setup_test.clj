(ns pchat.setup-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [pchat.i18n :as i18n]
            [pchat.setup :as setup]
            [pchat.view :as view]))

(defn- temp-dir
  []
  (str (java.nio.file.Files/createTempDirectory
        "pchat-setup"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- executable!
  [dir name]
  (let [file (io/file dir name)]
    (spit file "#!/bin/sh\n")
    (.setExecutable file true)))

(deftest pi-state-test
  (let [tools (temp-dir)
        markers (temp-dir)
        env {"PATH" tools}]
    (testing "without pi the markers tell the state"
      (is (= {:state :missing} (setup/pi-state env markers)))
      (spit (io/file markers "pi-installing") "now\n")
      (is (= {:state :installing} (setup/pi-state env markers)))
      (io/delete-file (io/file markers "pi-installing"))
      (spit (io/file markers "pi-failed") "npm ERR! network\n\nfailed (exit 1)\n")
      (is (= {:state :failed :lines ["npm ERR! network" "failed (exit 1)"]} (setup/pi-state env markers))))
    (testing "with pi on the PATH the markers do not matter"
      (executable! tools "pi")
      (is (= {:state :ready} (setup/pi-state env markers))))))

(deftest messages-test
  (testing "the answer to a message sent follows the state"
    (is (nil? (setup/not-ready-message {:state :ready})))
    (is (= [:i18n :setup/pi-installing] (setup/not-ready-message {:state :installing})))
    (is (= [:i18n :setup/pi-failed {:lines "npm ERR! network / failed (exit 1)"}]
           (setup/not-ready-message {:state :failed :lines ["npm ERR! network" "failed (exit 1)"]})))
    (is (= [:i18n :setup/pi-missing] (setup/not-ready-message {:state :missing}))))
  (testing "the person's message stays in the chat, the answer under it"
    (is (= [{:kind :user :text "Make me a card" :images ["/looks/a.png"]}
            {:kind :note :text [:i18n :setup/pi-installing]}]
           (setup/refusal-items "Make me a card" ["/looks/a.png"] [:i18n :setup/pi-installing]))))
  (testing "the banner"
    (is (nil? (view/setup-banner {:pi {:state :ready}})))
    (is (nil? (view/setup-banner nil)) "before the first reading")
    (is (= [:div.banner.setup [:i18n :setup/banner-installing]] (view/setup-banner {:pi {:state :installing}})))
    (is (= [:div.banner.setup.failed [:i18n :setup/pi-failed {:lines "a / b"}]]
           (view/setup-banner {:pi {:state :failed :lines ["a" "b"]}})))))

(deftest api-test
  (let [tools (temp-dir)
        markers (temp-dir)
        db (atom {})
        handle (setup/handle {:db db :env {"PATH" tools} :markers-dir markers})]
    (spit (io/file markers "pi-installing") "now\n")
    (spit (io/file markers "video-tools-installing") "now\n")
    (testing "GET /api/setup reads the machine and keeps the db current"
      (let [{:keys [status headers body]} (:ring/response (setup/api-handler handle {}))]
        (is (= 200 status))
        (is (= "application/json; charset=utf-8" (get headers "Content-Type")))
        (is (= {:pi "installing" :video-tools "installing"} (json/parse-string body true))))
      (is (= :installing (get-in @db [:setup :pi :state])))
      (is (not (setup/ready? (:setup @db)))))
    (testing "pi installed: ready, the video tools still on their way"
      (executable! tools "pi")
      (io/delete-file (io/file markers "pi-installing"))
      (is (= {:pi "ready" :video-tools "installing"}
             (json/parse-string (:body (:ring/response (setup/api-handler handle {}))) true)))
      (is (setup/ready? (:setup @db))))
    (testing "everything there"
      (doseq [name ["ffmpeg" "google-chrome" "hyperframes"]]
        (executable! tools name))
      (io/delete-file (io/file markers "video-tools-installing"))
      (is (= {:pi "ready" :video-tools "ready"}
             (json/parse-string (:body (:ring/response (setup/api-handler handle {}))) true))))))

(deftest appearance-tab-test
  (testing "the Appearance tab names the three schemes for the browser's toggle"
    (let [view (i18n/translate (view/settings-view {:settings-tab :appearance :connections {}}) :en)
          toggle (first (filter #(and (vector? %) (= :ui/theme (first %))) (tree-seq coll? seq view)))
          labels (get-in toggle [1 :ui/labels])]
      (is (= {:system "Like my device" :light "Light" :dark "Dark"} labels))
      (is (some #{"Appearance"} (filter string? (tree-seq coll? seq view))) "the tab is there")))
  (testing "Connections stays the first tab"
    (is (= [:connections :appearance] (mapv :id view/tabs)))))
