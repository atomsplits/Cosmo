package com.rivers.cosmo.chat

/** Looks up the armored public key that belongs to a Cosmo ID. */
fun interface PublicKeyDirectory {
    /** Returns null when that Cosmo ID has no published key. */
    suspend fun fetch(cosmoId: String): String?
}

/** A directory the app can also publish its own key to (Supabase; see [SupabaseKeyDirectory]). */
interface ChatKeyServer : PublicKeyDirectory {
    /**
     * Publishes [armoredKey] as [cosmoId]'s chat key. [verifier] proves the account is ours (the
     * same value as logging in sends). Returns false if the server didn't accept it.
     */
    suspend fun publish(cosmoId: String, verifier: String, fingerprint: String, armoredKey: String): Boolean
}
