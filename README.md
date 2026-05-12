# IOU Tracker

Track shared household expenses by SMS — no app download, no bank account, no smartphone required.

> One person pays for pizza. The other owes them $17.50. They both have a basic phone. Venmo won't work. This will.

---

## What it does

Text a simple command to your Twilio number. Get an instant reply. No accounts to create, no app to install, no data plan required beyond sending a text.

When it's time to settle up, you get a link to a printable receipt you can screenshot or hand over physically. Cash, cheque, handshake — however you settle, the app just keeps the record.

---

## SMS Commands

Send any of these to your Twilio number:

| Text | What happens |
|------|-------------|
| `17.50 for pizza from Maria` | Records: you owe Maria $17.50 for pizza |
| `balance` | Shows everything you owe + everyone who owes you |
| `settle Maria` | Generates a printable receipt for your debt to Maria |
| `history` | Your full transaction log with IDs and statuses |
| `history Maria` | Transaction log with one person |
| `cancel` | Cancels your most recent pending debt entry |
| `cancel 5` | Cancels debt #5 (ID shown in confirmation message) |
| `split 60 for dinner with Maria, John` | Splits $60 three ways — records that Maria and John each owe you $20 |
| `name Bob` | Registers your display name (shown on receipts) |
| `help` | Prints the full command list |

### Example conversation

```
You:  17.50 for pizza from Maria
Bot:  Recorded: You owe Maria $17.50 for pizza (ID: 3).
      Total owed to Maria: $17.50

You:  5.00 for coffee from Maria
Bot:  Recorded: You owe Maria $5.00 for coffee (ID: 4).
      Total owed to Maria: $22.50

You:  balance
Bot:  You owe:
        Maria: $22.50
        Subtotal: $22.50

You:  settle Maria
Bot:  Receipt ready for your debt to Maria.
      Show this link to settle up:
      https://yourdomain.com/receipt/a1b2c3d4-...

You:  history Maria
Bot:  History with Maria:
        [pending] $17.50 for pizza (2026-05-12) #3
        [pending] $5.00 for coffee (2026-05-12) #4

You:  split 60 for dinner with Ana, Luis
Bot:  Split $60.00 3 ways for dinner.
      Per person: $20.00
        Ana owes you $20.00
        Luis owes you $20.00
      Text 'balance' to see who owes you.
```

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Clojure 1.11.1 |
| Build | Leiningen 2.x |
| HTTP server | Ring + Jetty |
| Routing | Compojure |
| Database | SQLite via next.jdbc |
| HTML | Hiccup |
| SMS | Twilio (webhook) |
| Runtime | Java 17 |

---

## Project Structure

```
SMS-based-IOU-tracker/
├── project.clj                   ← dependencies + build config
├── .env.example                  ← environment variable template
├── Dockerfile                    ← multi-stage Docker build
├── docker-compose.yml
├── postman_collection.json       ← import into Postman to test all endpoints
│
├── src/iou_tracker/
│   ├── core.clj                  ← Jetty server entry point
│   ├── db.clj                    ← SQLite schema + all database queries
│   ├── sms.clj                   ← SMS command parser + TwiML response builder
│   ├── handlers.clj              ← Ring HTTP handlers + REST API + Swagger UI
│   └── views.clj                 ← Hiccup HTML (landing page, printable receipt)
│
├── resources/
│   ├── logback.xml
│   └── public/
│       ├── style.css             ← print-friendly receipt CSS
│       └── openapi.yaml          ← OpenAPI 3.0 spec (served at /openapi.yaml)
│
└── test/iou_tracker/
    ├── sms_test.clj              ← command parser tests
    └── db_test.clj               ← database layer tests
```

---

## Database Schema

```sql
users    — phone (unique), name, created_at
debts    — debtor_phone, creditor_name, amount, description, status, created_at
credits  — creditor_phone, debtor_name, amount, description, status, created_at
receipts — token (unique), debtor/creditor info, total_amount, debt_details (JSON), status
```

**Debt lifecycle:** `pending` → `receipted` (when settle is called) → settled on receipt page  
**Cancel path:** `pending` → `cancelled`

---

## REST API

