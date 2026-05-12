(ns iou-tracker.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [environ.core :refer [env]]
            [clojure.tools.logging :as log]
            [cheshire.core :as json])
  (:import [java.util UUID]))

;; ---------------------------------------------------------------------------
;; Datasource
;; ---------------------------------------------------------------------------

(defn make-datasource []
  (let [db-path (or (env :db-path) "iou_tracker.db")]
    (jdbc/get-datasource {:dbtype "sqlite" :dbname db-path})))

(defonce ^:private -datasource (delay (make-datasource)))

(defn ds [] @-datasource)

;; ---------------------------------------------------------------------------
;; Migrations
;; ---------------------------------------------------------------------------

(defn migrate! []
  (jdbc/execute! (ds) ["
    CREATE TABLE IF NOT EXISTS users (
      id         INTEGER PRIMARY KEY AUTOINCREMENT,
      phone      TEXT    UNIQUE NOT NULL,
      name       TEXT,
      created_at DATETIME DEFAULT CURRENT_TIMESTAMP
    )"])
  (jdbc/execute! (ds) ["
    CREATE TABLE IF NOT EXISTS debts (
      id            INTEGER PRIMARY KEY AUTOINCREMENT,
      debtor_phone  TEXT    NOT NULL,
      creditor_name TEXT    NOT NULL,
      amount        REAL    NOT NULL,
      description   TEXT    NOT NULL,
      status        TEXT    NOT NULL DEFAULT 'pending',
      created_at    DATETIME DEFAULT CURRENT_TIMESTAMP
    )"])
  (jdbc/execute! (ds) ["
    CREATE TABLE IF NOT EXISTS receipts (
      id            INTEGER PRIMARY KEY AUTOINCREMENT,
      token         TEXT    UNIQUE NOT NULL,
      debtor_phone  TEXT    NOT NULL,
      debtor_name   TEXT,
      creditor_name TEXT    NOT NULL,
      total_amount  REAL    NOT NULL,
      debt_details  TEXT    NOT NULL,
      status        TEXT    NOT NULL DEFAULT 'active',
      created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
      settled_at    DATETIME
    )"])
  ;; Feature 3: tracks money others owe the SMS user (created via split)
  (jdbc/execute! (ds) ["
    CREATE TABLE IF NOT EXISTS credits (
      id             INTEGER PRIMARY KEY AUTOINCREMENT,
      creditor_phone TEXT    NOT NULL,
      debtor_name    TEXT    NOT NULL,
      amount         REAL    NOT NULL,
      description    TEXT    NOT NULL,
      status         TEXT    NOT NULL DEFAULT 'pending',
      created_at     DATETIME DEFAULT CURRENT_TIMESTAMP
    )"])
  (log/info "Database migrations complete"))

;; ---------------------------------------------------------------------------
;; Users
;; ---------------------------------------------------------------------------

(defn find-or-create-user! [phone]
  (or (first (jdbc/execute! (ds)
               ["SELECT * FROM users WHERE phone = ?" phone]
               {:builder-fn rs/as-unqualified-maps}))
      (do
        (jdbc/execute! (ds) ["INSERT INTO users (phone) VALUES (?)" phone])
        (first (jdbc/execute! (ds)
                 ["SELECT * FROM users WHERE phone = ?" phone]
                 {:builder-fn rs/as-unqualified-maps})))))

(defn set-user-name! [phone name]
  (find-or-create-user! phone)
  (jdbc/execute! (ds) ["UPDATE users SET name = ? WHERE phone = ?" name phone]))

(defn get-user [phone]
  (first (jdbc/execute! (ds)
           ["SELECT * FROM users WHERE phone = ?" phone]
           {:builder-fn rs/as-unqualified-maps})))

;; ---------------------------------------------------------------------------
;; Debts
;; ---------------------------------------------------------------------------

(defn record-debt!
  "Saves a new IOU and returns the new debt's id."
  [debtor-phone creditor-name amount description]
  (find-or-create-user! debtor-phone)
  (:id (jdbc/execute-one! (ds)
         ["INSERT INTO debts (debtor_phone, creditor_name, amount, description)
           VALUES (?, ?, ?, ?) RETURNING id"
          debtor-phone (clojure.string/trim creditor-name) amount description]
         {:builder-fn rs/as-unqualified-maps})))

(defn get-debt [id]
  (first (jdbc/execute! (ds)
           ["SELECT * FROM debts WHERE id = ?" id]
           {:builder-fn rs/as-unqualified-maps})))

(defn get-last-pending-debt [debtor-phone]
  (first (jdbc/execute! (ds)
           ["SELECT * FROM debts
             WHERE debtor_phone = ? AND status = 'pending'
             ORDER BY created_at DESC LIMIT 1"
            debtor-phone]
           {:builder-fn rs/as-unqualified-maps})))

(defn pending-debts-to
  "All pending debts that debtor-phone owes to creditor-name (case-insensitive)."
  [debtor-phone creditor-name]
  (jdbc/execute! (ds)
    ["SELECT * FROM debts
      WHERE debtor_phone = ?
        AND LOWER(creditor_name) = LOWER(?)
        AND status = 'pending'
      ORDER BY created_at ASC"
     debtor-phone creditor-name]
    {:builder-fn rs/as-unqualified-maps}))

(defn all-pending-debts
  "Everything debtor-phone owes, grouped by creditor."
  [debtor-phone]
  (jdbc/execute! (ds)
    ["SELECT creditor_name,
             SUM(amount)   AS total,
             COUNT(*)      AS count
      FROM   debts
      WHERE  debtor_phone = ?
        AND  status = 'pending'
      GROUP  BY LOWER(creditor_name)
      ORDER  BY creditor_name ASC"
     debtor-phone]
    {:builder-fn rs/as-unqualified-maps}))

(defn mark-debts-receipted! [debt-ids]
  (doseq [id debt-ids]
    (jdbc/execute! (ds)
      ["UPDATE debts SET status = 'receipted' WHERE id = ?" id])))

;; ---------------------------------------------------------------------------
;; Feature 1 — Expense History
;; ---------------------------------------------------------------------------

(defn debt-history
  "All debts for debtor-phone (all statuses), newest first.
   Pass creditor-name to filter to one person."
  ([debtor-phone]
   (jdbc/execute! (ds)
     ["SELECT * FROM debts WHERE debtor_phone = ? ORDER BY created_at DESC"
      debtor-phone]
     {:builder-fn rs/as-unqualified-maps}))
  ([debtor-phone creditor-name]
   (jdbc/execute! (ds)
     ["SELECT * FROM debts
       WHERE debtor_phone = ? AND LOWER(creditor_name) = LOWER(?)
       ORDER BY created_at DESC"
      debtor-phone creditor-name]
     {:builder-fn rs/as-unqualified-maps})))

(defn credit-history
  "All credits for creditor-phone (all statuses), newest first."
  ([creditor-phone]
   (jdbc/execute! (ds)
     ["SELECT * FROM credits WHERE creditor_phone = ? ORDER BY created_at DESC"
      creditor-phone]
     {:builder-fn rs/as-unqualified-maps}))
  ([creditor-phone debtor-name]
   (jdbc/execute! (ds)
     ["SELECT * FROM credits
       WHERE creditor_phone = ? AND LOWER(debtor_name) = LOWER(?)
       ORDER BY created_at DESC"
      creditor-phone debtor-name]
     {:builder-fn rs/as-unqualified-maps})))

;; ---------------------------------------------------------------------------
;; Feature 2 — Cancel / Undo a Debt
;; ---------------------------------------------------------------------------

(defn cancel-debt!
  "Cancels a pending debt only if it belongs to debtor-phone.
   Returns true if a row was actually updated."
  [debt-id debtor-phone]
  (let [result (jdbc/execute-one! (ds)
                 ["UPDATE debts SET status = 'cancelled'
                   WHERE id = ? AND debtor_phone = ? AND status = 'pending'"
                  debt-id debtor-phone])]
    (pos? (get result :next.jdbc/update-count 0))))

;; ---------------------------------------------------------------------------
;; Feature 3 — Group Split
;; ---------------------------------------------------------------------------

(defn record-split!
  "Splits `amount` equally among the texter + `participant-names`.
   Records a credit entry for each participant (they owe the creditor-phone).
   Returns {:share <per-person-amount> :count <num-participants>}."
  [creditor-phone participant-names amount description]
  (let [total-people (inc (count participant-names))
        share        (double (/ amount total-people))]
    (find-or-create-user! creditor-phone)
    (doseq [pname participant-names]
      (jdbc/execute! (ds)
        ["INSERT INTO credits (creditor_phone, debtor_name, amount, description)
          VALUES (?, ?, ?, ?)"
         creditor-phone (clojure.string/trim pname) share description]))
    {:share share :count (count participant-names)}))

(defn pending-credits
  "Everything owed TO creditor-phone, grouped by debtor."
  [creditor-phone]
  (jdbc/execute! (ds)
    ["SELECT debtor_name,
             SUM(amount)   AS total,
             COUNT(*)      AS count
      FROM   credits
      WHERE  creditor_phone = ?
        AND  status = 'pending'
      GROUP  BY LOWER(debtor_name)
      ORDER  BY debtor_name ASC"
     creditor-phone]
    {:builder-fn rs/as-unqualified-maps}))

;; ---------------------------------------------------------------------------
;; Receipts
;; ---------------------------------------------------------------------------

(defn create-receipt!
  "Generates a printable receipt for everything debtor-phone owes creditor-name.
   Returns the receipt token, or nil if there are no pending debts."
  [debtor-phone creditor-name]
  (let [debts (pending-debts-to debtor-phone creditor-name)]
    (when (seq debts)
      (let [token        (str (UUID/randomUUID))
            total        (->> debts (map :amount) (reduce +))
            user         (get-user debtor-phone)
            debt-ids     (map :id debts)
            details-json (json/generate-string
                           (map #(select-keys % [:id :amount :description :created_at]) debts))]
        (jdbc/execute! (ds)
          ["INSERT INTO receipts
              (token, debtor_phone, debtor_name, creditor_name, total_amount, debt_details)
            VALUES (?, ?, ?, ?, ?, ?)"
           token debtor-phone (:name user) creditor-name total details-json])
        (mark-debts-receipted! debt-ids)
        token))))

(defn get-receipt [token]
  (first (jdbc/execute! (ds)
           ["SELECT * FROM receipts WHERE token = ?" token]
           {:builder-fn rs/as-unqualified-maps})))

(defn settle-receipt! [token]
  (jdbc/execute! (ds)
    ["UPDATE receipts
      SET status = 'settled', settled_at = CURRENT_TIMESTAMP
      WHERE token = ? AND status = 'active'"
     token]))
