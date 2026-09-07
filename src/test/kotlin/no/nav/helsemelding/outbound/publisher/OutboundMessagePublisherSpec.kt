package no.nav.helsemelding.outbound.publisher

import arrow.fx.coroutines.resourceScope
import io.github.nomisRev.kafka.publisher.KafkaPublisher
import io.github.nomisRev.kafka.receiver.KafkaReceiver
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.nav.helsemelding.outbound.KafkaSpec
import no.nav.helsemelding.outbound.config
import no.nav.helsemelding.outbound.model.MessageStatus
import no.nav.helsemelding.outbound.model.MessageStatusEvent
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class OutboundMessagePublisherSpec : KafkaSpec(
    {
        "Publishes status with message id as key to the configured topic using the supplied JSON settings" {
            resourceScope {
                val topics = config().kafka.topics.copy(statusMessage = "test.status.${Uuid.random()}")
                val kafkaPublisher = install({ KafkaPublisher(publisherSettings()) }) { publisher, _ ->
                    publisher.close()
                }
                val publisher = OutboundMessagePublisher(topics, kafkaPublisher, Json { encodeDefaults = true })
                val message = MessageStatusEvent(
                    messageId = Uuid.random(),
                    timestamp = Instant.parse("2026-09-07T10:00:00Z"),
                    status = MessageStatus.COMPLETED
                )

                val metadata = publisher.publish(message).shouldBeRight()
                val record = withTimeout(20.seconds) {
                    KafkaReceiver(receiverSettings()).receive(topics.statusMessage).first()
                }

                metadata.topic() shouldBe topics.statusMessage
                record.key() shouldBe message.messageId.toString()
                val payload = Json.parseToJsonElement(record.value().decodeToString()).jsonObject
                payload["messageId"]?.jsonPrimitive?.content shouldBe message.messageId.toString()
                payload["timestamp"]?.jsonPrimitive?.content shouldBe "2026-09-07T10:00:00Z"
                payload["status"]?.jsonPrimitive?.content shouldBe "COMPLETED"
                payload["apprec"] shouldBe JsonNull
                payload["error"] shouldBe JsonNull
            }
        }
    }
)
