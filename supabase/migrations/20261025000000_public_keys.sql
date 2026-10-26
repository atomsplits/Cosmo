-- Public PGP keys for Cosmo chat, one per Cosmo ID.
-- The app publishes its key here through publish_public_key (…_publish_public_key.sql) and
-- looks contacts up here when the chat server doesn't know them.
-- Apply with the Supabase CLI: supabase db push. Safe to run more than once.

create table if not exists public.public_keys (
    -- Same format the app generates: COSMO-XXXX-XXXX, without the easily confused 0/O, 1/I/L, U/V.
    cosmo_id text primary key
        check (cosmo_id ~ '^COSMO-[A-HJKMNP-TW-Z2-9]{4}-[A-HJKMNP-TW-Z2-9]{4}$'),
    -- OpenPGP v4 fingerprint (40 hex characters), so clients can check a key without parsing it.
    fingerprint text not null check (fingerprint ~ '^[0-9A-F]{40}$'),
    armored_public_key text not null check (length(armored_public_key) <= 16384),
    created_at timestamptz not null default now()
);

alter table public.public_keys enable row level security;

-- Public keys are meant to be public: anyone may look one up.
drop policy if exists "Public keys are readable by everyone" on public.public_keys;
create policy "Public keys are readable by everyone"
    on public.public_keys for select
    using (true);

-- No insert/update/delete policies on purpose. Whoever registers a key must prove they hold the
-- matching secret key (as the relay does with its signed challenge), so writes should go through
-- an Edge Function using the service role, not straight from the app.
