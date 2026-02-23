package no.nav.helsemelding.outbound.service

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.helsemelding.outbound.model.AppRecStatus.OK
import no.nav.helsemelding.outbound.model.CreateState
import no.nav.helsemelding.outbound.model.ExternalDeliveryState.ACKNOWLEDGED
import no.nav.helsemelding.outbound.model.ExternalDeliveryState.REJECTED
import no.nav.helsemelding.outbound.model.ExternalDeliveryState.UNCONFIRMED
import no.nav.helsemelding.outbound.model.MessageType.DIALOG
import no.nav.helsemelding.outbound.model.UpdateState
import java.net.URI
import kotlin.uuid.Uuid

private const val MESSAGE1 = "http://example.com/messages/1"
private const val MESSAGE2 = "http://example.com/messages/2"
private const val MESSAGE3 = "http://example.com/messages/3"
private const val MESSAGE4 = "http://example.com/messages/4"
private const val MESSAGE5 = "http://example.com/messages/5"

class MessageStateServiceSpec : StringSpec(
    {

        "Create initial state – creates message with null external states and one baseline history entry" {
            val messageStateService = FakeTransactionalMessageStateService()

            val id = Uuid.random()
            val externalRefId = Uuid.random()
            val externalMessageUrl = URI(MESSAGE1).toURL()

            val snapshot = messageStateService.createInitialState(
                CreateState(
                    id = id,
                    externalRefId = externalRefId,
                    messageType = DIALOG,
                    externalMessageUrl = externalMessageUrl
                )
            )

            val messageState = snapshot.messageState

            messageState.id shouldBe id
            messageState.externalRefId shouldBe externalRefId
            messageState.externalMessageUrl shouldBe externalMessageUrl

            messageState.externalDeliveryState shouldBe null
            messageState.appRecStatus shouldBe null

            snapshot.messageStateChanges.size shouldBe 1
            val history = snapshot.messageStateChanges.first()

            history.oldDeliveryState shouldBe null
            history.newDeliveryState shouldBe null
            history.oldAppRecStatus shouldBe null
            history.newAppRecStatus shouldBe null
        }

        "Record state change – updates external state and appends history" {
            val messageStateService = FakeTransactionalMessageStateService()

            val id = Uuid.random()
            val externalRefId = Uuid.random()
            val externalMessageUrl = URI(MESSAGE1).toURL()

            messageStateService.createInitialState(
                CreateState(
                    id = id,
                    externalRefId = externalRefId,
                    messageType = DIALOG,
                    externalMessageUrl = externalMessageUrl
                )
            )

            val updated = messageStateService.recordStateChange(
                UpdateState(
                    externalRefId = externalRefId,
                    messageType = DIALOG,
                    oldDeliveryState = null,
                    newDeliveryState = ACKNOWLEDGED,
                    oldAppRecStatus = null,
                    newAppRecStatus = null
                )
            )

            updated.messageState.externalDeliveryState shouldBe ACKNOWLEDGED

            updated.messageStateChanges.size shouldBe 2
            val last = updated.messageStateChanges.last()

            last.oldDeliveryState shouldBe null
            last.newDeliveryState shouldBe ACKNOWLEDGED
            last.oldAppRecStatus shouldBe null
            last.newAppRecStatus shouldBe null
        }

        "Get message snapshot – returns null when missing" {
            val messageStateService = FakeTransactionalMessageStateService()

            messageStateService.getMessageSnapshot(Uuid.random()).shouldBeNull()
        }

        "Find pollable messages – only messages with NULL, ACKNOWLEDGED or UNCONFIRMED delivery state" {
            val messageStateService = FakeTransactionalMessageStateService()

            val id1 = Uuid.random()
            val externalRefId1 = Uuid.random()
            val externalMessageUrl1 = URI(MESSAGE1).toURL()

            val id2 = Uuid.random()
            val externalRefId2 = Uuid.random()
            val externalMessageUrl2 = URI(MESSAGE2).toURL()

            val id3 = Uuid.random()
            val externalRefId3 = Uuid.random()
            val externalMessageUrl3 = URI(MESSAGE3).toURL()

            val id4 = Uuid.random()
            val externalRefId4 = Uuid.random()
            val externalMessageUrl4 = URI(MESSAGE4).toURL()

            val id5 = Uuid.random()
            val externalRefId5 = Uuid.random()
            val externalMessageUrl5 = URI(MESSAGE5).toURL()

            val nullSnapshot = messageStateService.createInitialState(
                CreateState(
                    id = id1,
                    externalRefId = externalRefId1,
                    messageType = DIALOG,
                    externalMessageUrl = externalMessageUrl1
                )
            )

            messageStateService.createInitialState(
                CreateState(
                    id = id2,
                    externalRefId = externalRefId2,
                    messageType = DIALOG,
                    externalMessageUrl = externalMessageUrl2
                )
            )

            messageStateService.createInitialState(
                CreateState(
                    id = id3,
                    externalRefId = externalRefId3,
                    messageType = DIALOG,
                    externalMessageUrl = externalMessageUrl3
                )
            )

            messageStateService.createInitialState(
                CreateState(
                    id = id4,
                    externalRefId = externalRefId4,
                    messageType = DIALOG,
                    externalMessageUrl = externalMessageUrl4
                )
            )

            messageStateService.createInitialState(
                CreateState(
                    id = id5,
                    externalRefId = externalRefId5,
                    messageType = DIALOG,
                    externalMessageUrl = externalMessageUrl5
                )
            )

            val acknowledgedSnapshot = messageStateService.recordStateChange(
                UpdateState(
                    externalRefId = externalRefId2,
                    messageType = DIALOG,
                    oldDeliveryState = null,
                    newDeliveryState = ACKNOWLEDGED,
                    oldAppRecStatus = null,
                    newAppRecStatus = null
                )
            )

            messageStateService.recordStateChange(
                UpdateState(
                    externalRefId = externalRefId3,
                    messageType = DIALOG,
                    oldDeliveryState = null,
                    newDeliveryState = ACKNOWLEDGED,
                    oldAppRecStatus = null,
                    newAppRecStatus = OK
                )
            )

            val unconfirmedSnapshot = messageStateService.recordStateChange(
                UpdateState(
                    externalRefId = externalRefId4,
                    messageType = DIALOG,
                    oldDeliveryState = null,
                    newDeliveryState = UNCONFIRMED,
                    oldAppRecStatus = null,
                    newAppRecStatus = null
                )
            )

            messageStateService.recordStateChange(
                UpdateState(
                    externalRefId = externalRefId5,
                    messageType = DIALOG,
                    oldDeliveryState = null,
                    newDeliveryState = REJECTED,
                    oldAppRecStatus = null,
                    newAppRecStatus = null
                )
            )

            val messages = messageStateService.findPollableMessages()

            messages.size shouldBe 3
            messages shouldContain nullSnapshot.messageState
            messages shouldContain acknowledgedSnapshot.messageState
            messages shouldContain unconfirmedSnapshot.messageState
        }

        "Mark as polled – updates last polled at only for selected messages" {
            val messageStateService = FakeTransactionalMessageStateService()

            val id1 = Uuid.random()
            val externalRefId1 = Uuid.random()
            val externalMessageUrl1 = URI(MESSAGE1).toURL()
            val id2 = Uuid.random()
            val externalRefId2 = Uuid.random()
            val externalMessageUrl2 = URI(MESSAGE2).toURL()

            messageStateService.createInitialState(
                CreateState(
                    id1,
                    externalRefId1,
                    DIALOG,
                    externalMessageUrl1
                )
            )
            messageStateService.createInitialState(
                CreateState(
                    id2,
                    externalRefId2,
                    DIALOG,
                    externalMessageUrl2
                )
            )

            messageStateService.getMessageSnapshot(externalRefId1)!!.messageState.lastPolledAt.shouldBeNull()
            messageStateService.getMessageSnapshot(externalRefId2)!!.messageState.lastPolledAt.shouldBeNull()

            val updated = messageStateService.markAsPolled(listOf(externalRefId1))
            updated shouldBe 1

            messageStateService.getMessageSnapshot(externalRefId1)!!.messageState.lastPolledAt shouldNotBe null
            messageStateService.getMessageSnapshot(externalRefId2)!!.messageState.lastPolledAt shouldBe null
        }
    }
)
