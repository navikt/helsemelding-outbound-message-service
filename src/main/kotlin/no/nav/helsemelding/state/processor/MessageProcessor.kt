package no.nav.helsemelding.state.processor

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import no.nav.helsemelding.ediadapter.client.EdiAdapterClient
import no.nav.helsemelding.ediadapter.model.Metadata
import no.nav.helsemelding.ediadapter.model.PostMessageRequest
import no.nav.helsemelding.state.model.CreateState
import no.nav.helsemelding.state.model.DialogMessage
import no.nav.helsemelding.state.model.MessageType.DIALOG
import no.nav.helsemelding.state.receiver.MessageReceiver
import no.nav.helsemelding.state.service.MessageStateService
import java.net.URI
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger {}
const val BASE64_ENCODING = "base64"

class MessageProcessor(
    private val messageReceiver: MessageReceiver,
    private val messageStateService: MessageStateService,
    private val ediAdapterClient: EdiAdapterClient
) {
    fun processMessages(scope: CoroutineScope) =
        messageFlow()
            .onEach { message -> processAndSendMessage(scope, message) }
            .flowOn(Dispatchers.IO)
            .launchIn(scope)

    private fun messageFlow(): Flow<DialogMessage> =
        messageReceiver.receiveMessages()

    internal fun processAndSendMessage(scope: CoroutineScope, dialogMessage: DialogMessage) = scope.launch {
        val postMessageRequest = PostMessageRequest(
            businessDocument = dialogMessage.toString(),
            contentType = ContentType.Application.Xml.toString(),
            contentTransferEncoding = BASE64_ENCODING
        )

        ediAdapterClient.postMessage(postMessageRequest)
            .onRight { metadata -> initializeState(metadata, dialogMessage.id) }
            .onLeft { errorMessage -> log.error { "Received error when processing dialog message: $errorMessage" } }
    }

    private suspend fun initializeState(metadata: Metadata, dialogMessageId: Uuid) {
        val createState = CreateState(
            messageType = DIALOG,
            externalRefId = metadata.id,
            externalMessageUrl = URI.create(metadata.location).toURL()
        )

        val newMessage = messageStateService.createInitialState(createState)
        log.info { "Processed and sent message with reference id: $dialogMessageId" }
    }
}
