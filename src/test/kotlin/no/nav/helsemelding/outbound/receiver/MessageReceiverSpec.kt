package no.nav.helsemelding.outbound.receiver

import app.cash.turbine.test
import app.cash.turbine.turbineScope
import arrow.fx.coroutines.resourceScope
import io.github.nomisRev.kafka.publisher.KafkaPublisher
import io.github.nomisRev.kafka.receiver.AutoOffsetReset
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.startWith
import no.nav.helsemelding.outbound.KafkaSpec
import no.nav.helsemelding.outbound.config
import no.nav.helsemelding.outbound.config.Config
import no.nav.helsemelding.outbound.config.Kafka.SecurityProtocol
import no.nav.helsemelding.outbound.config.withKafka
import no.nav.helsemelding.outbound.kafkaReceiver
import no.nav.helsemelding.outbound.metrics.FakeMetrics
import org.apache.kafka.clients.producer.ProducerRecord
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class MessageReceiverSpec : KafkaSpec(
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

        "One message if record key is a valid uuid - one message" {
            resourceScope {
                turbineScope {
                    val publisher = KafkaPublisher(publisherSettings())
                    val referenceId = Uuid.random()
                    val content = "data".toByteArray()
                    val topic = config.kafka.topics.dialogMessageOut
                    publisher.publishScope {
                        publish(
                            ProducerRecord(
                                topic,
                                referenceId.toString(),
                                content
                            )
                        )
                    }

                    val receiver = MessageReceiver(
                        topic,
                        kafkaReceiver(config.kafka, AutoOffsetReset.Earliest),
                        FakeMetrics()
                    )
                    val messages = receiver.receiveMessages()

                    messages.test {
                        val message = awaitItem()
                        message.id shouldBe referenceId
                        message.payload shouldBe content
                    }
                }
            }
        }

        "No message if record key is null" {
            resourceScope {
                turbineScope {
                    val publisher = KafkaPublisher(publisherSettings())
                    val content = "data".toByteArray()
                    val topic = "${config.kafka.topics.dialogMessageOut}2"
                    publisher.publishScope {
                        publish(
                            ProducerRecord(
                                topic,
                                content
                            )
                        )
                    }

                    val receiver = MessageReceiver(
                        topic,
                        kafkaReceiver(config.kafka, AutoOffsetReset.Earliest),
                        FakeMetrics()
                    )
                    val messages = receiver.receiveMessages()

                    messages.test(timeout = 2.seconds) {
                        val exception = shouldThrow<AssertionError> {
                            awaitItem()
                        }
                        exception.message should startWith("No value produced")
                    }
                }
            }
        }

        "No message if record key is an invalid uuid" {
            resourceScope {
                turbineScope {
                    val publisher = KafkaPublisher(publisherSettings())
                    val content = "data".toByteArray()
                    val topic = "${config.kafka.topics.dialogMessageOut}3"
                    publisher.publishScope {
                        publish(
                            ProducerRecord(
                                topic,
                                "1234-abcd",
                                content
                            )
                        )
                    }

                    val receiver = MessageReceiver(
                        topic,
                        kafkaReceiver(config.kafka, AutoOffsetReset.Earliest),
                        FakeMetrics()
                    )
                    val messages = receiver.receiveMessages()

                    messages.test(timeout = 2.seconds) {
                        val exception = shouldThrow<AssertionError> {
                            awaitItem()
                        }
                        exception.message should startWith("No value produced")
                    }
                }
            }
        }
    }
)
