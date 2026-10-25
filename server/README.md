# Cosmo chat relay

A small WebSocket server that passes end-to-end encrypted chat messages between Cosmo users.
It only ever handles PGP ciphertext: messages are encrypted and signed on the sender's phone and
can only be decrypted on the recipient's phone (and the sender's, for their own history).

## Running it

From the repository root, with any JDK 17 or newer (Android Studio's bundled JDK works):

```sh
./gradlew -p server run          # listens on port 8080
PORT=9000 ./gradlew -p server run   # or pick a port
./gradlew -p server test         # runs the relay tests
```

Then point the app at it in **Settings → Chat server**:

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
