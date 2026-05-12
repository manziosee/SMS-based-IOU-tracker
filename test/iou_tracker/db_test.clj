(ns iou-tracker.db-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [iou-tracker.db :as db]
            [next.jdbc :as jdbc]))

;; Each test gets its own temp SQLite file so connections share the same schema.
;; In-memory SQLite creates a fresh DB per connection, which breaks multi-query tests.
(defn with-test-db [f]
  (let [tmp  (java.io.File/createTempFile "iou_test_" ".db")
        path (.getAbsolutePath tmp)
        ds   (jdbc/get-datasource {:dbtype "sqlite" :dbname path})]
    (.deleteOnExit tmp)
    (try
      (with-redefs [db/ds (constantly ds)]
        (db/migrate!)
        (f))
      (finally (.delete tmp)))))

(use-fixtures :each with-test-db)

;; ---------------------------------------------------------------------------
;; Users
;; ---------------------------------------------------------------------------

(deftest find-or-create-user-test
  (testing "creates a new user on first call"
    (let [user (db/find-or-create-user! "+15550001111")]
      (is (= "+15550001111" (:phone user)))))

  (testing "returns the same user on subsequent calls"
    (db/find-or-create-user! "+15550002222")
    (let [user (db/find-or-create-user! "+15550002222")]
      (is (= "+15550002222" (:phone user))))))

(deftest set-user-name-test
  (db/find-or-create-user! "+15550003333")
  (db/set-user-name! "+15550003333" "Alice")
  (let [user (db/get-user "+15550003333")]
    (is (= "Alice" (:name user)))))

;; ---------------------------------------------------------------------------
;; Debts — core
;; ---------------------------------------------------------------------------

(deftest record-debt-returns-id-test
  (let [id (db/record-debt! "+15550010000" "Maria" 17.5 "pizza")]
    (is (integer? id))
    (is (pos? id))))

(deftest record-and-query-debts-test
  (db/record-debt! "+15550004444" "Maria" 17.50 "pizza")
  (db/record-debt! "+15550004444" "Maria" 5.00  "coffee")
  (db/record-debt! "+15550004444" "John"  20.00 "gas")

  (testing "pending-debts-to filters by creditor"
    (is (= 2 (count (db/pending-debts-to "+15550004444" "Maria")))))

  (testing "all-pending-debts groups by creditor"
    (is (= 2 (count (db/all-pending-debts "+15550004444")))))

  (testing "total grouped amount for Maria"
    (let [maria (->> (db/all-pending-debts "+15550004444")
                     (filter #(= "Maria" (:creditor_name %)))
                     first)]
      (is (= 22.5 (:total maria))))))

;; ---------------------------------------------------------------------------
;; Feature 1 — Expense History
;; ---------------------------------------------------------------------------

(deftest debt-history-test
  (db/record-debt! "+15550020000" "Maria" 10.0 "lunch")
  (db/record-debt! "+15550020000" "John"  20.0 "gas")

  (testing "full history returns all debts"
    (is (= 2 (count (db/debt-history "+15550020000")))))

  (testing "filtered history returns only matching creditor"
    (let [rows (db/debt-history "+15550020000" "Maria")]
      (is (= 1 (count rows)))
      (is (= "Maria" (:creditor_name (first rows)))))))

;; ---------------------------------------------------------------------------
;; Feature 2 — Cancel / Undo
;; ---------------------------------------------------------------------------

(deftest cancel-debt-test
  (let [id (db/record-debt! "+15550030000" "Bob" 30.0 "dinner")]
    (testing "get-last-pending-debt returns the debt"
      (let [d (db/get-last-pending-debt "+15550030000")]
        (is (= id (:id d)))))

    (testing "cancel-debt! returns true for valid cancel"
      (is (true? (db/cancel-debt! id "+15550030000"))))

    (testing "debt is now cancelled"
      (let [d (db/get-debt id)]
        (is (= "cancelled" (:status d)))))

    (testing "cancel-debt! returns false for non-existent / already cancelled"
      (is (false? (db/cancel-debt! id "+15550030000"))))))

;; ---------------------------------------------------------------------------
;; Feature 4 — Debt Notes
;; ---------------------------------------------------------------------------

(deftest add-debt-note-test
  (let [id (db/record-debt! "+15550070000" "Maria" 20.0 "dinner")]
    (testing "add-debt-note! returns true on success"
      (is (true? (db/add-debt-note! id "+15550070000" "paid half already"))))

    (testing "note is persisted on the debt"
      (let [d (db/get-debt id)]
        (is (= "paid half already" (:notes d)))))

    (testing "note can be updated"
      (db/add-debt-note! id "+15550070000" "fully paid back")
      (is (= "fully paid back" (:notes (db/get-debt id)))))

    (testing "wrong owner cannot add note"
      (is (false? (db/add-debt-note! id "+19999999999" "hacked"))))))

(deftest note-appears-in-history-test
  (let [id (db/record-debt! "+15550071000" "Bob" 15.0 "coffee")]
    (db/add-debt-note! id "+15550071000" "paying Friday")
    (let [history (db/debt-history "+15550071000")]
      (is (= "paying Friday" (:notes (first history)))))))

(deftest cancel-wrong-owner-test
  (let [id (db/record-debt! "+15550031111" "Maria" 5.0 "coffee")]
    (testing "cannot cancel another user's debt"
      (is (false? (db/cancel-debt! id "+19999999999"))))))

;; ---------------------------------------------------------------------------
;; Feature 3 — Group Split
;; ---------------------------------------------------------------------------

(deftest record-split-test
  (let [result (db/record-split! "+15550040000" ["Maria" "John"] 60.0 "dinner")]
    (testing "returns correct share"
      (is (= 20.0 (:share result))))

    (testing "returns participant count"
      (is (= 2 (:count result))))

    (testing "pending-credits shows both participants"
      (let [credits (db/pending-credits "+15550040000")]
        (is (= 2 (clojure.core/count credits)))
        (let [maria (first (filter #(= "Maria" (:debtor_name %)) credits))]
          (is (= 20.0 (:total maria))))))))

;; ---------------------------------------------------------------------------
;; Receipts — lifecycle
;; ---------------------------------------------------------------------------

(deftest receipt-lifecycle-test
  (db/record-debt! "+15550005555" "Bob" 30.0 "dinner")

  (testing "create-receipt! returns a token"
    (let [token (db/create-receipt! "+15550005555" "Bob")]
      (is (string? token))

      (testing "get-receipt finds it"
        (let [r (db/get-receipt token)]
          (is (= "active" (:status r)))
          (is (= 30.0 (:total_amount r)))))

      (testing "settle-receipt! marks it settled"
        (db/settle-receipt! token)
        (let [r (db/get-receipt token)]
          (is (= "settled" (:status r))))))))

(deftest create-receipt-no-debts-test
  (is (nil? (db/create-receipt! "+15550006666" "Nobody"))))
