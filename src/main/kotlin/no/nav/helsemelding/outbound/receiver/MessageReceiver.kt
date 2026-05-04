package no.nav.helsemelding.outbound.receiver

import io.github.nomisRev.kafka.receiver.KafkaReceiver
import io.github.nomisRev.kafka.receiver.ReceiverRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import no.nav.helsemelding.outbound.handler.MessageHandler
import no.nav.helsemelding.outbound.model.DialogMessage
import no.nav.helsemelding.outbound.model.MessageRecord
import kotlin.uuid.Uuid

class MessageReceiver(
    private val dialogMessageOutTopic: String,
    private val kafkaReceiver: KafkaReceiver<String, ByteArray>,
    private val messageHandler: MessageHandler
) {
    fun receiveMessages(): Flow<DialogMessage> =
        kafkaReceiver
            .receive(dialogMessageOutTopic)
            .mapNotNull { record ->
                when (val validation = validateRecordKey(record)) {
                    RecordKeyValidation.Valid ->
                        toMessage(record)

                    is RecordKeyValidation.Invalid -> {
                        messageHandler.handleInvalidKafkaKey(
                            record = record.toMessageRecord(),
                            validation = validation
                        )
                        null
                    }
                }
            }

    private fun toMessage(record: ReceiverRecord<String, ByteArray>): DialogMessage =
        DialogMessage(
            Uuid.parse(record.key()),
            record.value()
        )

    private fun ReceiverRecord<String, ByteArray>.toMessageRecord(): MessageRecord =
        MessageRecord(
            key = key(),
            payload = value(),
            offset = offset.offset,
            occuredAt = timestamp()
        )
}

sealed interface RecordKeyValidation {
    data object Valid : RecordKeyValidation

    data class Invalid(
        val reason: String
    ) : RecordKeyValidation
}

internal fun validateRecordKey(
    record: ReceiverRecord<String, ByteArray>
): RecordKeyValidation =
    when (val key = record.key()) {
        null -> RecordKeyValidation.Invalid("Kafka record key is null")
        else ->
            if (Uuid.parseOrNull(key) == null) {
                RecordKeyValidation.Invalid("Kafka record key is not a valid UUID")
            } else {
                RecordKeyValidation.Valid
            }
    }
