package no.nav.helsemelding.outbound.receiver

import io.github.nomisRev.kafka.receiver.KafkaReceiver
import io.github.nomisRev.kafka.receiver.ReceiverRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import no.nav.helsemelding.outbound.model.EdiStatusEvent
import no.nav.helsemelding.outbound.model.EdiStatusMessage
import kotlin.uuid.Uuid

class EdiMessageReceiver(
    private val topic: String,
    private val kafkaReceiver: KafkaReceiver<String, ByteArray>
) {
    fun receiveMessages(): Flow<EdiStatusMessage> =
        kafkaReceiver
            .receive(topic)
            .map(::toEdiStatusMessage)

    fun toEdiStatusMessage(record: ReceiverRecord<String, ByteArray>) = EdiStatusMessage(
        messageId = Uuid.parse(record.key()),
        status = Json.decodeFromString<EdiStatusEvent>(record.value().decodeToString()),
        acknowledge = { record.offset.acknowledge() }
    )
}
