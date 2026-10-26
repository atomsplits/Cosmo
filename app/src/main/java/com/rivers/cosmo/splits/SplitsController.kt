package com.rivers.cosmo.splits

import android.content.Context
import com.rivers.cosmo.util.backgroundScope
import com.rivers.cosmo.wallet.WalletController
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The signed-in user's bill splits: ones they organize and ones they joined from an invite. Splits
 * are saved on this device as their signed invites; payment status is worked out from the wallet's
 * Stellar payment history.
 */
class SplitsController(context: Context, private val cosmoId: String, private val wallet: WalletController) {

    /** A saved split. [myShare] is the share this user pays (null for the organizer, or if not chosen yet). */
    data class Entry(val split: BillSplit, val signed: SplitInvite.Signed, val organizer: Boolean, val myShare: Int?)

    data class SplitView(val entry: Entry, val statuses: List<SplitStatus.ShareStatus?>) {
        val split: BillSplit get() = entry.split
        val collected: BigDecimal get() = SplitStatus.collected(statuses)
        val paidCount: Int get() = statuses.count { it?.complete == true }
        val myStatus: SplitStatus.ShareStatus? get() = entry.myShare?.let { statuses.getOrNull(it) }
    }

    data class State(val splits: List<SplitView> = emptyList(), val loading: Boolean = false, val error: String? = null)

    private val prefs = context.getSharedPreferences("cosmo_splits", Context.MODE_PRIVATE)
    private val key = "splits_$cosmoId"
    private val scope = backgroundScope("bill splits", Dispatchers.IO)
    private val _state = MutableStateFlow(State(load().map { SplitView(it, emptyStatuses(it)) }))
    val state: StateFlow<State> = _state.asStateFlow()

    fun refresh() {
        scope.launch {
            _state.update { it.copy(loading = true) }
            val address = wallet.state.value.address
            val payments = runCatching { wallet.paymentsForSplits() }
            _state.value = State(
                splits = load().map { entry ->
                    val statuses = if (address != null && payments.isSuccess) {
                        SplitStatus.compute(entry.split, address, payments.getOrThrow())
                    } else {
                        emptyStatuses(entry)
                    }
                    SplitView(entry, statuses)
                },
                error = payments.exceptionOrNull()?.message
            )
        }
    }

    /** Creates and saves a split organized by this user, paid into their wallet. */
    fun create(title: String, assetCode: String, assetIssuer: String?, shares: List<SplitShare>): Result<Entry> = runCatching {
        val walletState = wallet.state.value
        val address = walletState.address ?: throw IllegalStateException("Your wallet key isn't on this device.")
        require(shares.size in 2..BillSplit.MAX_SHARES) { "A split needs between 2 and ${BillSplit.MAX_SHARES} people." }
        val split = BillSplit(
            id = BillSplit.newId(),
            title = title.trim().take(60),
            organizerCosmoId = cosmoId,
            organizerAddress = address,
            network = walletState.network,
            assetCode = assetCode,
            assetIssuer = assetIssuer,
            shares = shares,
            createdAt = System.currentTimeMillis()
        )
        val signed = wallet.signInvite(split) ?: throw IllegalStateException("Your wallet key isn't on this device.")
        Entry(split, signed, organizer = true, myShare = null).also { save(load() + it); refresh() }
    }

    /** Checks an invite link and saves the split it describes. */
    fun join(link: String, shareIndex: Int?): Result<Entry> = runCatching {
        val opened = SplitInvite.open(link)
        val split = opened.split
        val address = wallet.state.value.address
        if (address == split.organizerAddress) throw InvalidInviteException("This is your own bill split.")
        if (split.network != wallet.state.value.network) {
            throw InvalidInviteException("This split is on the ${split.network.label}. Switch networks in Settings to join it.")
        }
        if (shareIndex != null && (shareIndex !in split.shares.indices || split.shares[shareIndex].isOrganizer)) {
            throw InvalidInviteException("Pick one of the participants' shares.")
        }
        val entry = Entry(split, opened.signed, organizer = false, myShare = shareIndex)
        save(load().filterNot { it.split.id == split.id && it.split.organizerAddress == split.organizerAddress } + entry)
        refresh()
        entry
    }

    fun chooseShare(splitId: String, shareIndex: Int) {
        save(load().map { if (it.split.id == splitId && !it.organizer) it.copy(myShare = shareIndex) else it })
        refresh()
    }

    /** The invite link for one participant's share, or for everyone if [shareIndex] is null. */
    fun inviteLink(entry: Entry, shareIndex: Int?): String = SplitInvite.link(entry.signed, shareIndex)

    fun delete(splitId: String) {
        save(load().filterNot { it.split.id == splitId })
        refresh()
    }

    fun close() = scope.cancel()

    private fun emptyStatuses(entry: Entry): List<SplitStatus.ShareStatus?> =
        entry.split.shares.mapIndexed { i, share -> if (share.isOrganizer) SplitStatus.ShareStatus(i, share.amount, true) else null }

    private fun load(): List<Entry> {
        val array = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val item = array.getJSONObject(i)
                val signed = SplitInvite.Signed(item.getString("d"), item.getString("s"))
                Entry(SplitInvite.verify(signed), signed, item.getBoolean("organizer"), if (item.isNull("share")) null else item.getInt("share"))
            }.getOrNull()
        }
    }

    private fun save(entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach {
            array.put(
                JSONObject()
                    .put("d", it.signed.payload)
                    .put("s", it.signed.signature)
                    .put("organizer", it.organizer)
                    .put("share", it.myShare ?: JSONObject.NULL)
            )
        }
        prefs.edit().putString(key, array.toString()).apply()
    }
}
