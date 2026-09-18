# QORA Smart Digital Queue Management System

Join a queue remotely, watch your position update live, and get notified when
your turn is near. Businesses manage their queues from a staff dashboard.

Built in Java with **zero external dependencies** no Maven, no Gradle, no
database server. If you have a JDK, you can run it.

---

## Running it

### Easiest way (no compiler needed)

Double-click **START-QORA.bat** (Windows) or **START-QORA.command**
(Mac/Linux). Your browser opens automatically. This runs the prebuilt
`QORA.jar`, which only needs Java installed â€” not the JDK.

See `INSTALL.txt` for the non-technical version of these instructions.

### From source (needs JDK 17+)

```
./run.sh        # Linux / macOS
run.bat         # Windows
```

Then open http://localhost:8080

After editing any `.java` file, rebuild the jar with `./build-jar.sh`.

### Demo data

To load demo accounts and queues, run this in a second terminal while the
server is up (needs a shell â€” on Windows use Git Bash, or just register
accounts by hand through the sign-up form):

```
./seed.sh
```

This creates:

| Account | Sign in with | Password |
|---|---|---|
| Rao Clinic (business) | `clinic@qora.test` | `demo1234` |
| Trim & Tidy Salon (business) | `salon@qora.test` | `demo1234` |
| Anita (customer) | `9876543210` | `demo1234` |
| Bhaskar (customer) | `9876543211` | `demo1234` |

Data is saved to a `data/qora.db` file beside the jar and reloaded on
restart. Delete it to start clean. If port 8080 is taken, QORA picks
another port automatically and prints the address.

---|---|---|
| Rao Clinic (business) | `clinic@qora.test` | `demo1234` |
| Trim & Tidy Salon (business) | `salon@qora.test` | `demo1234` |
| Anita (customer) | `9876543210` | `demo1234` |
| Bhaskar (customer) | `9876543211` | `demo1234` |

Data is saved to `data/qora.db` and reloaded on restart. Delete that file to
start clean.

---

## Demoing it (the two-window trick)

The most convincing demo uses two browser windows side by side:

1. **Window A** â€” sign in as `clinic@qora.test`, open the *General OPD* queue,
   click **Manage**. This is the staff dashboard.
2. **Window B** â€” open a **private/incognito** window (so the sessions don't
   collide), sign in as `9876543210`, pick Rao Clinic â†’ General OPD â†’ **Join
   this queue**.
3. Watch Window A: the customer appears in the dashboard within 3 seconds.
4. In Window A, click **Call next customer**.
5. Watch Window B: the big number flips to **NOW**, and a notification fires.

That live cross-window update is the thing worth showing an evaluator.

---

## What's implemented