The same business logic is exposed as a JSON REST API for web/mobile clients.
Full interactive docs at `/docs` (Swagger UI).

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/sms` | Twilio webhook (TwiML response) |
| `GET` | `/api/users/:phone` | Get user profile |
| `GET` | `/api/users/:phone/debts` | Pending debts grouped by creditor |
| `GET` | `/api/users/:phone/history` | Full history (debts + credits, all statuses) |
| `POST` | `/api/debts` | Create a debt via JSON |
| `POST` | `/api/debts/:id/cancel` | Cancel a pending debt |
| `POST` | `/api/split` | Split a bill, record credits |
| `GET` | `/receipt/:token` | Printable HTML receipt |
| `POST` | `/receipt/:token/settle` | Mark receipt as settled |
| `GET` | `/api/receipts/:token` | Receipt as JSON |
| `GET` | `/docs` | Swagger UI |
| `GET` | `/openapi.yaml` | Raw OpenAPI spec |

---

## Local Setup

### Prerequisites

- Java 17+
- [Leiningen](https://leiningen.org/) (`lein`)
- A [Twilio account](https://console.twilio.com) with a phone number (free trial works)
- [ngrok](https://ngrok.com/) for local Twilio webhook tunneling

### 1. Clone and install

```bash
git clone <repo-url>
cd SMS-based-IOU-tracker
cp .env.example .env        # fill in your Twilio credentials
lein deps                   # download all JARs
```

### 2. Configure environment

Edit `.env`:

```env
PORT=3000
DB_PATH=iou_tracker.db
TWILIO_ACCOUNT_SID=ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
TWILIO_AUTH_TOKEN=your_auth_token_here
TWILIO_PHONE_NUMBER=+15005550006
BASE_URL=https://xxxx.ngrok-free.app   # update after step 4
```

Leiningen reads env vars via [environ](https://github.com/weavejester/environ).
For dev, add them to `profiles.clj` (gitignored):

```clojure
{:dev {:env {:port "3000"
             :db-path "iou_tracker_dev.db"
             :twilio-account-sid "AC..."
             :base-url "https://xxxx.ngrok-free.app"}}}
```

### 3. Run the server

```bash
lein run
# Server starts on http://localhost:3000
```

### 4. Expose with ngrok

```bash
ngrok http 3000
# Copy the https URL, e.g. https://abc123.ngrok-free.app
```

Update `BASE_URL` in your config to the ngrok URL.

### 5. Configure Twilio

1. Go to [Twilio Console → Phone Numbers](https://console.twilio.com/us1/develop/phone-numbers/manage/active)
2. Click your number → **Messaging** section
3. Set **"A message comes in"** → **Webhook** → `https://abc123.ngrok-free.app/sms`
4. Method: **HTTP POST**
5. Save

### 6. Test it

```bash
lein test          # run all unit tests
```

Or text your Twilio number: `help`

---

## Docker

```bash
# Build and start
docker compose up --build

# Detached
docker compose up -d

# Logs
docker compose logs -f

# Stop
docker compose down
```

The SQLite database is persisted in the `iou_data` Docker volume at `/data/iou_tracker.db`.

To inject Twilio credentials, either add them to `docker-compose.yml` under `environment`
or use a `.env` file at the project root (Docker Compose auto-loads it).

---

## Postman

Import `postman_collection.json` directly into Postman:

**Postman → Import → Upload Files → select `postman_collection.json`**

The collection includes:
- All SMS simulation requests (form-encoded POST to `/sms`)
- All REST API endpoints with example bodies
- A `base_url` variable (`http://localhost:3000` by default)

---

## Running Tests

```bash
lein test
```

Tests use a temporary SQLite file per test so they never touch your dev database.

---

## Deployment

### VPS (recommended for low cost)

```bash
# Build the standalone JAR
lein uberjar

# Copy to server
scp target/uberjar/sms-iou-tracker-*-standalone.jar user@yourserver:~/iou/app.jar

# On the server — run with systemd or screen
PORT=3000 DB_PATH=/opt/iou/data.db java -jar ~/iou/app.jar
```

### Docker on a VPS

```bash
# On your server
git clone <repo>
cd SMS-based-IOU-tracker
# Edit docker-compose.yml — set BASE_URL to your real domain
docker compose up -d
```

Put nginx in front and point your Twilio webhook at `https://yourdomain.com/sms`.

---

## Why This Project

Most bill-splitting tools (Venmo, CashApp, Splitwise) require a smartphone, a bank account, and often a credit card. That excludes:

- Teenagers without bank accounts
- Undocumented workers without access to US banking
- People with bad credit or past bankruptcy
- Older adults who distrust mobile payment apps

IOU Tracker needs **none of that**. Just a phone that can send texts — which everyone has.

No money moves through this app. Settlement happens offline with cash or a handshake. The app is just the shared memory.

---

## License

MIT
