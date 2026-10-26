<div align="center">

# Cosmo

**A Stellar wallet that feels like a messaging app: pay, split, trade, chat and sell to friends, with no username, no email and no custodian.**

Built at **HackMeridian 2026 · Lisbon** (25–26 October 2026)

`Genesis path` · `Stellar Unlocked` · `Stellar Composed` · Android · Kotlin · Jetpack Compose · Stellar Testnet

[Demo video](#demo) · [Pitch deck](#demo) · [How it uses Stellar](#how-cosmo-uses-stellar) · [Run it](#run-it-locally)

</div>

---

## The problem

Sending money to a friend on Stellar takes seconds and costs a fraction of a cent, but the apps
around it still feel like tools for traders. You copy 56-character addresses, manage trustlines by
hand, leave the wallet to chat about the payment, and leave again to cash out. Splitting a dinner
or selling a digital good to someone you met online means trusting a platform that holds your
money, or trusting the other person.

Standard wallets also all look and work the same. You get a balance, a send button and a
transaction list in a fixed layout. You can't rearrange the screen around what you use, change
how it looks beyond light or dark mode, or add the small tools you'd want every day, like a
savings goal, a tip jar or a summary of your spending. Everyday conveniences, such as scanning a
QR code to join a shared bill, getting notified when a friend messages you, or seeing who still
owes their share, are missing or live in separate apps. A wallet is something people open every
day, but most feel like a bank statement rather than an app built for them.

## What Cosmo does

Cosmo is a self-custodial Android wallet built around people instead of addresses. Every user gets
a **Cosmo ID** (`COSMO-7KQ2-M9XD`) and a 12-word recovery phrase. The phrase is the wallet, and it
never leaves the phone.

| | Feature | What happens |
| --- | --- | --- |
| 💸 | **Payments** | Send XLM or any trusted asset to a Cosmo ID, a Stellar address or a QR code. |
| 🧾 | **Bill splits** | Create a shared bill. Each person gets a signed invite link or QR code, pays their share with a memo, and progress is tracked on the ledger. No server is involved. |
| 🔁 | **Trade** | Swap XLM for BTC, ETH, LTC, USDC and other tokens on Stellar's built-in DEX, with a live quote and a slippage limit. Only tokens vouched for by a `stellar.toml` are offered. |
| 🏦 | **On and off ramps** | Buy XLM with a card or cash out to a bank account through Stellar anchors. Card purchases are locked until the anchor has verified the user's identity. |
| 🪪 | **KYC** | Identity verification goes directly to the anchor through Stellar's KYC API. Cosmo never sees the documents. |
| 🛒 | **Marketplace** | Sell digital goods (files, codes, text) for XLM. Every sale is protected by an on-chain escrow that neither side controls alone. |
| 🔐 | **Encrypted chat** | End-to-end PGP-encrypted messages between Cosmo IDs, with key pinning, unread badges and notifications. |
| 🧩 | **Plugins** | Users can add their own dashboard widgets in sandboxed JavaScript. Plugins never see keys and can't send payments. |
| 🎨 | **Make it yours** | Drag-and-resize dashboards on every tab, themes shareable by QR code, custom `.woff2` fonts, animated backgrounds. |

## How Cosmo uses Stellar

Cosmo uses Stellar's own protocol features rather than smart contracts. This is the core of the
project.

### Stellar Unlocked: protocol features doing real work

- **Trustless marketplace escrow from classic primitives.** Each sale opens a temporary escrow
  account ([`Escrow.kt`](app/src/main/java/com/rivers/cosmo/wallet/Escrow.kt)) that works like a
  hash- and time-locked contract without Soroban:
  - **Multisig.** The account needs 2 votes. The seller's key gives one and a SHA-256 hash lock
    (`sha256Hash` signer) gives the other.
  - **Pre-authorized transaction.** The buyer's refund (`preAuthTx` signer) is signed before any
    money moves, and it only becomes valid after the deadline.
  - **Time bounds.** These enforce the deadline, plus a safety margin so a release can't race a
    refund.
  - **Disabled master key.** Once the escrow is locked, even the buyer can't touch it.
  - **How the seller gets paid.** The seller can only collect by revealing the hash preimage,
    which is the decryption key for the goods they already sent. Getting paid publishes that key on
    the ledger, where the buyer's app reads it and unlocks the purchase. If the seller never
    collects, the buyer takes a refund after the deadline. No arbiter, no custodian.
- **Path payments for swaps.** Trades use `PathPaymentStrictSend` across the DEX, with quotes from
  Horizon's path finder and a minimum-received guard
  ([`Trading.kt`](app/src/main/java/com/rivers/cosmo/wallet/Trading.kt)).
- **Memos as an accounting layer.** Bill split shares are matched to payments by memo, so the
  ledger itself is the split's database.
- **Ed25519 signatures off-chain.** A split invite is signed with the organizer's Stellar key, so
  anyone can check that the amounts and destination weren't altered.

### Stellar Composed: the anchor ecosystem

| Standard | Used for |
| --- | --- |
| **SEP-1** `stellar.toml` | Discovering anchors and checking that a token is really vouched for by its issuer's domain |
| **SEP-10** Web Authentication | Signing in to anchors with the wallet's key |
| **SEP-12** KYC API | Identity verification status and fields, sent straight to the anchor |
| **SEP-24** Interactive deposit/withdraw | Buying XLM with a card, cashing out to a bank, and following each transfer's progress |

On testnet, everything runs against `testanchor.stellar.org`. More anchors can be added in
Settings → Anchors.

## Architecture

```
┌──────────────────────────── Android app (Kotlin, Compose) ────────────────────────────┐
│  Wallet · Payments · Splits · Transfers · Marketplace · Chat · Settings · Plugins      │
│                                                                                        │
│  Recovery phrase ──► Stellar keys (BIP-39 / SEP-5), encrypted with Android Keystore    │
│                 └──► PBKDF2 verifier (one-way) ──────────────────────┐                 │
└───────┬──────────────────────┬────────────────────────┬──────────────┼─────────────────┘
        │ Horizon              │ SEP-1/10/12/24         │ WebSocket    │ PostgREST RPC
        ▼                      ▼                        ▼              ▼
  Stellar network         Anchors                 Cosmo relay       Supabase
  payments, DEX,          card on-ramp,           (Ktor)            accounts (bcrypt of the
  escrow accounts         bank off-ramp, KYC      encrypted chat,   verifier), public chat keys
                                                  marketplace
                                                  listings
```

- **The app** ([`app/`](app)) does all signing and encryption on the device.
- **The relay** ([`server/`](server)) only ever sees PGP ciphertext and public listings. It routes
  messages by Cosmo ID and queues them for offline users.
- **Supabase** ([`supabase/`](supabase)) stores accounts as a bcrypt hash of a one-way verifier
  derived from the phrase, plus public chat keys. The app's anon key can only call
  `security definer` functions. It can't read the tables.

### Security choices

- The recovery phrase never leaves the phone. It's stored encrypted with an Android Keystore key,
  and recovery screens block screenshots by default.
- Supabase can't recover phrases or wallets, and a copy of its table can't be used to log in.
- Chat uses trust-on-first-use key pinning. A changed key blocks sending until the user checks it.
- Card and bank details are entered on the anchor's own page and never pass through Cosmo.
- Plugins run in a Rhino sandbox: no Java, network or file access, and a CPU budget on every call.

## Run it locally

**Requirements:** Android Studio (AGP 9.1), JDK 17 (Gradle downloads it if missing), an Android
device or emulator on API 24 or later.

1. **Clone and open the project** in Android Studio.
2. **Optional: set up Supabase accounts.** Apply [`supabase/migrations`](supabase/migrations) to a
   project (see [`supabase/README.md`](supabase/README.md)), then add the following to
   `local.properties`:
   ```properties
   supabase.url=https://<project-ref>.supabase.co
   supabase.anonKey=<anon key>
   ```
   Without these, accounts stay on the device.
3. **Start the chat and marketplace relay:**
   ```sh
   ./gradlew :server:run          # listens on ws://0.0.0.0:8080/chat
   ```
   On a phone connected over USB, run `adb reverse tcp:8080 tcp:8080` and choose
   **Settings → Cosmo server → Phone on USB**. The emulator preset works as is.
4. **Run the `app` configuration.** Sign up, then fund the new testnet account from the wallet
   tab (Friendbot).

**Tests:**

```sh
./gradlew :app:testDebugUnitTest   # wallet, escrow, splits, trading, chat protocol, auth, plugins…
./gradlew :server:test             # relay log-in, routing, offline queue, marketplace
```

## Demo

| | Link |
| --- | --- |
| 🎥 Demo video | _https://www.youtube.com/watch?v=2fXxoGjBR3s_ |
| 📊 Pitch deck | _https://smallpdf.com/file#s=70005e22-c30e-454e-834d-f04549ea2669_ |
| 📱 APK | _https://github.com/atomsplits/Cosmo/releases/tag/alpha_ |

**Suggested demo flow (about 3 minutes):**

1. Sign up: you get a Cosmo ID and phrase, and there's no email.
2. Fund the account on testnet and pay a friend's Cosmo ID.
3. Split a dinner: share the QR code, the friend pays, and progress updates from the ledger.
4. Swap XLM for a testnet token via path payment.
5. List a file on the Marketplace. The buyer funds the escrow, the seller's app collects
   automatically, and the buyer's file unlocks.
6. Chat about it end-to-end encrypted, with a notification on the other phone.
7. Verify identity with the test anchor, then buy XLM with a card.

## Judging criteria at a glance

| Criterion | Where to look |
| --- | --- |
| **Problem clarity** | [The problem](#the-problem): payments between people still feel like trading tools, selling to strangers needs a trusted middleman, and wallets are bland, rigid and short on everyday conveniences. |
| **MVP quality** | A complete Android app with 8 working areas, a relay server, a database schema, and unit tests across the wallet, escrow, splits, trading, chat and plugins. |
| **Stellar integration** | Multisig, pre-authorized transactions, hash-lock signers, time bounds, path payments, memos, and SEP-1, SEP-10, SEP-12 and SEP-24 ([details](#how-cosmo-uses-stellar)). |
| **Continued development** | See the [roadmap](#roadmap). |

## Roadmap

- [ ] Mainnet launch with production anchors (on/off ramps in EUR, BRL and NGN)
- [ ] Push notifications (FCM) so chat and sales reach users while the app is closed
- [ ] Move relay state (keys, listings, offline queue) to Supabase so it survives restarts
- [ ] Optional Soroban escrow contract for physical goods with dispute resolution
- [ ] Contacts and payment requests by Cosmo ID across devices
- [ ] Security review of key storage and the escrow flow; apply to the Stellar Community Fund

## Project structure

```
app/          Android app
  wallet/       Stellar client, keys, trading, escrow
  anchor/       SEP-1, SEP-10, SEP-12, SEP-24
  market/       marketplace orders and protocol
  splits/       bill splits and signed invites
  chat/         PGP chat, key directory, notifications
  plugins/      sandboxed JavaScript widgets
  ui/           Compose screens
server/       Ktor WebSocket relay for chat and marketplace listings
supabase/     database migrations (accounts, public chat keys)
docs/plugins/ plugin guide and examples
```

## Status and limitations

This is a hackathon build. It runs on **Stellar Testnet** by default.

- The relay keeps sessions, listings and the offline queue in memory.
- Chat notifications only arrive while the app is running.
- The code hasn't been audited, so don't use it with real funds on mainnet yet.

## Team

_Team Rivers -> Maxim Pankratov: UX, Programming, Design, Pitch Deck, Demo_

---

<div align="center">
Built on <a href="https://stellar.org">Stellar</a> for HackMeridian 2026 · Lisbon
</div>
