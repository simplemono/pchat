(ns pchat.render-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [pchat.render :as render]))

(defn- temp-dir
  []
  (str (java.nio.file.Files/createTempDirectory
        "pchat-render"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest video-tools-test
  (let [tools (temp-dir)
        markers (temp-dir)
        executable! (fn [name]
                      (let [file (io/file tools name)]
                        (spit file "#!/bin/sh\n")
                        (.setExecutable file true)))]
    (testing "an empty PATH misses every tool, a Chrome by any of its names counts"
      (is (= ["ffmpeg" "google-chrome" "hyperframes"] (render/missing-video-tools {"PATH" tools})))
      (executable! "ffmpeg")
      (executable! "chromium")
      (is (= ["hyperframes"] (render/missing-video-tools {"PATH" tools})))
      (executable! "hyperframes")
      (is (empty? (render/missing-video-tools {"PATH" tools}))))
    (testing "the message follows the operator's markers"
      (is (nil? (render/not-ready-message [] {:state :absent})) "tools there: nothing to say")
      (is (= "The video tools are not installed on this machine (ffmpeg), so no video: the operator installs them."
             (render/not-ready-message ["ffmpeg"] (render/video-tools-state markers))))
      (spit (io/file markers "video-tools-installing") "now\n")
      (is (= {:state :installing} (render/video-tools-state markers)))
      (is (= "The video tools are still being installed, try again in a few minutes."
             (render/not-ready-message ["ffmpeg"] (render/video-tools-state markers))))
      (io/delete-file (io/file markers "video-tools-installing"))
      (spit (io/file markers "video-tools-failed") "failed (exit 100): sudo apt-get install: E: Unable to locate package\n")
      (is (= "The video tools could not be installed: failed (exit 100): sudo apt-get install: E: Unable to locate package"
             (render/not-ready-message ["ffmpeg"] (render/video-tools-state markers)))))
    (testing "start! refuses without a process while the tools install, the reason as the render's error"
      (let [db (atom {})
            handle (render/handle {:db db :chats-dir (temp-dir) :env {"PATH" (temp-dir)} :markers-dir markers})]
        (is (= "card" (render/start! handle "c" "card")))
        (is (= 1 (get-in @db [:chats "c" :renders "card" :exit])))
        (is (= "The video tools could not be installed: failed (exit 100): sudo apt-get install: E: Unable to locate package"
               (get-in @db [:chats "c" :renders "card" :error])))
        (is (not (render/running? handle "c" "card")))))))

(deftest on-finish-test
  (let [tools (temp-dir)
        chats (temp-dir)
        bb (io/file tools "bb")
        finished (promise)]
    (.mkdirs (io/file chats "c"))
    (doseq [name ["ffmpeg" "google-chrome" "hyperframes"]]
      (let [file (io/file tools name)]
        (spit file "#!/bin/sh\n")
        (.setExecutable file true)))
    (spit bb "#!/bin/sh\necho 'frame 1/2'\necho 'frame 2/2'\nexit 0\n")
    (.setExecutable bb true)
    (let [db (atom {})
          handle (render/handle {:db db
                                 :chats-dir chats
                                 :bb (str bb)
                                 :env {"PATH" tools}
                                 :markers-dir (temp-dir)
                                 :on-finish (fn [id name] (deliver finished [id name (get-in @db [:chats id :renders name])]))})]
      (is (= "card" (render/start! handle "c" "card")))
      (let [[id name render] (deref finished 10000 [:timeout nil nil])]
        (is (= ["c" "card"] [id name]) "told once the render ended, with the chat")
        (is (= 0 (:exit render)) "with its exit in the db already")
        (is (nil? (:progress render)))))))
