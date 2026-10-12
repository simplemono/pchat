;; bb test
(require '[clojure.test :as t])
(require 'pchat.chat-test 'pchat.session-test 'pchat.artifacts-test 'pchat.looks-test 'pchat.projects-test 'pchat.i18n-test 'pchat.genui-test 'pchat.share-test 'pchat.hooks-test 'pchat.mcp-test)
(let [{:keys [fail error]} (t/run-tests 'pchat.chat-test 'pchat.session-test 'pchat.artifacts-test 'pchat.looks-test 'pchat.projects-test 'pchat.i18n-test 'pchat.genui-test 'pchat.share-test 'pchat.hooks-test 'pchat.mcp-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
