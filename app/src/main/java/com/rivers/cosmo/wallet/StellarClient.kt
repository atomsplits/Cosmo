package com.rivers.cosmo.wallet

import java.io.Closeable
import java.math.BigDecimal
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.decodeBase64
import org.stellar.sdk.Asset
import org.stellar.sdk.ChangeTrustAsset
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Memo
import org.stellar.sdk.Network
import org.stellar.sdk.Server
import org.stellar.sdk.Transaction
import org.stellar.sdk.TransactionBuilder
import org.stellar.sdk.TransactionBuilderAccount
import org.stellar.sdk.exception.AccountRequiresMemoException
import org.stellar.sdk.exception.BadRequestException
import org.stellar.sdk.exception.NetworkException
import org.stellar.sdk.operations.ChangeTrustOperation
import org.stellar.sdk.operations.CreateAccountOperation
import org.stellar.sdk.operations.PathPaymentStrictSendOperation
import org.stellar.sdk.operations.PaymentOperation
import org.stellar.sdk.requests.RequestBuilder
import org.stellar.sdk.responses.operations.CreateAccountOperationResponse
import org.stellar.sdk.responses.operations.OperationResponse
import org.stellar.sdk.responses.operations.PaymentOperationResponse

enum class StellarNetwork(
    val label: String,
    val horizonUrl: String,
    val passphrase: Network,
    /** Gives out free test XLM; only the test network has one. */
    val friendbotUrl: String?
) {
    Testnet("Testnet (free test XLM)", "https://horizon-testnet.stellar.org", Network.TESTNET, "https://friendbot.stellar.org"),
    Public("Public network (real XLM)", "https://horizon.stellar.org", Network.PUBLIC, null)
}

/** An amount of one asset held by an account; [issuer] is null for XLM. */
data class WalletBalance(val code: String, val issuer: String?, val amount: BigDecimal) {
    val isNative: Boolean get() = issuer == null
    val asset: Asset get() = if (issuer == null) Asset.createNativeAsset() else Asset.createNonNativeAsset(code, issuer)
}

sealed interface AccountState {
    /** Stellar accounts don't exist until someone sends them at least the minimum balance. */
    data object NotActivated : AccountState

    data class Active(val balances: List<WalletBalance>) : AccountState {
        val xlm: BigDecimal get() = balances.firstOrNull { it.isNative }?.amount ?: BigDecimal.ZERO
    }
}

data class PaymentRecord(
    val id: String,
    val incoming: Boolean,
    val counterparty: String,
    val amount: BigDecimal,
    val assetCode: String,
    /** ISO-8601, e.g. 2026-10-25T14:00:00Z. */
    val createdAt: String,
    val transactionHash: String,
    /** Null for XLM. */
    val assetIssuer: String? = null,
    /** The transaction's text memo, if it had one. */
    val memo: String? = null
)

/** A token found on the network by code, before checking who vouches for it. */
data class IssuedAsset(val code: String, val issuer: String, val holders: Int, val tomlUrl: String?)

/** A failure to show the user, already worded for them. */
class WalletException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Talks to a Stellar network through its Horizon API. Every call blocks on the network, so call
 * them from a background thread.
 */
class StellarClient(val network: StellarNetwork) : Closeable {
    private val server = Server(network.horizonUrl)
    private val httpClient = OkHttpClient()

    fun account(accountId: String): AccountState = request {
        try {
            val balances = server.accounts().account(accountId).balances
                .filter { it.assetType != LIQUIDITY_POOL_SHARES }
                .map { balance ->
                    if (balance.assetType == NATIVE) {
                        WalletBalance("XLM", null, BigDecimal(balance.balance))
                    } else {
                        WalletBalance(balance.assetCode, balance.assetIssuer, BigDecimal(balance.balance))
                    }
                }
            AccountState.Active(balances)
        } catch (e: NetworkException) {
            if (!isNotFound(e)) throw e
            AccountState.NotActivated
        }
    }

