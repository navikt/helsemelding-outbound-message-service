package no.nav.helsemelding.outbound.service

import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import no.nav.helsemelding.outbound.model.CreateState
import no.nav.helsemelding.outbound.model.ExternalDeliveryState.ACKNOWLEDGED
import no.nav.helsemelding.outbound.model.MessageType.DIALOG
import no.nav.helsemelding.outbound.model.UpdateState
import kotlin.uuid.Uuid

class MessageStateServiceSpec : StringSpec(
    {
        "create initial state – creates message with null external states and one baseline history entry" {
            val messageStateService = FakeTransactionalMessageStateService()

            val id = Uuid.random()
            val externalRefId = Uuid.random()

            val snapshot = messageStateService.createInitialState(
                CreateState(
                    id = id,
                    externalRefId = externalRefId,
                    messageType = DIALOG
                )
            )
                .shouldBeRight()

            val messageState = snapshot.messageState

            messageState.id shouldBe id
            messageState.externalRefId shouldBe externalRefId

            messageState.externalDeliveryState shouldBe null
            messageState.appRecStatus shouldBe null

            snapshot.messageStateChanges.size shouldBe 1
            val history = snapshot.messageStateChanges.first()

            history.oldDeliveryState shouldBe null
            history.newDeliveryState shouldBe null
            history.oldAppRecStatus shouldBe null
            history.newAppRecStatus shouldBe null
        }

        "record state change – updates external state and appends history" {
            val messageStateService = FakeTransactionalMessageStateService()

            val id = Uuid.random()
            val externalRefId = Uuid.random()

            messageStateService.createInitialState(
                CreateState(
                    id = id,
                    externalRefId = externalRefId,
                    messageType = DIALOG
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

        "get message snapshot – returns null when missing" {
            val messageStateService = FakeTransactionalMessageStateService()

            messageStateService.getMessageSnapshotByExternalRefId(Uuid.random()).shouldBeNull()
        }
    }
)
