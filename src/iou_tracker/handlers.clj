(ns iou-tracker.handlers
  (:require [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [ring.util.response :as resp]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [clojure.tools.logging :as log]
            [environ.core :refer [env]]
            [cheshire.core :as json]
            [iou-tracker.db :as db]
            [iou-tracker.sms :as sms]
            [iou-tracker.views :as views]))

;; ---------------------------------------------------------------------------
;; Response helpers
;; ---------------------------------------------------------------------------

(defn- base-url [request]
  (let [scheme (name (:scheme request))
        host   (get-in request [:headers "host"])]
    (str scheme "://" host)))

(defn- xml-response [body]
  {:status 200 :headers {"Content-Type" "text/xml; charset=utf-8"} :body body})

(defn- html-response [body]
  (-> (resp/response body)
      (resp/content-type "text/html; charset=utf-8")))

(defn- json-response
  ([body]         (json-response 200 body))
  ([status body]  {:status  status
                   :headers {"Content-Type" "application/json; charset=utf-8"}
                   :body    (json/generate-string body)}))

(defn- parse-json-body [request]
  (when-let [body (:body request)]
    (try (json/parse-string (slurp body) true)
         (catch Exception _ nil))))

;; ---------------------------------------------------------------------------
;; SMS webhook — POST /sms
;; Twilio posts form params: From, To, Body
;; ---------------------------------------------------------------------------

(defn handle-sms [request]
  (let [from (get-in request [:params :From])
        body (get-in request [:params :Body] "")]
    (log/infof "SMS from %s: %s" from body)
    (xml-response
      (sms/twiml-response
        (case (:cmd (sms/parse-command body))

          :record-debt
          (let [{:keys [creditor amount description]} (sms/parse-command body)
                debt-id (db/record-debt! from creditor amount description)
                total   (reduce + (map :amount (db/pending-debts-to from creditor)))]
            (str "Recorded: You owe " creditor
                 " " (sms/format-amount amount)
                 " for " description
                 " (ID: " debt-id ").\n"
                 "Total owed to " creditor ": " (sms/format-amount total)))

          :balance
          (sms/build-balance-text
            (db/all-pending-debts from)
            (db/pending-credits from))

          :settle
          (let [{:keys [name]} (sms/parse-command body)]
            (if-let [token (db/create-receipt! from name)]
              (sms/build-settle-text name token (base-url request))
              (str "You have no pending debts to " name ".")))

          :history
          (let [{:keys [name]} (sms/parse-command body)
                debts   (if name (db/debt-history from name)   (db/debt-history from))
                credits (if name (db/credit-history from name) (db/credit-history from))]
            (sms/build-history-text debts credits name))

          :cancel
          (let [{:keys [debt-id]} (sms/parse-command body)
                target  (or debt-id (:id (db/get-last-pending-debt from)))]
            (cond
              (nil? target)         "No pending debts to cancel."
              (db/cancel-debt! target from) (str "Cancelled debt #" target ".")
              :else                 (str "Could not cancel #" target ". It may not exist or already be settled.")))

          :split
          (let [{:keys [amount description participants]} (sms/parse-command body)
                {:keys [share count]} (db/record-split! from participants amount description)]
            (sms/build-split-text description amount share participants))

          :set-name
          (let [{:keys [name]} (sms/parse-command body)]
            (db/set-user-name! from name)
            (str "Got it! Your name is now \"" name "\"."))

          :help
          sms/help-text

          :unknown
          "I didn't understand that. Text HELP for a list of commands.")))))

;; ---------------------------------------------------------------------------
;; Receipt pages — web UI
;; ---------------------------------------------------------------------------

(defn handle-receipt-view [token]
  (if-let [receipt (db/get-receipt token)]
    (html-response (views/receipt receipt))
    (-> (html-response (views/not-found)) (resp/status 404))))

(defn handle-receipt-settle [token]
  (if-let [receipt (db/get-receipt token)]
    (if (= (:status receipt) "settled")
      (-> (html-response (views/already-settled receipt)) (resp/status 409))
      (do (db/settle-receipt! token)
          (resp/redirect (str "/receipt/" token))))
    (-> (html-response (views/not-found)) (resp/status 404))))

;; ---------------------------------------------------------------------------
;; REST API — JSON endpoints consumed by Postman / Swagger
;; ---------------------------------------------------------------------------

(defn api-get-user [phone]
  (if-let [user (db/get-user phone)]
    (json-response user)
    (json-response 404 {:error "User not found"})))

(defn api-get-debts [phone]
  (json-response {:phone phone :debts (db/all-pending-debts phone)}))

(defn api-get-history [phone]
  (json-response {:phone   phone
                  :debts   (db/debt-history phone)
                  :credits (db/credit-history phone)}))

(defn api-create-debt [request]
  (let [{:keys [debtor_phone creditor_name amount description]} (parse-json-body request)]
    (if (some nil? [debtor_phone creditor_name amount description])
      (json-response 400 {:error "Required: debtor_phone, creditor_name, amount, description"})
      (let [id    (db/record-debt! debtor_phone creditor_name (double amount) description)
            total (reduce + (map :amount (db/pending-debts-to debtor_phone creditor_name)))]
        (json-response 201 {:debt_id id
                            :message (str "You owe " creditor_name
                                          " " (sms/format-amount amount)
                                          " for " description)
                            :total_owed_to creditor_name
                            :total_pending total})))))

(defn api-cancel-debt [id request]
  (let [{:keys [phone]} (parse-json-body request)
        debt-id         (Long/parseLong (str id))]
    (cond
      (nil? phone)                   (json-response 400 {:error "Required: phone"})
      (db/cancel-debt! debt-id phone) (json-response {:success true :cancelled_id debt-id})
      :else                           (json-response 404 {:error "Debt not found or already cancelled"}))))

(defn api-split [request]
  (let [{:keys [payer_phone participants amount description]} (parse-json-body request)]
    (if (some nil? [payer_phone participants amount description])
      (json-response 400 {:error "Required: payer_phone, participants (array), amount, description"})
      (let [{:keys [share count]} (db/record-split! payer_phone participants (double amount) description)]
        (json-response 201 {:share        share
                            :participants count
                            :message      (sms/build-split-text description amount share participants)})))))

(defn api-get-receipt [token]
  (if-let [r (db/get-receipt token)]
    (json-response r)
    (json-response 404 {:error "Receipt not found"})))

;; ---------------------------------------------------------------------------
;; Swagger / API docs
;; ---------------------------------------------------------------------------

(defn swagger-ui []
  (html-response
    (str "<!DOCTYPE html><html><head>"
         "<title>IOU Tracker API</title>"
         "<meta charset='utf-8'/>"
         "<meta name='viewport' content='width=device-width, initial-scale=1'>"
         "<link rel='stylesheet' href='https://unpkg.com/swagger-ui-dist@5/swagger-ui.css'>"
         "</head><body>"
         "<div id='swagger-ui'></div>"
         "<script src='https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js'></script>"
         "<script>"
         "SwaggerUIBundle({"
         "  url: '/api-docs/openapi.yaml',"
         "  dom_id: '#swagger-ui',"
         "  presets: [SwaggerUIBundle.presets.apis, SwaggerUIBundle.SwaggerUIStandalonePreset],"
         "  layout: 'BaseLayout'"
         "});"
         "</script></body></html>")))

;; ---------------------------------------------------------------------------
;; Routes
;; ---------------------------------------------------------------------------

(defroutes app-routes
  ;; Web UI
  (GET  "/"                          []        (html-response (views/landing)))
  (GET  "/docs"                      []        (swagger-ui))
  (GET  "/receipt/:token"            [token]   (handle-receipt-view token))
  (POST "/receipt/:token/settle"     [token]   (handle-receipt-settle token))

  ;; Twilio webhook
  (POST "/sms"                       request   (handle-sms request))

  ;; REST API
  (GET  "/api/users/:phone"          [phone]   (api-get-user phone))
  (GET  "/api/users/:phone/debts"    [phone]   (api-get-debts phone))
  (GET  "/api/users/:phone/history"  [phone]   (api-get-history phone))
  (POST "/api/debts"                 request   (api-create-debt request))
  (POST "/api/debts/:id/cancel"      [id :as r] (api-cancel-debt id r))
  (POST "/api/split"                 request   (api-split request))
  (GET  "/api/receipts/:token"       [token]   (api-get-receipt token))

  ;; Static + fallback
  (route/resources "/" {:root "public"})
  (route/not-found (html-response (views/not-found))))

;; ---------------------------------------------------------------------------
;; Middleware stack
;; ---------------------------------------------------------------------------

(def app
  (-> app-routes
      (wrap-defaults (-> site-defaults
                         (assoc-in [:security :anti-forgery] false)))))
