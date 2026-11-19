package no.nav.helsemelding.outbound.service

import no.nav.helsemelding.outbound.config
import no.nav.helsemelding.outbound.model.MessageTransport

class MessageTransportRouter(
    private val ediReceiverHerIds: List<Int> = config().edi.receiverHerIds
) {
    fun route(receiverHerIds: List<Int>): MessageTransport =
        when {
            receiverHerIds.any { it in ediReceiverHerIds } -> MessageTransport.EDI_1_0
            else -> MessageTransport.EDI_2_0
        }
}
