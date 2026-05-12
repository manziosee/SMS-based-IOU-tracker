(ns iou-tracker.core
  (:require [ring.adapter.jetty :refer [run-jetty]]
            [environ.core :refer [env]]
            [clojure.tools.logging :as log]
            [iou-tracker.db :as db]
            [iou-tracker.handlers :refer [app]])
  (:gen-class))

(defn -main [& _args]
  (let [port (Integer/parseInt (or (env :port) "3000"))]
    (log/infof "Starting IOU Tracker on port %d" port)
    (db/migrate!)
    (run-jetty app {:port port :join? true})))
