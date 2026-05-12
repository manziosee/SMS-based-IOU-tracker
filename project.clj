(defproject sms-iou-tracker "0.1.0-SNAPSHOT"
  :description "SMS-based IOU tracker for shared expenses — no bank account required"
  :url "https://github.com/yourusername/sms-iou-tracker"
  :license {:name "MIT"}

  :dependencies
  [[org.clojure/clojure "1.11.1"]

   ;; HTTP server
   [ring/ring-core "1.11.0"]
   [ring/ring-jetty-adapter "1.11.0"]
   [ring/ring-defaults "0.3.4"]

   ;; Routing
   [compojure "1.7.1"]

   ;; Database
   [com.github.seancorfield/next.jdbc "1.3.909"]
   [org.xerial/sqlite-jdbc "3.45.1.0"]

   ;; HTML templating
   [hiccup "1.0.5"]

   ;; JSON
   [cheshire "5.13.0"]

   ;; Environment variables
   [environ "1.2.0"]

   ;; HTTP client (for outbound Twilio calls if needed)
   [clj-http "3.12.3"]

   ;; Logging
   [org.clojure/tools.logging "1.3.0"]
   [ch.qos.logback/logback-classic "1.4.14"]]

  :plugins [[lein-environ "1.2.0"]]

  :main ^:skip-aot iou-tracker.core
  :target-path "target/%s"

  :profiles
  {:uberjar {:aot :all
             :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}
   :dev     {:dependencies [[ring/ring-mock "0.4.0"]]
             :env {:port "3000"
                   :db-path "iou_tracker_dev.db"}}})
