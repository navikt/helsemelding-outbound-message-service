package no.nav.helsemelding.outbound.receiver

import io.github.nomisRev.kafka.receiver.KafkaReceiver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import no.nav.helsemelding.outbound.model.DialogMessage
import kotlin.uuid.Uuid

class MessageReceiver(
    private val dialogMessageOutTopic: String,
    private val kafkaReceiver: KafkaReceiver<String, ByteArray>
) {
    fun receiveMessages(): Flow<DialogMessage> =
        kafkaReceiver
            .receive(dialogMessageOutTopic)
            .map { record ->
                DialogMessage(
                    Uuid.parse(record.key()),
                    record.value()
                )
            }
}
