(ns iou-tracker.sms
  "Parses inbound SMS commands and builds TwiML text responses.
   All business logic lives here; no HTTP knowledge needed.")

;; ---------------------------------------------------------------------------
;; TwiML helpers
;; ---------------------------------------------------------------------------

(defn twiml-response
  "Wraps text in the minimal TwiML XML that Twilio expects."
  [text]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
       "<Response><Message>"
       text
       "</Message></Response>"))

;; ---------------------------------------------------------------------------
;; Command parsing
;; ---------------------------------------------------------------------------
;;
;;  {amount} for {description} from {name}     → record a debt you owe
;;  balance                                     → see everyone you owe + who owes you
;;  settle {name}                               → generate printable receipt
;;  history [name]                              → itemized transaction log
;;  cancel [id]                                 → undo last (or specific) debt
;;  split {amount} for {description} with {names} → split bill, track credits
;;  name {yourname}                             → register display name
;;  help                                        → command summary

(def ^:private re-owe-from
  #"(?i)^(\d+(?:\.\d{1,2})?)\s+for\s+(.+?)\s+from\s+(.+)$")

(def ^:private re-settle
  #"(?i)^settle\s+(.+)$")

(def ^:private re-history
  #"(?i)^history(?:\s+(.+))?$")

(def ^:private re-cancel
  #"(?i)^cancel(?:\s+(\d+))?$")

(def ^:private re-split
  #"(?i)^split\s+(\d+(?:\.\d{1,2})?)\s+for\s+(.+?)\s+with\s+(.+)$")

(def ^:private re-name
  #"(?i)^name\s+(.+)$")

(defn- parse-participants
  "Splits 'Maria, John' or 'Maria John' into a vector of trimmed names."
  [s]
  (let [s (clojure.string/trim s)]
    (if (clojure.string/includes? s ",")
      (mapv clojure.string/trim (clojure.string/split s #",\s*"))
      (mapv clojure.string/trim (clojure.string/split s #"\s+")))))

(defn parse-command
  "Returns a map {:cmd keyword, ...fields} or {:cmd :unknown}."
  [raw-body]
  (let [body (-> raw-body str clojure.string/trim)]
    (cond
      (re-matches #"(?i)^balance$" body)
      {:cmd :balance}

      (re-matches #"(?i)^help$" body)
      {:cmd :help}

      (re-find re-owe-from body)
      (let [[_ amount description creditor] (re-find re-owe-from body)]
        {:cmd         :record-debt
         :amount      (Double/parseDouble amount)
         :description (clojure.string/trim description)
         :creditor    (clojure.string/trim creditor)})

      (re-find re-settle body)
      (let [[_ name] (re-find re-settle body)]
        {:cmd :settle :name (clojure.string/trim name)})

      (re-find re-history body)
      (let [[_ name] (re-find re-history body)]
        {:cmd :history :name (when name (clojure.string/trim name))})

      (re-find re-cancel body)
      (let [[_ id-str] (re-find re-cancel body)]
        {:cmd :cancel :debt-id (when id-str (Long/parseLong id-str))})

      (re-find re-split body)
      (let [[_ amount description names-str] (re-find re-split body)]
        {:cmd          :split
         :amount       (Double/parseDouble amount)
         :description  (clojure.string/trim description)
         :participants (parse-participants names-str)})

      (re-find re-name body)
      (let [[_ name] (re-find re-name body)]
        {:cmd :set-name :name (clojure.string/trim name)})

      :else
      {:cmd :unknown :raw body})))

;; ---------------------------------------------------------------------------
;; Response builders
;; ---------------------------------------------------------------------------

(def help-text
  (clojure.string/join "\n"
    ["IOU Tracker commands:"
     "  17.50 for pizza from Maria      — record debt you owe Maria"
     "  balance                         — see all you owe + who owes you"
     "  settle Maria                    — get printable receipt"
     "  history                         — full transaction log"
     "  history Maria                   — log with one person"
     "  cancel                          — undo your last debt entry"
     "  cancel 5                        — undo debt by ID"
     "  split 60 for dinner with A, B   — split bill, track credits"
     "  name Bob                        — register your name"
     "  help                            — this message"]))

(defn format-amount [n]
  (format "$%.2f" (double n)))

(defn build-balance-text [debts credits]
  (let [owe-lines  (map #(str "  " (:creditor_name %) ": " (format-amount (:total %))) debts)
        owed-lines (map #(str "  " (:debtor_name %)   ": " (format-amount (:total %))) credits)
        owe-total  (reduce + 0 (map :total debts))
        owed-total (reduce + 0 (map :total credits))]
    (cond
      (and (empty? debts) (empty? credits))
      "No outstanding debts either way. You're all clear!"

      :else
      (clojure.string/join "\n"
        (remove nil?
          [(when (seq debts)
             (clojure.string/join "\n"
               (concat ["You owe:"] owe-lines
                       [(str "  Subtotal: " (format-amount owe-total))])))
           (when (seq credits)
             (clojure.string/join "\n"
               (concat ["Others owe you:"] owed-lines
                       [(str "  Subtotal: " (format-amount owed-total))])))])))))

(defn build-settle-text [creditor-name token base-url]
  (str "Receipt ready for your debt to " creditor-name ".\n"
       "Show this link to settle up:\n"
       base-url "/receipt/" token))

(defn build-history-text [debts credits person-name]
  (let [label   (if person-name (str "History with " person-name) "Full history")
        fmt-row #(str "  [" (:status %) "] "
                      (format-amount (:amount %)) " for " (:description %)
                      " (" (subs (:created_at %) 0 10) ")"
                      " #" (:id %))]
    (if (and (empty? debts) (empty? credits))
      (str label ": no transactions found.")
      (clojure.string/join "\n"
        (remove nil?
          [label
           (when (seq debts)
             (clojure.string/join "\n"
               (concat ["You owe:"] (map fmt-row debts))))
           (when (seq credits)
             (clojure.string/join "\n"
               (concat ["Owed to you:"] (map fmt-row credits))))])))))

(defn build-split-text [description amount share participants]
  (let [total-people (inc (count participants))
        lines        (map #(str "  " % " owes you " (format-amount share)) participants)]
    (clojure.string/join "\n"
      (concat
        [(str "Split " (format-amount amount) " " total-people " ways for " description ".")]
        [(str "Per person: " (format-amount share))]
        lines
        ["Text 'balance' to see who owes you."]))))
