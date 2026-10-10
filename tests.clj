;; bb test
(require '[clojure.test :as t])
(require 'pchat.chat-test)
(let [{:keys [fail error]} (t/run-tests 'pchat.chat-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
