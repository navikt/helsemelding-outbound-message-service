package no.nav.helsemelding.outbound.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.uuid.Uuid

@Serializable
data class AppRecPayload(
    @Transient
    val id: Uuid? = null,
    val receiverHerId: Int? = null,
    val status: String? = null,
    val errorList: List<AppRecErrorMessage> = emptyList()
)
