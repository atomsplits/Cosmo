package com.rivers.cosmo.anchor

import com.rivers.cosmo.kyc.KycStatus
import org.json.JSONObject

/** SEP-12, Stellar's KYC API: the anchor says what it needs, the wallet sends it. */
object Sep12 {
    /** A detail the anchor asks for. Names come from SEP-9, e.g. first_name or photo_id_front. */
    data class Field(
        val name: String,
        /** "string", "binary" (a file), "number" or "date". */
        val type: String,
        val description: String,
        val choices: List<String>,
        val optional: Boolean,
        /** Set when the user already sent this and the anchor turned it down. */
        val error: String? = null
    ) {
        val isFile: Boolean get() = type == "binary"
        val label: String get() = name.split('_').joinToString(" ").replaceFirstChar { it.uppercase() }
    }

    data class Customer(
        val status: KycStatus,
        /** What's still needed, including details the anchor rejected. */
        val needed: List<Field>,
        /** Names of details already sent and accepted or being checked. */
        val provided: List<String>,
        val message: String?
    )

    fun parseCustomer(json: JSONObject): Customer {
        val needed = fields(json.optJSONObject("fields"))
        val providedJson = json.optJSONObject("provided_fields")
        val provided = providedJson?.keys()?.asSequence()?.toList().orEmpty()
        // Rejected details come back in provided_fields; ask for them again.
        val rejected = fields(providedJson).mapNotNull { field ->
            val entry = providedJson!!.getJSONObject(field.name)
            if (entry.optString("status") != "REJECTED") null else field.copy(error = entry.text("error") ?: "Rejected")
        }
        return Customer(
            status = KycStatus.parse(json.optString("status")),
            needed = (rejected + needed).distinctBy { it.name }.sortedWith(compareBy({ it.optional }, { it.isFile })),
            provided = provided.filter { name -> rejected.none { it.name == name } },
            message = json.text("message")
        )
    }

    private fun fields(json: JSONObject?): List<Field> = json?.keys()?.asSequence()?.mapNotNull { name ->
        val field = json.optJSONObject(name) ?: return@mapNotNull null
        val choices = field.optJSONArray("choices")
        Field(
            name = name,
            type = field.optString("type", "string"),
            description = field.optString("description", name),
            choices = if (choices == null) emptyList() else List(choices.length()) { choices.optString(it) },
            optional = field.optBoolean("optional", false)
        )
    }?.toList().orEmpty()
}
