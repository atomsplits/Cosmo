# Cosmo server

A small server for the Cosmo app. It relays end-to-end encrypted chat messages between Cosmo
users.

Identity verification doesn't go through this server: the app uses Stellar's KYC API (SEP-12)
directly with the user's anchor, signing in with their wallet (SEP-10), so personal details go
straight from the phone to the anchor.

The chat relay passes messages between users.
It only ever handles PGP ciphertext: messages are encrypted and signed on the sender's phone and
can only be decrypted on the recipient's phone (and the sender's, for their own history).

## Running it

From the repository root, with any JDK 17 or newer (Android Studio's bundled JDK works):

```sh
./gradlew :server:run            # listens on port 8080
PORT=9000 ./gradlew :server:run     # or pick a port
./gradlew :server:test           # runs the relay tests
```

Then point the app at it in **Settings → Cosmo server**:

- **Android emulator:** `ws://10.0.2.2:8080/chat` (the default). `10.0.2.2` is the emulator's
  address for your computer.
- **Your phone:** `ws://<your computer's Wi-Fi IP>:8080/chat`, e.g. `ws://192.168.1.20:8080/chat`.
  The phone and computer must be on the same network, and your firewall must allow port 8080.

Plain `ws://` only works in debug builds (see `app/src/debug/res/xml/network_security_config.xml`).
Release builds require `wss://`, so deploy the relay behind TLS before shipping.

### Testing on your phone

The server runs on your computer; the app connects to it.

1. Start it: `./gradlew :server:run`. It prints the addresses to use.
2. In the app, open Settings → Cosmo server, choose one of these, then tap **Save and reconnect**:
   - **Phone on USB** (easiest): with the phone plugged in and USB debugging on, run
     `adb reverse tcp:8080 tcp:8080` on the computer (again after each reconnect), then pick the
     "Phone on USB" preset (`ws://localhost:8080/chat`).
   - **Emulator**: the "Emulator" preset (`ws://10.0.2.2:8080/chat`).
   - **Same Wi-Fi**: the `ws://192.168.…:8080/chat` address the server printed. If it doesn't
     connect, allow Java through the computer's firewall for private networks.
3. Chat → the status shows "Connected". To try chat and the marketplace between two people, use
   two devices (or a phone and the emulator) with different accounts.

Plain `ws://` only works in debug builds; release builds need `wss://` (a server with TLS).

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

## Marketplace

Logged-in users can list digital goods for sale. The server only keeps the shop window (in
memory for now; TODO: Supabase): the seller's Cosmo ID always comes from their login, never from
the frame.

| Direction | Frame |
| --- | --- |
| app → server | `{"type":"publish_listing","listing":{"id":"<uuid>","title":"…","description":"…","price":"12.5","sellerAddress":"G…","kind":"text"}}` |
| app → server | `{"type":"unpublish_listing","id":"<uuid>"}` |
| app → server | `{"type":"browse_listings","requestId":"<uuid>"}` |
| server → app | `{"type":"listings","requestId":"<uuid>","listings":[{…,"sellerId":"COSMO-…","publishedAt":1761400000000}]}` |

Problems with a listing come back as an `error` frame whose `id` is the listing's.

The sale itself never goes through the server in readable form. Buyer and seller apps exchange
their offer and payment details as ordinary end-to-end encrypted chat messages, and the payment is
a hash- and time-locked escrow on Stellar (see `app/src/main/java/com/rivers/cosmo/wallet/Escrow.kt`).
The relay allows messages up to 100 KB so a seller's goods (up to 48 KB) fit in one.

