package no.nav.emottak.state.processor

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import no.nav.emottak.state.integration.ediadapter.EdiAdapterClient
import no.nav.emottak.state.model.CreateState
import no.nav.emottak.state.model.DialogMessage
import no.nav.emottak.state.model.MessageType.DIALOG
import no.nav.emottak.state.receiver.MessageReceiver
import no.nav.emottak.state.service.MessageStateService
import java.net.URI
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger {}

class MessageProcessor(
    private val messageReceiver: MessageReceiver,
    private val messageStateService: MessageStateService,
    private val ediAdapterClient: EdiAdapterClient
) {
    fun processMessages() =
        messageFlow()
            .onEach { message -> processAndSendMessage(message) }
            .flowOn(Dispatchers.IO)

    private fun messageFlow(): Flow<DialogMessage> =
        messageReceiver.receiveMessages()

    private suspend fun processAndSendMessage(dialogMessage: DialogMessage) {
        val uuid = ediAdapterClient.postMessage(dialogMessage)

        val createState = CreateState(
            messageType = DIALOG,
            externalRefId = Uuid.parse(uuid),
            externalMessageUrl = URI.create("nhn/$uuid").toURL()
        )

        messageStateService.createInitialState(createState)
        log.info { "Processed and sent message with reference id: ${dialogMessage.id}" }
    }
}
