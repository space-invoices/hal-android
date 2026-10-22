package com.chatwithhal.sdk

import org.json.JSONObject

/** How a CRM stage sent with identify may move the company. */
enum class HalStageUpdateMode(internal val value: String) {
    /** Only ever forward in the pipeline (the default). */
    ADVANCE_ONLY("advance_only"),

    /** Exactly the stage given, backwards too. Use only when your backend is the CRM's system of record. */
    EXACT("exact"),
}

/**
 * The company a user belongs to, for CRM linking.
 *
 * @property id Your id for the company (matched against HAL's id or `external_id`).
 * @property stage A CRM stage name, matched exactly.
 */
data class HalCompany @JvmOverloads constructor(
    val id: String? = null,
    val name: String? = null,
    val stage: String? = null,
    val metadata: Map<String, Any?>? = null,
) {
    internal fun toJson(): JSONObject = JSONObject().apply {
        putOpt("id", id)
        putOpt("name", name)
        putOpt("stage", stage)
        metadata?.let { put("metadata", scalarMap(it)) }
    }
}

/**
 * A logged-in user of your app. [userId] or [email] is required.
 *
 * @property phone International format (`+386…`) is safest.
 * @property userHash HMAC-SHA256 of the user id, computed on **your server** with
 *   the project's identity secret. Required when identity verification is on.
 * @property metadata Strings, numbers, booleans or `null`.
 */
data class HalUser @JvmOverloads constructor(
    val userId: String? = null,
    val email: String? = null,
    val name: String? = null,
    val phone: String? = null,
    val userHash: String? = null,
    val company: HalCompany? = null,
    val metadata: Map<String, Any?>? = null,
    val stageUpdateMode: HalStageUpdateMode? = null,
) {
    internal val hasIdentifier: Boolean get() = !userId.isNullOrEmpty() || !email.isNullOrEmpty()

    internal fun toJson(): JSONObject = JSONObject().apply {
        putOpt("user_id", userId)
        putOpt("email", email)
        putOpt("name", name)
        putOpt("phone", phone)
        putOpt("user_hash", userHash)
        company?.let { put("company", it.toJson()) }
        metadata?.let { put("metadata", scalarMap(it)) }
        stageUpdateMode?.let { put("stage_update_mode", it.value) }
    }
}

/** HAL stores scalars only; anything else is refused here rather than by the server. */
internal fun scalarValue(value: Any?): Any = when (value) {
    null -> JSONObject.NULL
    is String, is Boolean, is Int, is Long, is Double, is Float -> value
    is Number -> value.toDouble()
    else -> throw IllegalArgumentException(
        "HalChat values must be String, Number, Boolean or null, not ${value::class.java.simpleName}",
    )
}

internal fun scalarMap(values: Map<String, Any?>): JSONObject = JSONObject().apply {
    values.forEach { (key, value) -> put(key, scalarValue(value)) }
}
