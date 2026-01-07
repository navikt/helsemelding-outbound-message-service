package no.nav.helsemelding.state.processor

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import no.nav.emottak.ediadapter.client.EdiAdapterClient
import no.nav.emottak.ediadapter.model.PostMessageRequest
import no.nav.helsemelding.state.model.CreateState
import no.nav.helsemelding.state.model.DialogMessage
import no.nav.helsemelding.state.model.MessageType.DIALOG
import no.nav.helsemelding.state.receiver.MessageReceiver
import no.nav.helsemelding.state.service.MessageStateService
import java.net.URI

private val log = KotlinLogging.logger {}
const val BASE64_ENCODING = "base64"

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

    internal suspend fun processAndSendMessage(dialogMessage: DialogMessage) {
        val postMessageRequest = PostMessageRequest(
            businessDocument = dialogMessage.toString(),
            contentType = ContentType.Application.Xml.toString(),
            contentTransferEncoding = BASE64_ENCODING
        )

        val (metadata, errorMessage) = ediAdapterClient.postMessage(postMessageRequest)

        if (errorMessage != null) {
            log.error { "Received error when processing dialog message: $errorMessage" }
            return
        }

        if (metadata == null) {
            log.error { "Metadata is null" }
            return
        }

        val createState = CreateState(
            messageType = DIALOG,
            externalRefId = metadata.id,
            externalMessageUrl = URI.create(metadata.location).toURL()
        )

        val newMessage = messageStateService.createInitialState(createState)
        log.info { "Processed and sent message with reference id: ${dialogMessage.id}" }
    }
}