**Authentication**
- Register with an email **or** a mobile number (either one works, you don't need both)
- Sign in with whichever you registered
- Passwords stored salted and hashed, never in plaintext
- Bearer-token sessions; every protected endpoint checks the token
- Two roles: customer and business owner

**Customer side**
- Browse registered businesses and their open queues
- See how many are waiting and the estimated wait before joining
- Join remotely; live position and ETA, refreshed every 3 seconds
- Notification (browser notification + in-app toast) when your turn approaches
- Leave the queue at any time

**Business side**
- Register a business with an average service time
- Create multiple queues per business (one per counter, doctor, chair)
- Live dashboard of who's waiting, in order, with each person's ETA
- Call next customer, mark served, mark no-show

**Queue engine**
- FIFO ordering by join time
- Positions recomputed on read, so cancellations and no-shows close the gap
  automatically â€” nobody gets a stale position
- ETA = (position âˆ’ 1) Ã— average service time
- Duplicate joins blocked; a served customer can rejoin later

---

## Architecture

```
src/qora/
  Main.java      HTTP server, routing, static file serving
  Api.java       All endpoint handlers, validation, authorization
  Store.java     In-memory data store + file persistence
  Models.java    User, Business, WaitQueue, QueueEntry
  AuthUtil.java  Password hashing, token generation
  Json.java      Minimal JSON parser/writer (no library needed)

public/
  index.html     App shell
  style.css      Visual design
  app.js         Frontend: views, API calls, live polling
```

Built on `com.sun.net.httpserver`, which ships inside the JDK. The frontend is
plain JavaScript with no build step.

`QORA.jar` bundles the compiled classes and the `public/` web files together,
so it runs standalone from any folder. `Main.readStatic()` loads web files from
inside the jar first, falling back to the `public/` folder on disk when running
from compiled classes during development.

`Store` is deliberately the only class that touches data. To move to a real
database later, reimplement `Store`'s methods against JDBC or JPA â€” the
handlers in `Api.java` don't change.

---

## API reference

All request and response bodies are JSON. Protected endpoints need the header
`Authorization: Bearer <token>`.

### Auth
| Method | Path | Body | Notes |
|---|---|---|---|
| POST | `/api/auth/register` | `name`, `email` and/or `phone`, `password`, `role` | Returns token + user |
| POST | `/api/auth/login` | `identifier` (email or phone), `password` | Returns token + user |
| GET | `/api/me` | â€” | Current user |

### Businesses
| Method | Path | Notes |
|---|---|---|
| GET | `/api/businesses` | All businesses (public) |
| POST | `/api/businesses` | Owner only; `name`, `category`, `avgServiceMinutes` |
| GET | `/api/my-business` | The signed-in owner's business |

### Queues
| Method | Path | Notes |
|---|---|---|
| GET | `/api/businesses/{id}/queues` | Queues with live waiting counts |
| POST | `/api/businesses/{id}/queues` | Owner only; `name` |
| GET | `/api/queues/{id}` | Queue state |
| POST | `/api/queues/{id}/join` | Customer joins |
| GET | `/api/queues/{id}/my-entry` | Your position, status and ETA |
| GET | `/api/queues/{id}/entries` | Staff dashboard; owner only |
| POST | `/api/queues/{id}/call-next` | Owner only |

### Entries
| Method | Path | Notes |
|---|---|---|
| POST | `/api/entries/{id}/serve` | Owner only |
| POST | `/api/entries/{id}/no-show` | Owner only |
| POST | `/api/entries/{id}/cancel` | The customer who joined |

Errors come back as `{"error": "message"}` with a real status code: 400
validation, 401 not signed in, 403 not allowed, 404 missing, 409 conflict.

---

## Tested behaviour

Verified working:

- Registration and login by email, and by mobile number with no email at all
- Rejection of: duplicate email, duplicate mobile, neither identifier supplied,
  passwords under 6 characters, wrong password, unknown account
- Rejection of: requests with no token, requests with a forged token, a customer
  reading a staff dashboard, a customer calling the next person, a customer
  creating a business, a customer marking someone else served
- Positions shifting correctly when someone ahead is served, cancels, or no-shows
- Calling next on an empty queue returning a clean 409 rather than crashing
- A served customer being able to rejoin
- **25 customers joining simultaneously** all receiving unique positions 1â€“25
  with no duplicates or gaps
- Data surviving a full server restart, including password hashes
- Malformed and empty request bodies returning 400, not a 500

---

## Known limitations

Worth stating plainly in your report â€” examiners tend to ask.

**Live updates use polling, not WebSocket.** The frontend re-fetches every 3
seconds. This is reliable and easy to demo, but means up to 3 seconds of lag and
more requests than a push-based design. WebSocket (`spring-websocket` or a raw
`WebSocketHandler`) would be the upgrade.

**Notifications are in-app only.** When your turn approaches you get a browser
notification and an on-screen toast. There is no real email or SMS delivery â€”
that needs an external provider (SendGrid, Twilio) with an account, API keys,
and network access, and it costs money per message. The notification *trigger
logic* is implemented and working; only the delivery channel is local.

**Passwords use salted SHA-256, not bcrypt.** SHA-256 is fast by design, which
is exactly wrong for password hashing â€” a real system should use bcrypt or
Argon2 to make brute-forcing expensive. That needs a library this
zero-dependency build can't include. `AuthUtil.hash()` is the single place to
change.

**Sessions are in-memory.** Tokens are lost on restart, so everyone signs in
again. Users and queues persist; only sessions don't.

**Persistence is a serialized file, not a database.** `data/qora.db` is fine for
a demo and survives restarts, but it rewrites the whole file on save and won't
scale. Swapping in MySQL or PostgreSQL means reimplementing `Store` only.

**ETA is a flat average.** Position Ã— average service time. It ignores time of
day, which staff member is working, and how long the current customer has
already been in the chair. A moving average of recent actual service times would
be a natural improvement and a good thing to write about.

**No email/phone verification.** You can register with an address you don't own.
Real verification needs the same external provider as notifications.

---

## Suggested next steps

Roughly in order of value for a project report:

1. Replace polling with WebSocket for true push updates
2. Move persistence to MySQL/PostgreSQL via JDBC
3. Switch password hashing to bcrypt
4. Add real email/SMS via SendGrid or Twilio
5. Improve the ETA model using recent actual service times
6. Add analytics to the dashboard: average wait, peak hours, no-show rate

