;; bb test
(require '[clojure.test :as t])
(require 'pchat.chat-test 'pchat.session-test 'pchat.artifacts-test 'pchat.looks-test)
(let [{:keys [fail error]} (t/run-tests 'pchat.chat-test 'pchat.session-test 'pchat.artifacts-test 'pchat.looks-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
