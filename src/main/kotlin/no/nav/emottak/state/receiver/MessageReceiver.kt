package no.nav.emottak.state.receiver

import io.github.nomisRev.kafka.receiver.KafkaReceiver
import io.github.nomisRev.kafka.receiver.ReceiverRecord
import io.github.nomisRev.kafka.receiver.ReceiverSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import no.nav.emottak.state.config
import no.nav.emottak.state.model.DialogMessage
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import kotlin.uuid.Uuid

class MessageReceiver(
    private val kafkaReceiver: KafkaReceiver<String, ByteArray>
) {
    private val kafka = config().kafkaTopics

    fun receiveMessages(): Flow<DialogMessage> = kafkaReceiver
        .receive(kafka.dialogMessage)
        .map(::toMessage)

    private suspend fun toMessage(record: ReceiverRecord<String, ByteArray>): DialogMessage {
        return DialogMessage(
            Uuid.parse(record.key()),
            record.value()
        )
    }
}

fun fakeMessageReceiver(): MessageReceiver = MessageReceiver(
    KafkaReceiver(
        ReceiverSettings(
            bootstrapServers = "",
            keyDeserializer = StringDeserializer(),
            valueDeserializer = ByteArrayDeserializer(),
            groupId = ""
        )
    )
)
