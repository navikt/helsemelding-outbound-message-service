package no.nav.helsemelding.outbound.publisher

import app.cash.turbine.test
import arrow.fx.coroutines.resourceScope
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import no.nav.helsemelding.outbound.KafkaSpec
import no.nav.helsemelding.outbound.config
import no.nav.helsemelding.outbound.model.MessageStatus
import no.nav.helsemelding.outbound.model.MessageStatusEvent
import kotlin.time.Instant
import kotlin.uuid.Uuid

class StatusMessagePublisherSpec : KafkaSpec({
    "publishes status with message id as key" {
        resourceScope {
            val topics = config().kafka.topics
            val publisher = StatusMessagePublisher(topics, kafkaPublisher())
            val event = MessageStatusEvent(
                messageId = Uuid.random(),
                timestamp = Instant.parse("2026-09-07T10:00:00Z"),
                status = MessageStatus.COMPLETED
            )

            publisher.publish(event).shouldBeRight().topic() shouldBe topics.statusMessage

            kafkaReceiver().receive(topics.statusMessage)
                .test(timeout = kafkaTimeout) {
                    val record = awaitItem()
                    record.key() shouldBe event.messageId.toString()
                    Json.decodeFromString<MessageStatusEvent>(record.value().decodeToString()) shouldBe event
                }
        }
    }
})
