package no.nav.helsemelding.outbound.publisher

import app.cash.turbine.test
import arrow.fx.coroutines.resourceScope
import io.github.nomisRev.kafka.publisher.KafkaPublisher
import io.github.nomisRev.kafka.receiver.AutoOffsetReset
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import no.nav.helsemelding.outbound.KafkaSpec
import no.nav.helsemelding.outbound.config
import no.nav.helsemelding.outbound.config.Config
import no.nav.helsemelding.outbound.config.Kafka.SecurityProtocol
import no.nav.helsemelding.outbound.config.withKafka
import no.nav.helsemelding.outbound.kafkaReceiver
import no.nav.helsemelding.outbound.model.MessageStatus
import no.nav.helsemelding.outbound.model.MessageStatusEvent
import no.nav.helsemelding.outbound.receiver.MessageReceiver
import kotlin.time.Instant
import kotlin.uuid.Uuid

class StatusMessagePublisherSpec : KafkaSpec(
    {
        lateinit var config: Config

        beforeSpec {
            config = config()
                .withKafka {
                    copy(
                        bootstrapServers = container.bootstrapServers,
                        securityProtocol = SecurityProtocol("PLAINTEXT")
                    )
                }
        }

        "Publishes status with message id as key" {
            resourceScope {
                val topics = config.kafka.topics
                val kafkaPublisher = install({ KafkaPublisher(publisherSettings()) }) { publisher, _ ->
                    publisher.close()
                }
                val publisher = StatusMessagePublisher(topics, kafkaPublisher)
                val event = MessageStatusEvent(
                    messageId = Uuid.random(),
                    timestamp = Instant.parse("2026-09-07T10:00:00Z"),
                    status = MessageStatus.COMPLETED
                )

                val metadata = publisher.publish(event).shouldBeRight()
                metadata.topic() shouldBe topics.statusMessage

                val receiver = MessageReceiver(
                    topics.statusMessage,
                    kafkaReceiver(config.kafka, AutoOffsetReset.Earliest)
                )
                val messages = receiver.receiveMessages()

                messages.test {
                    val message = awaitItem()
                    message.id shouldBe event.messageId
                    val publishedEvent = Json.decodeFromString<MessageStatusEvent>(message.payload.decodeToString())
                    publishedEvent shouldBe event
                }
            }
        }
    }
)
