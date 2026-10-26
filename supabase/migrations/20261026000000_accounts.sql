-- Cosmo accounts: a Cosmo ID and a hash derived from its 12-word recovery phrase, for logging in.
--
-- The recovery phrase never leaves the phone. The app derives a one-way "verifier" from it
-- (PBKDF2-HMAC-SHA256, salted with the Cosmo ID, see app/.../auth/PhraseVerifier.kt), and this
-- table stores only a bcrypt hash of that verifier. So the server can check a log-in, but neither
-- Supabase nor anyone who copies this table can work out the phrase, the wallet key, or even the
-- value to log in with.
--
-- The table isn't readable or writable by the app's roles at all; the two functions below are
-- the only way in.

-- Supabase has pgcrypto installed in the "extensions" schema already; this is for fresh databases.
create schema if not exists extensions;
create extension if not exists pgcrypto with schema extensions;

create table public.accounts (
    -- Same format the app generates: COSMO-XXXX-XXXX, without the easily confused 0/O, 1/I/L, U/V.
    cosmo_id text primary key
        check (cosmo_id ~ '^COSMO-[A-HJKMNP-TW-Z2-9]{4}-[A-HJKMNP-TW-Z2-9]{4}$'),
    -- bcrypt hash of the phrase verifier.
    phrase_hash text not null,
    created_at timestamptz not null default now()
);

alter table public.accounts enable row level security;
revoke all on table public.accounts from public, anon, authenticated;

-- Creates an account. Returns false if the Cosmo ID is already taken (nothing is changed then).
create function public.register_account(p_cosmo_id text, p_verifier text)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
begin
    -- Checked here rather than left to the table's constraint, whose error would echo the row back.
    if p_cosmo_id is null or p_cosmo_id !~ '^COSMO-[A-HJKMNP-TW-Z2-9]{4}-[A-HJKMNP-TW-Z2-9]{4}$' then
        raise exception 'invalid cosmo id' using errcode = '22023';
    end if;
    -- The verifier is 32 bytes as lowercase hex; anything else isn't from the app.
    if p_verifier is null or p_verifier !~ '^[0-9a-f]{64}$' then
        raise exception 'invalid verifier' using errcode = '22023';
    end if;
    insert into public.accounts (cosmo_id, phrase_hash)
    values (p_cosmo_id, extensions.crypt(p_verifier, extensions.gen_salt('bf', 10)))
    on conflict (cosmo_id) do nothing;
    return found;
end;
$$;

-- Checks a log-in: 'ok', 'mismatch' (wrong phrase), or 'unknown' (no such Cosmo ID).
create function public.verify_account(p_cosmo_id text, p_verifier text)
returns text
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    stored text;
begin
    select phrase_hash into stored from public.accounts where cosmo_id = p_cosmo_id;
    if stored is null then
        return 'unknown';
    end if;
    if p_verifier is not null and extensions.crypt(p_verifier, stored) = stored then
        return 'ok';
    end if;
    return 'mismatch';
end;
$$;

-- Callable by the app with the project's public (anon) key, through /rest/v1/rpc/…
revoke all on function public.register_account(text, text) from public;
revoke all on function public.verify_account(text, text) from public;
grant execute on function public.register_account(text, text) to anon, authenticated;
grant execute on function public.verify_account(text, text) to anon, authenticated;
