;; bb test
(require '[clojure.test :as t])
(require 'pchat.chat-test 'pchat.session-test 'pchat.artifacts-test 'pchat.looks-test 'pchat.projects-test 'pchat.i18n-test 'pchat.genui-test 'pchat.share-test 'pchat.hooks-test 'pchat.mcp-test 'pchat.render-test 'pchat.setup-test 'pchat.update-test 'pchat.nrepl-server-test 'pchat.uploads-test 'pchat.skills-test 'pchat.chats-test 'pchat.migrate-test 'pchat.jobs-test)
(let [{:keys [fail error]} (t/run-tests 'pchat.chat-test 'pchat.session-test 'pchat.artifacts-test 'pchat.looks-test 'pchat.projects-test 'pchat.i18n-test 'pchat.genui-test 'pchat.share-test 'pchat.hooks-test 'pchat.mcp-test 'pchat.render-test 'pchat.setup-test 'pchat.update-test 'pchat.nrepl-server-test 'pchat.uploads-test 'pchat.skills-test 'pchat.chats-test 'pchat.migrate-test 'pchat.jobs-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
