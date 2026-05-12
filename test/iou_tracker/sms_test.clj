(ns iou-tracker.sms-test
  (:require [clojure.test :refer [deftest is testing]]
            [iou-tracker.sms :as sms]))

;; ---------------------------------------------------------------------------
;; parse-command — original commands
;; ---------------------------------------------------------------------------

(deftest parse-record-debt
  (testing "standard IOU format"
    (let [cmd (sms/parse-command "17.50 for pizza from Maria")]
      (is (= :record-debt (:cmd cmd)))
      (is (= 17.50 (:amount cmd)))
      (is (= "pizza" (:description cmd)))
      (is (= "Maria" (:creditor cmd)))))

  (testing "whole-dollar amount"
    (let [cmd (sms/parse-command "20 for gas from John")]
      (is (= :record-debt (:cmd cmd)))
      (is (= 20.0 (:amount cmd)))))

  (testing "case insensitive keywords"
    (is (= :record-debt (:cmd (sms/parse-command "5.00 FOR coffee FROM Ana")))))

  (testing "multi-word description"
    (let [cmd (sms/parse-command "30 for grocery run from Bob")]
      (is (= "grocery run" (:description cmd))))))

(deftest parse-balance
  (is (= {:cmd :balance} (sms/parse-command "balance")))
  (is (= {:cmd :balance} (sms/parse-command "BALANCE")))
  (is (= {:cmd :balance} (sms/parse-command "  balance  "))))

(deftest parse-settle
  (let [cmd (sms/parse-command "settle Maria")]
    (is (= :settle (:cmd cmd)))
    (is (= "Maria" (:name cmd)))))

(deftest parse-set-name
  (let [cmd (sms/parse-command "name Bob Smith")]
    (is (= :set-name (:cmd cmd)))
    (is (= "Bob Smith" (:name cmd)))))

(deftest parse-help
  (is (= {:cmd :help} (sms/parse-command "help")))
  (is (= {:cmd :help} (sms/parse-command "HELP"))))

(deftest parse-unknown
  (is (= :unknown (:cmd (sms/parse-command "random stuff")))))

;; ---------------------------------------------------------------------------
;; Feature 1 — History
;; ---------------------------------------------------------------------------

(deftest parse-history
  (testing "no name — full history"
    (let [cmd (sms/parse-command "history")]
      (is (= :history (:cmd cmd)))
      (is (nil? (:name cmd)))))

  (testing "with name"
    (let [cmd (sms/parse-command "history Maria")]
      (is (= :history (:cmd cmd)))
      (is (= "Maria" (:name cmd)))))

  (testing "case insensitive"
    (is (= :history (:cmd (sms/parse-command "HISTORY"))))))

(deftest build-history-text-test
  (testing "no transactions"
    (is (re-find #"no transactions" (sms/build-history-text [] [] nil))))

  (testing "with debts and credits"
    (let [debts   [{:id 1 :status "pending" :amount 17.5 :description "pizza"  :created_at "2026-05-12 10:00:00"}]
          credits [{:id 1 :status "pending" :amount 20.0 :description "dinner" :created_at "2026-05-12 12:00:00"}]
          text    (sms/build-history-text debts credits "Maria")]
      (is (re-find #"pizza"  text))
      (is (re-find #"dinner" text))
      (is (re-find #"#1"     text)))))

;; ---------------------------------------------------------------------------
;; Feature 2 — Cancel
;; ---------------------------------------------------------------------------

(deftest parse-cancel
  (testing "cancel with no ID cancels last"
    (let [cmd (sms/parse-command "cancel")]
      (is (= :cancel (:cmd cmd)))
      (is (nil? (:debt-id cmd)))))

  (testing "cancel with specific ID"
    (let [cmd (sms/parse-command "cancel 5")]
      (is (= :cancel (:cmd cmd)))
      (is (= 5 (:debt-id cmd)))))

  (testing "case insensitive"
    (is (= :cancel (:cmd (sms/parse-command "CANCEL"))))))

;; ---------------------------------------------------------------------------
;; Feature 3 — Split
;; ---------------------------------------------------------------------------

(deftest parse-split
  (testing "space-separated participants"
    (let [cmd (sms/parse-command "split 60 for dinner with Maria John")]
      (is (= :split (:cmd cmd)))
      (is (= 60.0 (:amount cmd)))
      (is (= "dinner" (:description cmd)))
      (is (= ["Maria" "John"] (:participants cmd)))))

  (testing "comma-separated participants"
    (let [cmd (sms/parse-command "split 90 for groceries with Ana, Luis, Pedro")]
      (is (= :split (:cmd cmd)))
      (is (= ["Ana" "Luis" "Pedro"] (:participants cmd)))))

  (testing "decimal amount"
    (let [cmd (sms/parse-command "split 45.75 for gas with Kim")]
      (is (= 45.75 (:amount cmd))))))

(deftest build-split-text-test
  (let [text (sms/build-split-text "dinner" 60.0 20.0 ["Maria" "John"])]
    (is (re-find #"\$60.00" text))
    (is (re-find #"\$20.00" text))
    (is (re-find #"Maria"   text))
    (is (re-find #"John"    text))
    (is (re-find #"3 ways"  text))))

;; ---------------------------------------------------------------------------
;; Feature 4 — Debt Notes
;; ---------------------------------------------------------------------------

(deftest parse-note
  (testing "note with ID and text"
    (let [cmd (sms/parse-command "note 5 paid half in cash")]
      (is (= :note (:cmd cmd)))
      (is (= 5 (:debt-id cmd)))
      (is (= "paid half in cash" (:note-text cmd)))))

  (testing "note with multi-word text"
    (let [cmd (sms/parse-command "note 12 met at the cafe, gave $10 back")]
      (is (= :note (:cmd cmd)))
      (is (= 12 (:debt-id cmd)))
      (is (= "met at the cafe, gave $10 back" (:note-text cmd)))))

  (testing "case insensitive keyword"
    (is (= :note (:cmd (sms/parse-command "NOTE 3 something"))))))

(deftest note-requires-id-and-text
  (testing "no ID falls through to unknown"
    (is (= :unknown (:cmd (sms/parse-command "note"))))))

;; ---------------------------------------------------------------------------
;; Response builders
;; ---------------------------------------------------------------------------

(deftest format-amount-test
  (is (= "$17.50" (sms/format-amount 17.5)))
  (is (= "$20.00" (sms/format-amount 20.0)))
  (is (= "$0.99"  (sms/format-amount 0.99))))

(deftest build-balance-text-test
  (testing "no debts and no credits"
    (is (re-find #"all clear" (sms/build-balance-text [] []))))

  (testing "debts only"
    (let [debts [{:creditor_name "Maria" :total 17.5}
                 {:creditor_name "John"  :total 30.0}]
          text  (sms/build-balance-text debts [])]
      (is (re-find #"Maria"   text))
      (is (re-find #"John"    text))
      (is (re-find #"You owe" text))))

  (testing "credits only"
    (let [credits [{:debtor_name "Ana" :total 20.0}]
          text    (sms/build-balance-text [] credits)]
      (is (re-find #"Ana"      text))
      (is (re-find #"owe you"  text))))

  (testing "debts and credits combined"
    (let [debts   [{:creditor_name "Maria" :total 17.5}]
          credits [{:debtor_name "John"   :total 30.0}]
          text    (sms/build-balance-text debts credits)]
      (is (re-find #"Maria" text))
      (is (re-find #"John"  text)))))
