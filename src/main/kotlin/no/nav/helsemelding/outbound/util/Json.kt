package no.nav.helsemelding.outbound.util

import kotlinx.serialization.json.Json

inline fun <reified T> T.toJson(): String = Json.encodeToString(this)
