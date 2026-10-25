package com.rivers.cosmo.kyc

import com.rivers.cosmo.anchor.AnchorException
import com.rivers.cosmo.anchor.AnchorService
import com.rivers.cosmo.anchor.Sep12
import com.rivers.cosmo.anchor.Upload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Identity verification with Stellar's KYC API (SEP-12). Verification belongs to an anchor: the
 * user signs in to it with their wallet (SEP-10) and sends their details straight to it, so they
 * never pass through a Cosmo server.
 */
class KycController(private val anchors: AnchorService, anchorDomains: List<String>) {
    data class State(
        /** Anchors from Settings, in order; the user picks which one to verify with. */
        val domains: List<String> = emptyList(),
        val domain: String? = null,
        val customer: Sep12.Customer? = null,
        val loading: Boolean = false,
        val submitting: Boolean = false,
        val error: String? = null
    ) {
        val status: KycStatus? get() = customer?.status
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State(domains = anchorDomains, domain = anchorDomains.firstOrNull()))
    val state: StateFlow<State> = _state.asStateFlow()
    private var loadJob: Job? = null

    fun select(domain: String) {
        _state.update { it.copy(domain = domain, customer = null, error = null) }
        refresh()
    }

    fun refresh() {
        val domain = _state.value.domain ?: return
        loadJob?.cancel()
        loadJob = scope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val customer = anchors.customer(domain)
                _state.update { if (it.domain == domain) it.copy(loading = false, customer = customer) else it }
            } catch (e: AnchorException) {
                _state.update { if (it.domain == domain) it.copy(loading = false, error = e.message) else it }
            }
        }
    }

    /** Sends the user's details to the selected anchor, then reloads what it still needs. */
    suspend fun submit(fields: Map<String, String>, files: Map<String, Upload>): Result<Unit> {
        val domain = _state.value.domain ?: return Result.failure(AnchorException("Add an anchor in Settings first."))
        _state.update { it.copy(submitting = true, error = null) }
        val result = withContext(Dispatchers.IO) {
            runCatching { anchors.updateCustomer(domain, fields, files) }
        }
        _state.update { it.copy(submitting = false, error = (result.exceptionOrNull() as? AnchorException)?.message) }
        refresh()
        return result
    }

    fun close() = scope.cancel()
}
