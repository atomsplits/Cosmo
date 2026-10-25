package com.rivers.cosmo.kyc

import android.app.Activity
import android.util.Log
import com.sumsub.sns.core.SNSMobileSDK
import com.sumsub.sns.core.data.listener.TokenExpirationHandler
import com.sumsub.sns.core.data.model.SNSCompletionResult
import com.sumsub.sns.core.data.model.SNSException
import com.sumsub.sns.core.data.model.SNSSDKState
import java.util.Locale
import kotlinx.coroutines.runBlocking

/**
 * Opens Sumsub's verification flow (it runs in its own Activity), following Sumsub's Android
 * integration template. The SDK's callbacks are for the UI only: the real verdict comes from the
 * Cosmo server, which hears from Sumsub directly.
 */
class SumsubLauncher(private val tokenProvider: suspend () -> String) {

    /** Call on the main thread with a token the app already fetched. [onClosed] runs when the flow closes. */
    fun present(activity: Activity, accessToken: String, onClosed: () -> Unit) {
        SNSMobileSDK.Builder(activity)
            .withAccessToken(accessToken, onTokenExpiration = tokenExpirationHandler)
            .withHandlers(
                onStateChanged = { newState: SNSSDKState, previousState: SNSSDKState ->
                    Log.d(TAG, "State: $previousState -> $newState")
                },
                onCompleted = { result: SNSCompletionResult, _: SNSSDKState ->
                    if (result is SNSCompletionResult.AbnormalTermination) {
                        Log.e(TAG, "Verification closed with an error", result.exception)
                    }
                    onClosed()
                },
                onError = { e: SNSException -> Log.e(TAG, "Verification error", e) }
            )
            .withLocale(Locale.getDefault())
            .build()
            .launch()
    }

    /**
     * Sumsub calls this on a background thread when the token expires mid-flow and waits for the
     * return value, so blocking here is what the SDK expects. Returning null ends the session.
     */
    private val tokenExpirationHandler = object : TokenExpirationHandler {
        override fun onTokenExpired(): String? = runBlocking {
            runCatching { tokenProvider() }
                .onFailure { Log.e(TAG, "Couldn't refresh the verification token", it) }
                .getOrNull()
        }
    }

    private companion object {
        const val TAG = "Sumsub"
    }
}
