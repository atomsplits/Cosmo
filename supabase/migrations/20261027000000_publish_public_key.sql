-- Lets the app publish its chat (PGP) public key to public.public_keys, so chat can find a Cosmo
-- ID's key even when that user isn't connected to the chat server, or after the server restarts.
--
-- Publishing needs the same phrase verifier as logging in (see …_accounts.sql), so only whoever
-- holds an account's recovery phrase can set its key. Publishing again replaces the key: logging
-- in on a new phone gives that phone a new chat key, and contacts are then warned that it changed.
--
-- Safe to run more than once.

create or replace function public.publish_public_key(
    p_cosmo_id text,
    p_verifier text,
    p_fingerprint text,
    p_armored_key text
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    stored text;
begin
    if p_fingerprint is null or p_fingerprint !~ '^[0-9A-F]{40}$' then
        raise exception 'invalid fingerprint' using errcode = '22023';
    end if;
    if p_armored_key is null
        or length(p_armored_key) > 16384
        or p_armored_key not like '-----BEGIN PGP PUBLIC KEY BLOCK-----%' then
        raise exception 'invalid public key' using errcode = '22023';
    end if;

    select phrase_hash into stored from public.accounts where cosmo_id = p_cosmo_id;
    if stored is null or p_verifier is null or extensions.crypt(p_verifier, stored) <> stored then
        return false;
    end if;

    insert into public.public_keys (cosmo_id, fingerprint, armored_public_key)
    values (p_cosmo_id, p_fingerprint, p_armored_key)
    on conflict (cosmo_id) do update
        set fingerprint = excluded.fingerprint,
            armored_public_key = excluded.armored_public_key,
            created_at = now();
    return true;
end;
$$;

revoke all on function public.publish_public_key(text, text, text, text) from public;
grant execute on function public.publish_public_key(text, text, text, text) to anon, authenticated;

-- Reading keys goes through PostgREST (GET /rest/v1/public_keys); the select policy is in …_public_keys.sql.
grant select on table public.public_keys to anon, authenticated;

notify pgrst, 'reload schema';
