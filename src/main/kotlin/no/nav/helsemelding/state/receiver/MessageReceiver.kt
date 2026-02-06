package no.nav.helsemelding.state.receiver

import io.github.nomisRev.kafka.receiver.KafkaReceiver
import io.github.nomisRev.kafka.receiver.ReceiverRecord
import io.github.nomisRev.kafka.receiver.ReceiverSettings
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import no.nav.helsemelding.state.model.DialogMessage
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger {}

class MessageReceiver(
    private val dialogMessageOutTopic: String,
    private val kafkaReceiver: KafkaReceiver<String, ByteArray>
) {
    fun receiveMessages(): Flow<DialogMessage> = kafkaReceiver
        .receive(dialogMessageOutTopic)
        .filter(::isValidRecordKey)
        .map(::toMessage)

    private suspend fun toMessage(record: ReceiverRecord<String, ByteArray>): DialogMessage {
        return DialogMessage(
            Uuid.parse(record.key()),
            record.value()
        )
    }
}

internal fun isValidRecordKey(record: ReceiverRecord<String, ByteArray>): Boolean {
    val key = record.key()
    if (key == null) {
        log.error { "Receiver record key is null. Key should be a valid uuid. Offset: ${record.offset.offset}" }
        return false
    }

    return if (Uuid.parseOrNull(key) != null) {
        true
    } else {
        log.error { "Receiver record key: $key is invalid and therefore ignored." }
        false
    }
}

fun fakeMessageReceiver(): MessageReceiver = MessageReceiver(
    "fake.dialog.topic",
    KafkaReceiver(
        ReceiverSettings(
            bootstrapServers = "",
            keyDeserializer = StringDeserializer(),
            valueDeserializer = ByteArrayDeserializer(),
            groupId = ""
        )
    )
)
