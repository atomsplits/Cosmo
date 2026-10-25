package com.rivers.cosmo.wallet

import java.io.Closeable
import java.math.BigDecimal
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.stellar.sdk.Asset
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Memo
import org.stellar.sdk.Network
import org.stellar.sdk.Server
import org.stellar.sdk.Transaction
import org.stellar.sdk.TransactionBuilder
import org.stellar.sdk.TransactionBuilderAccount
import org.stellar.sdk.exception.AccountNotFoundException
import org.stellar.sdk.exception.AccountRequiresMemoException
import org.stellar.sdk.exception.BadRequestException
import org.stellar.sdk.exception.NetworkException
import org.stellar.sdk.operations.CreateAccountOperation
import org.stellar.sdk.operations.PaymentOperation
import org.stellar.sdk.requests.RequestBuilder
import org.stellar.sdk.responses.operations.CreateAccountOperationResponse
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
    val transactionHash: String
)

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
        } catch (e: AccountNotFoundException) {
            AccountState.NotActivated
        }
    }

    /** The account's latest incoming and outgoing payments, newest first. */
    fun recentPayments(accountId: String, limit: Int = 20): List<PaymentRecord> = request {
        try {
            server.payments().forAccount(accountId).order(RequestBuilder.Order.DESC).limit(limit).execute().records
                .mapNotNull { operation ->
                    when (operation) {
                        is PaymentOperationResponse -> PaymentRecord(
                            id = operation.id.toString(),
                            incoming = operation.to == accountId,
                            counterparty = if (operation.to == accountId) operation.from else operation.to,
                            amount = BigDecimal(operation.amount),
                            assetCode = if (operation.assetType == NATIVE) "XLM" else operation.assetCode,
                            createdAt = operation.createdAt,
                            transactionHash = operation.transactionHash
                        )
                        is CreateAccountOperationResponse -> PaymentRecord(
                            id = operation.id.toString(),
                            incoming = operation.account == accountId,
                            counterparty = if (operation.account == accountId) operation.funder else operation.account,
                            amount = BigDecimal(operation.startingBalance),
                            assetCode = "XLM",
                            createdAt = operation.createdAt,
                            transactionHash = operation.transactionHash
                        )
                        // Other kinds (path payments, account merges) aren't shown yet.
                        else -> null
                    }
                }
        } catch (e: AccountNotFoundException) {
            emptyList()
        }
    }

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
    fun send(keyPair: KeyPair, destination: String, balance: WalletBalance, amount: BigDecimal, memo: String?): String = request {
        val source = try {
            server.loadAccount(keyPair.accountId)
        } catch (e: AccountNotFoundException) {
            throw WalletException("Your account isn't activated yet, so it can't send anything.")
        }
        val destinationExists = account(destination) is AccountState.Active
        val transaction = buildTransaction(network, source, keyPair, destination, balance, amount, memo, destinationExists)
        try {
            server.submitTransaction(transaction).hash
        } catch (e: AccountRequiresMemoException) {
            throw WalletException("This address needs a memo (exchanges use it to find your account). Add one and try again.", e)
        } catch (e: BadRequestException) {
            throw WalletException(describeFailure(e), e)
        }
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

        /** Builds and signs a payment, or an account creation when [destinationExists] is false. */
        fun buildTransaction(
            network: StellarNetwork,
            source: TransactionBuilderAccount,
            keyPair: KeyPair,
            destination: String,
            balance: WalletBalance,
            amount: BigDecimal,
            memo: String?,
            destinationExists: Boolean
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
            memo?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                if (text.toByteArray(Charsets.UTF_8).size > MAX_MEMO_BYTES) throw WalletException("Memos can be at most $MAX_MEMO_BYTES characters.")
                builder.addMemo(Memo.text(text))
            }
            return builder.build().also { it.sign(keyPair) }
        }

        /** Explains Horizon's result codes, see developers.stellar.org/docs/data/horizon/api-reference/errors/result-codes. */
        fun describeFailure(e: BadRequestException): String {
            val codes = e.problem?.extras?.resultCodes
            val operationCode = codes?.operationsResultCodes?.firstOrNull { it != "op_success" }
            return when (operationCode ?: codes?.transactionResultCode) {
                "op_underfunded" -> "You don't have enough of that asset to send this amount."
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