    /** The account's latest incoming and outgoing payments, newest first. */
    fun recentPayments(accountId: String, limit: Int = 20): List<PaymentRecord> = request {
        try {
            server.payments().forAccount(accountId).order(RequestBuilder.Order.DESC).limit(limit)
                .includeTransactions(true) // for memos, which bill splits use to match payments
                .execute().records
                .mapNotNull { operation ->
                    when (operation) {
                        is PaymentOperationResponse -> PaymentRecord(
                            id = operation.id.toString(),
                            incoming = operation.to == accountId,
                            counterparty = if (operation.to == accountId) operation.from else operation.to,
                            amount = BigDecimal(operation.amount),
                            assetCode = if (operation.assetType == NATIVE) "XLM" else operation.assetCode,
                            createdAt = operation.createdAt,
                            transactionHash = operation.transactionHash,
                            assetIssuer = if (operation.assetType == NATIVE) null else operation.assetIssuer,
                            memo = textMemo(operation)
                        )
                        is CreateAccountOperationResponse -> PaymentRecord(
                            id = operation.id.toString(),
                            incoming = operation.account == accountId,
                            counterparty = if (operation.account == accountId) operation.funder else operation.account,
                            amount = BigDecimal(operation.startingBalance),
                            assetCode = "XLM",
                            createdAt = operation.createdAt,
                            transactionHash = operation.transactionHash,
                            memo = textMemo(operation)
                        )
                        // Other kinds (path payments, account merges) aren't shown yet.
                        else -> null
                    }
                }
        } catch (e: NetworkException) {
            if (!isNotFound(e)) throw e
            emptyList()
        }
    }

    private fun textMemo(operation: OperationResponse): String? =
        operation.transaction?.takeIf { it.memoType == "text" }?.memoValue

