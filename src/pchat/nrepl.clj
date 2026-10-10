(ns pchat.nrepl
  "A small nREPL client: one eval, the result as a map. For the artifact
  projects, which run an nREPL server of their own."
  (:require [bencode.core :as bencode]))

(defn- text
  [value]
  (when value
    (String. ^bytes value "UTF-8")))

(defn eval!
  "Evaluates `code` on the nREPL server at `port`, in namespace `ns` when
  given, and returns {:value :out :err :ex}, strings or nil."
  ([port code]
   (eval! port code nil))
  ([port code ns]
   (with-open [socket (java.net.Socket. "127.0.0.1" (int port))]
    (.setSoTimeout socket 30000)
    (let [out (java.io.BufferedOutputStream. (.getOutputStream socket))
          in (java.io.PushbackInputStream. (.getInputStream socket))]
      (bencode/write-bencode out (cond-> {"op" "eval" "code" (str code) "id" "1"}
                                   ns (assoc "ns" (str ns))))
      (.flush out)
      (loop [result {}]
        (let [message (bencode/read-bencode in)
              result (cond-> result
                       (get message "value") (assoc :value (text (get message "value")))
                       (get message "out") (update :out str (text (get message "out")))
                       (get message "err") (update :err str (text (get message "err")))
                       (get message "ex") (assoc :ex (text (get message "ex"))))
              done? (some #(= "done" (text %)) (get message "status"))]
          (if done?
            result
            (recur result))))))))
