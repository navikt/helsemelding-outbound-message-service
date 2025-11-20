package no.nav.emottak.state.service

import no.nav.emottak.state.model.AppRecStatus
import no.nav.emottak.state.model.ExternalDeliveryState
import no.nav.emottak.state.model.MessageState
import no.nav.emottak.state.model.MessageStateSnapshot
import no.nav.emottak.state.model.MessageType
import no.nav.emottak.state.repository.MessageRepository
import no.nav.emottak.state.repository.MessageStateHistoryRepository
import no.nav.emottak.state.repository.MessageStateTransactionRepository
import java.net.URL
import kotlin.time.Clock
import kotlin.uuid.Uuid

interface MessageStateService {
    /**
     * Registers a newly accepted message and initializes its tracked external state.
     *
     * Called when the adapter confirms that a message has been successfully created in the
     * external system. At this point, no external delivery information is known, so both
     * the delivery state and the application-level receipt status (AppRecStatus) are stored
     * as `null`. This establishes a consistent baseline before the poller begins to fetch
     * real external state from the remote system.
     *
     * An initial history entry is also recorded so that the message’s state history starts
     * from a well-defined origin.
     *
     * This method is transactional — the message record and its initial history entry
     * are guaranteed to be persisted atomically.
     *
     * @param messageType The domain category of the message (e.g., [MessageType.DIALOG]).
     * @param externalRefId The UUID reference returned by the external API.
     * @param externalMessageUrl The URL pointing to the message resource in the external system.
     * @return A [MessageStateSnapshot] containing the newly stored message and its initial history.
     */
    suspend fun createInitialState(
        messageType: MessageType,
        externalRefId: Uuid,
        externalMessageUrl: URL
    ): MessageStateSnapshot

    /**
     * Records a change in external delivery state or application receipt status for an existing message.
     *
     * Called when the external system reports an updated status for a previously registered message.
     * This function compares the new state against what is already stored and, if a change is detected,
     * updates the message’s external state and appends a corresponding history entry.
     *
     * Either the delivery state or the application receipt status (or both) may have changed.
     * The external message URL remains unchanged and is not modified.
     *
     * This method is transactional — the message update and its history entry are committed as a single unit.
     *
     * @param messageType The domain category of the message (e.g., [MessageType.DIALOG]).
     * @param externalRefId The unique identifier of the message in the external system.
     * @param oldDeliveryState The previously known transport-level delivery state, or `null` if unknown.
     * @param newDeliveryState The newly reported delivery state from the external system, or `null`.
     * @param oldAppRecStatus The previously stored application-level receipt status, or `null`.
     * @param newAppRecStatus The newly reported application receipt status, or `null`.
     * @return A [MessageStateSnapshot] containing the updated message and its full change history.
     */
    suspend fun recordStateChange(
        messageType: MessageType,
        externalRefId: Uuid,
        oldDeliveryState: ExternalDeliveryState?,
        newDeliveryState: ExternalDeliveryState?,
        oldAppRecStatus: AppRecStatus?,
        newAppRecStatus: AppRecStatus?
    ): MessageStateSnapshot

    /**
     * Retrieves the current snapshot of a tracked message, including its delivery state and full history.
     *
     * Used when inspecting a specific message’s lifecycle — for example, in diagnostics, API queries,
     * or internal monitoring. The returned snapshot includes both the current delivery state
     * and all previously recorded state transitions.
     *
     * @param messageId The unique identifier of the tracked message.
     * @return A [MessageStateSnapshot] containing the message’s current state and full history,
     *         or `null` if no message with the given ID is being tracked.
     */
    suspend fun getMessageSnapshot(messageId: Uuid): MessageStateSnapshot?

    /**
     * Finds messages that are candidates for polling against the external system.
     *
     * A message is considered pollable when no external delivery state has been recorded yet
     * (i.e., `externalDeliveryState` is `null`). These messages require periodic polling so
     * the system can obtain their initial delivery and processing information from the remote
     * API.
     *
     * Only messages that have not been polled recently are included. This is determined by
     * comparing each message’s `lastPolledAt` timestamp against the configured minimum polling
     * interval (see [PollerConfig.minAgeSeconds]).
     *
     * To avoid excessive polling load, the number of returned messages is limited by the
     * configured fetch limit (see [PollerConfig.fetchLimit]).
     *
     * Default configuration values:
     * - `minAgeSeconds`: 30 seconds — a message must be at least this old since it was last polled
     * - `fetchLimit`: 100 messages — the maximum number of messages fetched per polling cycle
     *
     * These defaults can be overridden through application configuration or environment variables.
     *
     * @return a list of messages that are eligible for polling based on external state and timing.
     */
    suspend fun findPollableMessages(): List<MessageState>

    /**
     * Marks one or more messages as having been polled.
     *
     * This function updates the `lastPolledAt` [Instant] for the given messages,
     * indicating that their external delivery status has recently been checked.
     *
     * It is typically called by the poller component after each polling cycle,
     * once messages have been evaluated and any necessary state changes recorded.
     *
     * Updating this [Instant] ensures that the same messages will not be picked up
     * again in the next polling iteration until the configured minimum polling interval
     * has elapsed (see [PollerConfig.minAgeSeconds]).
     *
     * Default configuration values:
     * - `minAgeSeconds`: 30 seconds — a message must be at least this old since last polling
     *
     * @param externalRefIds A list of external reference id's corresponding to the messages
     *        that have just been polled successfully.
     *
     * @return number of messages marked as polled.
     */
    suspend fun markAsPolled(externalRefIds: List<Uuid>): Int
}

class TransactionalMessageStateService(
    private val messageRepository: MessageRepository,
    private val historyRepository: MessageStateHistoryRepository,
    private val transactionRepository: MessageStateTransactionRepository
) : MessageStateService {

    override suspend fun createInitialState(
        messageType: MessageType,
        externalRefId: Uuid,
        externalMessageUrl: URL
    ): MessageStateSnapshot =
        transactionRepository.createInitialState(
            messageType = messageType,
            externalRefId = externalRefId,
            externalMessageUrl = externalMessageUrl,
            occurredAt = Clock.System.now()
        )

    override suspend fun recordStateChange(
        messageType: MessageType,
        externalRefId: Uuid,
        oldDeliveryState: ExternalDeliveryState?,
        newDeliveryState: ExternalDeliveryState?,
        oldAppRecStatus: AppRecStatus?,
        newAppRecStatus: AppRecStatus?
    ): MessageStateSnapshot =
        transactionRepository.recordStateChange(
            messageType = messageType,
            externalRefId = externalRefId,
            oldDeliveryState = oldDeliveryState,
            newDeliveryState = newDeliveryState,
            oldAppRecStatus = oldAppRecStatus,
            newAppRecStatus = newAppRecStatus,
            occurredAt = Clock.System.now()
        )

    override suspend fun getMessageSnapshot(messageId: Uuid): MessageStateSnapshot? {
        val state = messageRepository.findOrNull(messageId) ?: return null
        val history = historyRepository.findAll(messageId)
        return MessageStateSnapshot(state, history)
    }

    override suspend fun findPollableMessages(): List<MessageState> = messageRepository.findForPolling()

    override suspend fun markAsPolled(externalRefIds: List<Uuid>): Int = messageRepository.markPolled(externalRefIds)
}
