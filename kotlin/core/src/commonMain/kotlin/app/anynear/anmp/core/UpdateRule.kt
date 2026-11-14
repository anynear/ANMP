package app.anynear.anmp.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * When an old client build must stop (ADR 0096, ANMP "Update rules"): block `app` (Android and iOS, build =
 * `versionCode` = `CURRENT_PROJECT_VERSION`, 2n or 2n+1 from the number of commits of the app's repository) or `web` (the
 * web app, build = number of commits of its repository). Set only when needed, by the network operator; absent means
 * nothing is asked. The list only enforces; notices about new
 * versions come from the stores and updaters themselves.
 *
 * Builds below [min] must update from [from] (Unix milliseconds) on; until then they show a notice with the date.
 */
data class UpdateRule(val min: Long, val from: Long) {
    /** What [build] has to do at [nowMillis]. A build of 0 or less (unknown, e.g. a local build) is never asked. */
    fun demand(build: Long, nowMillis: Long): UpdateDemand = when {
        build <= 0 || build >= min -> UpdateDemand.None
        nowMillis >= from -> UpdateDemand.Required(min, from)
        else -> UpdateDemand.Soon(min, from)
    }

    internal companion object {
        /** Reads a block; `null` unless both [min] and [from] are positive integers (the lead time is mandatory). */
        fun read(e: JsonElement?): UpdateRule? {
            val o = e as? JsonObject ?: return null
            fun num(key: String): Long? = (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it > 0 }
            return UpdateRule(num("min") ?: return null, num("from") ?: return null)
        }
    }
}

/** Result of [UpdateRule.demand]. */
sealed interface UpdateDemand {
    /** Nothing to do. */
    data object None : UpdateDemand

    /** Below [min]; from [from] (Unix milliseconds) on, the app is blocked. Until then a notice with the date. */
    data class Soon(val min: Long, val from: Long) : UpdateDemand

    /** Below [min] and [from] has passed: the app covers its interface until it is updated (only if there is a way). */
    data class Required(val min: Long, val from: Long) : UpdateDemand
}
