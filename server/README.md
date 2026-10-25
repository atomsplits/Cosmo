# Cosmo server

A small server for the Cosmo app. It relays end-to-end encrypted chat messages between Cosmo
users, and handles identity verification (KYC) with [Sumsub](https://sumsub.com) so the Sumsub
secret key never has to ship inside the app.

The chat relay passes messages between users.
It only ever handles PGP ciphertext: messages are encrypted and signed on the sender's phone and
can only be decrypted on the recipient's phone (and the sender's, for their own history).

## Running it

From the repository root, with any JDK 17 or newer (Android Studio's bundled JDK works):

```sh
./gradlew -p server run          # listens on port 8080
PORT=9000 ./gradlew -p server run   # or pick a port
./gradlew -p server test         # runs the relay tests
```

Then point the app at it in **Settings → Cosmo server**:

- **Android emulator:** `ws://10.0.2.2:8080/chat` (the default). `10.0.2.2` is the emulator's
  address for your computer.
- **Your phone:** `ws://<your computer's Wi-Fi IP>:8080/chat`, e.g. `ws://192.168.1.20:8080/chat`.
  The phone and computer must be on the same network, and your firewall must allow port 8080.

Plain `ws://` only works in debug builds (see `app/src/debug/res/xml/network_security_config.xml`).
Release builds require `wss://`, so deploy the relay behind TLS before shipping.

## How it works

Each user has a PGP key pair generated on their phone the first time they open chat. The secret
key stays on the phone, encrypted with a key held in the Android Keystore. The public key's user ID
is the user's Cosmo ID.

1. **Log in.** The server sends a random challenge. The app replies with its Cosmo ID, its public
   key, and the challenge signed with its secret key. The server checks that the key is named for
   that Cosmo ID and that the signature is valid. The first key to log in with a Cosmo ID claims it;
   after that, any other key is refused for that ID.
2. **Look up a contact.** The app asks for the contact's public key. The app pins the first key it
   sees for each contact and refuses to send if the server later offers a different one.
3. **Send.** The app encrypts the message to the recipient and to itself, signs it, and sends the
   ciphertext. The server forwards it, or queues up to 100 messages while the recipient is offline.

Keys and queued messages are kept in memory, so they're lost when the server restarts.
`supabase/migrations` has the table that will store public keys permanently.

## Protocol

JSON text frames over `ws://host:port/chat`. Every frame has a `type`.

| Direction | Frame |
| --- | --- |
| server → app | `{"type":"challenge","nonce":"…"}` |
| app → server | `{"type":"hello","userId":"COSMO-…","publicKey":"-----BEGIN PGP PUBLIC KEY BLOCK-----…","signature":"-----BEGIN PGP MESSAGE-----…"}` |
| server → app | `{"type":"welcome","userId":"COSMO-…"}` |
| app → server | `{"type":"get_key","userId":"COSMO-…"}` |
| server → app | `{"type":"key","userId":"COSMO-…","publicKey":"…"}` (`publicKey` is `null` if unknown) |
| app → server | `{"type":"send","id":"<uuid>","to":"COSMO-…","ciphertext":"-----BEGIN PGP MESSAGE-----…"}` |
| server → app | `{"type":"sent","id":"<uuid>","delivered":true}` (`false` means queued) |
| server → app | `{"type":"message","id":"<uuid>","from":"COSMO-…","ciphertext":"…","sentAt":1761400000000}` |
| server → app | `{"type":"error","message":"…","fatal":false,"id":null}` (`fatal` closes the connection) |

The app's side is `app/src/main/java/com/rivers/cosmo/chat/ChatProtocol.kt`; keep the two in step.

## Identity verification (Sumsub)

KYC is off until you give the server Sumsub API credentials. Use **sandbox** credentials while
developing: they can't touch real applicants' data. Create them in the Sumsub dashboard in sandbox
mode (Dev space → App tokens); the secret key is shown only once.

```sh
export SUMSUB_APP_TOKEN='sbx:...'        # required
export SUMSUB_SECRET_KEY='...'           # required
export SUMSUB_LEVEL_NAME='basic-kyc-level'   # optional; the verification level to use
export SUMSUB_WEBHOOK_SECRET='...'       # optional; enables POST /kyc/webhook
./gradlew -p server run
```

Never commit these values or put them in the app.

### How it works

1. In **Settings → Identity verification**, the app asks this server for a Sumsub access token.
   The request is signed with the user's chat PGP key, and the server checks it against the key
   that Cosmo ID logged in to chat with. A user therefore needs to have connected to chat once
   (the app does this automatically on start).
2. The server asks Sumsub for a 20-minute SDK token for that Cosmo ID (Sumsub's `externalUserId`)
   and returns it. The app opens Sumsub's verification flow with it.
3. Sumsub reviews the documents. The app shows the result by asking this server, which reads the
   applicant from Sumsub's API or, if that fails, uses the last webhook result.

The SDK's own callbacks are only used to refresh the screen: what counts is what Sumsub tells the
server. Anything you gate on verification (for example sending on the public Stellar network)
must be checked on a server, not in the app.

### Webhooks

Sumsub's webhooks are the authoritative, push-based way to hear about results. In the Sumsub
dashboard, add a webhook pointing at `https://<your server>/kyc/webhook` for at least the
`applicantReviewed` event, and set `SUMSUB_WEBHOOK_SECRET` to its secret. Deliveries are checked
against the `x-payload-digest` header and refused if it doesn't match.

Sumsub can't reach `localhost`, so to try webhooks during development, expose the server with a
tunnel such as `ngrok http 8080` and use the https URL it prints.

### Endpoints

All take and return JSON. The request body for the first two is
`{"userId":"COSMO-…","timestamp":<ms since 1970>,"signature":"<PGP-signed text>"}`, where the
signed text is `cosmo-kyc:<token|status>:<userId>:<timestamp>` and the timestamp must be within
5 minutes of the server's clock.

| Endpoint | Returns |
| --- | --- |
| `POST /kyc/token` | `{"token":"_act-sbx-…"}` for Sumsub's SDK |
| `POST /kyc/status` | `{"status":"NOT_STARTED"}`, or `INCOMPLETE`, `IN_REVIEW`, `APPROVED`, `NEEDS_RESUBMISSION`, `REJECTED` |
| `POST /kyc/webhook` | Receives Sumsub webhooks |

Errors come back as `{"error":"…"}` with a 4xx/5xx status.

Verification results are kept in memory for now. TODO: store them in Supabase.
