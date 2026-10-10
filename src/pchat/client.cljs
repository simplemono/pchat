(ns pchat.client
  "The browser bundle: the rframes runtime with rchat's composer (text,
  pasted or dropped images) and canvas aliases."
  (:require [rchat.client :as rchat]
            [rframes.client :as rframes]))

(defn init!
  []
  (rframes/init! {:register rchat/register}))
