package com.rivers.cosmo.chat

/**
 * Looks up the armored public key that belongs to a Cosmo ID.
 *
 * For now the chat relay answers these lookups (see [ChatService]).
 * TODO: Add a SupabaseKeyDirectory that reads the public_keys table from
 *  supabase/migrations, and publish each user's key there when they sign up.
 */
fun interface PublicKeyDirectory {
    /** Returns null when that Cosmo ID has no published key. */
    suspend fun fetch(cosmoId: String): String?
}
