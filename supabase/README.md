# Supabase

Database migrations for Cosmo's Supabase project. With the GitHub integration connected, they're
applied automatically when they reach the production branch; otherwise run `supabase db push`.

| Migration | What it adds |
| --- | --- |
| `…_public_keys.sql` | Public PGP keys per Cosmo ID (not used by the app yet) |
| `…_accounts.sql` | Accounts: a Cosmo ID and a hash derived from its recovery phrase, for logging in |

## Connecting the app

The app needs the project's URL and its **anon (public) key**, from Project Settings → API. Put them
in `local.properties` at the repository root (it's git-ignored), then rebuild:

```properties
supabase.url=https://<project-ref>.supabase.co
supabase.anonKey=<anon key>
```

On CI, set `SUPABASE_URL` and `SUPABASE_ANON_KEY` instead. Never use the `service_role` key in the
app. Without these settings the app keeps accounts on the device only, as it did before.

## How accounts are stored

The 12-word recovery phrase is also the user's Stellar wallet key, so it never leaves the phone.

1. The app derives a verifier from it: PBKDF2-HMAC-SHA256, 100,000 rounds, salted with
   `cosmo-auth-v1:<Cosmo ID>` (`app/.../auth/PhraseVerifier.kt`). It's one-way and unrelated to the
   wallet key, which BIP-39 derives from the same phrase differently.
2. Supabase stores only a bcrypt hash of that verifier (`accounts.phrase_hash`).

So someone with a copy of the table can't recover phrases or wallets, and can't log in either: the
stored value isn't what the app sends. The app's anon key can't read or write the table at all.
It can only call two functions:

| Function (`POST /rest/v1/rpc/…`) | Returns |
| --- | --- |
| `register_account(p_cosmo_id, p_verifier)` | `true`, or `false` if the Cosmo ID is taken |
| `verify_account(p_cosmo_id, p_verifier)` | `"ok"`, `"mismatch"` or `"unknown"` |

The phone also remembers accounts it has used, so logging in works offline, and accounts created on
a phone before Supabase was connected are moved to Supabase the next time they log in.

These functions check a log-in; they don't issue Supabase sessions yet. When data needs per-user
row-level security, the next step is minting a session after `verify_account` succeeds (for
example from an Edge Function), rather than trusting anything the app claims.
