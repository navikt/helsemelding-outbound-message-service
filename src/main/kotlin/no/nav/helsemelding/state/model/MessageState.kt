package no.nav.helsemelding.state.model

import java.net.URL
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class MessageState(
    val id: Uuid,
    val messageType: MessageType,
    val externalRefId: Uuid,
    val externalMessageUrl: URL,
    val externalDeliveryState: ExternalDeliveryState?,
    val appRecStatus: AppRecStatus?,
    val lastStateChange: Instant,
    val lastPolledAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant
)

fun MessageState.formatUnchanged(state: MessageDeliveryState): String = "${logPrefix()} unchanged ($state)"

fun MessageState.formatTransition(to: MessageDeliveryState): String = "${logPrefix()} → $to"

fun MessageState.formatInvalidState(): String = "${logPrefix()} entered INVALID state"

fun MessageState.formatExternal(
    newState: ExternalDeliveryState?,
    newAppRecStatus: AppRecStatus?
): String = "${logPrefix()} externalUpdate(delivery=$newState, appRec=$newAppRecStatus)"

private fun MessageState.logPrefix(): String = "Message $externalRefId"
