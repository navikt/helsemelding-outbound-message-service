package no.nav.emottak.state.receiver

import io.github.nomisRev.kafka.receiver.KafkaReceiver
import io.github.nomisRev.kafka.receiver.ReceiverRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import no.nav.emottak.state.config
import no.nav.emottak.state.model.Message
import kotlin.uuid.Uuid

class MessageReceiver(
    private val kafkaReceiver: KafkaReceiver<String, ByteArray>
) {
    private val kafka = config().kafkaTopics

    fun receiveMessages(): Flow<Message> = kafkaReceiver
        .receive(kafka.messagesOutTopic)
        .map(::toMessage)

    private suspend fun toMessage(record: ReceiverRecord<String, ByteArray>): Message {
        return Message(
            Uuid.parse(record.key()),
            record.value()
        )
    }
}