    /** Asks the test network's Friendbot for free test XLM, which also activates the account. */
    fun fundWithFriendbot(accountId: String) {
        val friendbot = network.friendbotUrl ?: throw WalletException("Free XLM is only available on the test network.")
        val url = friendbot.toHttpUrl().newBuilder().addQueryParameter("addr", accountId).build()
        val succeeded = try {
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful }
        } catch (e: java.io.IOException) {
            throw WalletException("Couldn't reach Friendbot. Check your internet connection.", e)
        }
        if (!succeeded) throw WalletException("Friendbot didn't send any XLM. This account may already be funded.")
    }

    /** Sends [amount] of [balance]'s asset to [destination] and returns the transaction hash. */
    fun send(keyPair: KeyPair, destination: String, balance: WalletBalance, amount: BigDecimal, memo: String?, typedMemo: Memo? = null): String = request {
        val source = try {
            server.loadAccount(keyPair.accountId)
        } catch (e: NetworkException) {
            if (!isNotFound(e)) throw e
            throw WalletException("Your account isn't activated yet, so it can't send anything.")
        }
        val destinationExists = account(destination) is AccountState.Active
        val transaction = buildTransaction(network, source, keyPair, destination, balance, amount, memo, destinationExists, typedMemo)
        submit(transaction)
    }

    /** The best price on the DEX right now for selling [amount] of [from] for [to], or null if there's no market. */
    fun quoteSwap(from: TradeAsset, amount: BigDecimal, to: TradeAsset): SwapQuote? = request {
        server.strictSendPaths()
            .sourceAsset(from.asset)
            .sourceAmount(amount.toPlainString())
            .destinationAssets(listOf(to.asset))
            .execute().records
            .maxByOrNull { BigDecimal(it.destinationAmount) }
            ?.let { SwapQuote(from, to, amount, BigDecimal(it.destinationAmount), it.path.orEmpty()) }
    }

    /** Makes the trade in [quote] and returns the transaction hash. */
    fun swap(keyPair: KeyPair, quote: SwapQuote, holdings: List<WalletBalance>): String = request {
        val source = try {
            server.loadAccount(keyPair.accountId)
        } catch (e: NetworkException) {
            if (!isNotFound(e)) throw e
            throw WalletException("Your account isn't activated yet, so it can't trade.")
        }
        val needsTrustline = !quote.to.isNative && holdings.none { quote.to.matches(it) }
        submit(buildSwapTransaction(network, source, keyPair, quote, needsTrustline))
    }

    /** Submits a signed transaction and returns its hash. */
    private fun submit(transaction: Transaction): String {
        try {
            return server.submitTransaction(transaction).hash
        } catch (e: AccountRequiresMemoException) {
            throw WalletException("This address needs a memo (exchanges use it to find your account). Add one and try again.", e)
        } catch (e: BadRequestException) {
            throw WalletException(describeFailure(e), e)
        }
    }

    /** All the issuers of tokens with this [code], most widely held first (Horizon's /assets). */
    fun issuersOf(code: String, limit: Int = 200): List<IssuedAsset> = request {
        server.assets().assetCode(code).limit(limit).execute().records
            .filter { it.assetIssuer != null }
            .map { asset ->
                IssuedAsset(
                    code = asset.assetCode,
                    issuer = asset.assetIssuer,
                    holders = asset.accounts?.authorized ?: 0,
                    tomlUrl = asset.links?.toml?.href?.takeIf { it.startsWith("https://") }
                )
            }
            .sortedByDescending { it.holders }
    }

    override fun close() {
        server.close()
        httpClient.dispatcher.executorService.shutdown()
    }

    /** Turns connection problems into a [WalletException] the screens can show as-is. */
    private fun <T> request(block: () -> T): T = try {
        block()
    } catch (e: WalletException) {
        throw e
    } catch (e: NetworkException) {
        throw WalletException("The Stellar network returned an error (${e.code ?: "no response"}). Try again in a moment.", e)
    } catch (e: Exception) {
        throw WalletException("Couldn't reach the Stellar network. Check your internet connection.", e)
    }

    companion object {
        const val NATIVE = "native"
        private const val LIQUIDITY_POOL_SHARES = "liquidity_pool_shares"

        /** A new Stellar account must start with at least 1 XLM (two base reserves of 0.5 XLM). */
        val MIN_STARTING_BALANCE = BigDecimal("1")

        /** Memos are at most 28 bytes. */
        const val MAX_MEMO_BYTES = 28

        // 0.0001 XLM per operation: ten times the minimum, so payments still go through when the
        // network is busy, while costing a fraction of a cent.
        private const val BASE_FEE = 1_000L
        private const val TIMEOUT_SECONDS = 180L

        /**
         * Horizon answers 404 for accounts that don't exist yet (nobody has sent them XLM). The SDK
         * reports that as a plain [BadRequestException] with code 404, not AccountNotFoundException,
         * which only its Soroban client throws, so check the code instead of the exception type.
         */
        fun isNotFound(e: NetworkException): Boolean = e.code == 404

        /** Builds and signs a payment, or an account creation when [destinationExists] is false. */
        fun buildTransaction(
            network: StellarNetwork,
            source: TransactionBuilderAccount,
            keyPair: KeyPair,
            destination: String,
            balance: WalletBalance,
            amount: BigDecimal,
            memo: String?,
            destinationExists: Boolean,
            /** An id or hash memo, as anchors ask for; replaces [memo] when set. */
            typedMemo: Memo? = null
        ): Transaction {
            if (destination == keyPair.accountId) throw WalletException("You can't send to your own address.")
            val operation = if (destinationExists) {
                PaymentOperation.builder().destination(destination).asset(balance.asset).amount(amount).build()
            } else {
                if (!balance.isNative) throw WalletException("That address isn't activated, so it can only receive XLM.")
                if (amount < MIN_STARTING_BALANCE) {
                    throw WalletException("That address isn't activated yet. Send at least $MIN_STARTING_BALANCE XLM to activate it.")
                }
                CreateAccountOperation.builder().destination(destination).startingBalance(amount).build()
            }
            val builder = TransactionBuilder(source, network.passphrase)
                .addOperation(operation)
                .setBaseFee(BASE_FEE)
                .setTimeout(TIMEOUT_SECONDS)
            if (typedMemo != null) builder.addMemo(typedMemo)
            else memo?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                if (text.toByteArray(Charsets.UTF_8).size > MAX_MEMO_BYTES) throw WalletException("Memos can be at most $MAX_MEMO_BYTES characters.")
                builder.addMemo(Memo.text(text))
            }
            return builder.build().also { it.sign(keyPair) }
        }

        /** The most a trustline can hold: Stellar's maximum amount. */
        private val MAX_TRUST_LIMIT = BigDecimal("922337203685.4775807")

        /**
         * Builds and signs a trade: a path payment to the user's own account, which sells exactly
         * the quoted amount and fails rather than receive less than [SwapQuote.minimumReceived].
         * When the user doesn't hold [SwapQuote.to] yet, the same transaction first adds a trustline
         * for it (which sets aside 0.5 XLM), so either both happen or neither does.
         */
        fun buildSwapTransaction(
            network: StellarNetwork,
            source: TransactionBuilderAccount,
            keyPair: KeyPair,
            quote: SwapQuote,
            needsTrustline: Boolean
        ): Transaction {
            if (quote.from.key == quote.to.key) throw WalletException("Pick two different assets to trade.")
            val builder = TransactionBuilder(source, network.passphrase).setBaseFee(BASE_FEE).setTimeout(TIMEOUT_SECONDS)
            if (needsTrustline) {
                builder.addOperation(ChangeTrustOperation.builder().asset(ChangeTrustAsset(quote.to.asset)).limit(MAX_TRUST_LIMIT).build())
            }
            builder.addOperation(
                PathPaymentStrictSendOperation.builder()
                    .sendAsset(quote.from.asset)
                    .sendAmount(quote.sendAmount)
                    .destination(keyPair.accountId)
                    .destAsset(quote.to.asset)
                    .destMin(quote.minimumReceived)
                    .path(quote.path.toTypedArray())
                    .build()
            )
            return builder.build().also { it.sign(keyPair) }
        }

        /** The memo an anchor asks for: SEP-24 gives its type ("text", "id" or "hash", base64) and value. */
        fun anchorMemo(type: String?, value: String): Memo = when (type) {
            "id" -> Memo.id(value.toBigIntegerOrNull()?.takeIf { it.signum() >= 0 && it.bitLength() <= 64 } ?: throw WalletException("The anchor sent an invalid memo."))
            "hash" -> Memo.hash(value.decodeBase64()?.toByteArray()?.takeIf { it.size == 32 } ?: throw WalletException("The anchor sent an invalid memo."))
            else -> {
                if (value.toByteArray(Charsets.UTF_8).size > MAX_MEMO_BYTES) throw WalletException("The anchor sent an invalid memo.")
                Memo.text(value)
            }
        }

        /** Explains Horizon's result codes, see developers.stellar.org/docs/data/horizon/api-reference/errors/result-codes. */
        fun describeFailure(e: BadRequestException): String {
            val codes = e.problem?.extras?.resultCodes
            val operationCode = codes?.operationsResultCodes?.firstOrNull { it != "op_success" }
            return when (operationCode ?: codes?.transactionResultCode) {
                "op_underfunded" -> "You don't have enough of that asset to send this amount."
                "op_under_dest_min" -> "The price moved before your trade went through. Get a new quote and try again."
                "op_too_few_offers" -> "There isn't enough on offer to fill this trade right now. Try a smaller amount."
                "op_cross_self" -> "This trade would fill one of your own offers."
                "op_low_reserve" -> "This would take your XLM below the minimum balance your account must keep."
                "op_no_trust" -> "The recipient can't receive this asset yet (they need to add a trustline for it)."
                "op_not_authorized", "op_src_not_authorized" -> "The asset's issuer hasn't authorised this payment."
                "op_line_full" -> "The recipient can't hold any more of this asset."
                "op_no_destination" -> "That address doesn't exist on this network."
                "op_already_exists" -> "That account already exists."
                "tx_insufficient_balance" -> "You don't have enough XLM to cover this payment and its fee."
                "tx_insufficient_fee" -> "The network is busy right now. Try again in a moment."
                "tx_bad_seq" -> "Another transaction was sent from this account at the same time. Try again."
                "tx_too_late" -> "The payment took too long to send. Try again."
                else -> "The payment was rejected (${operationCode ?: codes?.transactionResultCode ?: "unknown reason"})."
            }
        }
    }
}
