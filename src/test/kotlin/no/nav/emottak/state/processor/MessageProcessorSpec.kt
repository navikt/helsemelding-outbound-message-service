package no.nav.emottak.state.processor

import io.github.nomisRev.kafka.receiver.KafkaReceiver
import io.github.nomisRev.kafka.receiver.ReceiverSettings
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import no.nav.emottak.state.integration.ediadapter.EdiAdapterClient
import no.nav.emottak.state.integration.ediadapter.FakeEdiAdapterClient
import no.nav.emottak.state.model.DialogMessage
import no.nav.emottak.state.model.PostMessageResponse
import no.nav.emottak.state.receiver.MessageReceiver
import no.nav.emottak.state.service.transactionalMessageStateService
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import kotlin.uuid.Uuid

class MessageProcessorSpec : StringSpec(
    {

        "Process dialog message - Initialize message state with response from ediAdapterClient" {
            val uuid = Uuid.random()
            val url = "https://example.com/messages/1"
            val messageResponse = PostMessageResponse(uuid, url)
            val messageStateService = transactionalMessageStateService()
            val messageProcessor = MessageProcessor(
                dummyMessageReceiver(),
                messageStateService,
                stubEdiAdapterClient(messageResponse)
            )
            messageStateService.getMessageSnapshot(uuid).shouldBeNull()

            val dialogMessage = DialogMessage(uuid, "data".toByteArray())
            messageProcessor.processAndSendMessage(dialogMessage)

            val messageSnapshot = messageStateService.getMessageSnapshot(uuid)
            messageSnapshot.shouldNotBeNull()
            messageSnapshot.messageState.externalRefId shouldBeEqual uuid
            messageSnapshot.messageState.externalMessageUrl.toString() shouldBeEqual url
        }
    }
)

private fun stubEdiAdapterClient(message: PostMessageResponse): EdiAdapterClient = object : FakeEdiAdapterClient() {
    override suspend fun postMessage(dialogMessage: DialogMessage): PostMessageResponse = message
}

private fun dummyMessageReceiver(): MessageReceiver = MessageReceiver(
    KafkaReceiver(
        ReceiverSettings(
            bootstrapServers = "",
            keyDeserializer = StringDeserializer(),
            valueDeserializer = ByteArrayDeserializer(),
            groupId = ""
        )
    )
)
