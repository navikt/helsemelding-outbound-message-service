package no.nav.helsemelding.state.service

import no.nav.helsemelding.state.model.CreateState
import no.nav.helsemelding.state.model.MessageState
import no.nav.helsemelding.state.model.MessageStateSnapshot
import no.nav.helsemelding.state.model.UpdateState
import no.nav.helsemelding.state.repository.FakeMessageRepository
import no.nav.helsemelding.state.repository.FakeMessageStateHistoryRepository
import no.nav.helsemelding.state.repository.FakeMessageStateTransactionRepository
import no.nav.helsemelding.state.repository.MessageRepository
import no.nav.helsemelding.state.repository.MessageStateHistoryRepository
import no.nav.helsemelding.state.repository.MessageStateTransactionRepository
import kotlin.uuid.Uuid

interface MessageStateService {
    /**
     * Registers a newly accepted message and initializes its tracked external state.
     *
     * This is called after the adapter confirms that the message has been created in the
     * external system (external reference ID + message URL received). At this point no delivery
     * or AppRec information is available, so those fields are recorded as unknown (`null`)
     * until the poller starts retrieving real state from the external API.
     *
     * The provided message ID becomes the lifecycle identifier and is used to
     * correlate all later status updates.
     *
     * An initial history entry is also created so that the message lifecycle begins from a
     * well-defined starting point.
     *
     * The operation is transactional — the message row and its initial history entry are
     * persisted atomically.
     *
     * @param createState Initial data for the message including the message ID, external reference ID,
     *        message type, external message URL and creation timestamp.
     * @return A [MessageStateSnapshot] containing the persisted message and its initial history entry.
     */
    suspend fun createInitialState(createState: CreateState): MessageStateSnapshot

    /**
     * Records an update to the external delivery state or application receipt status
     * for an existing message.
     *
     * Called when the external system reports a new status for a previously registered
     * message. If either the delivery state or AppRecStatus has changed compared to the
     * currently stored values, the message is updated and a new history entry is recorded.
     *
     * Both tracked aspects of external state may change independently — the transport-level
     * delivery state and the application-level receipt status. The external message URL
     * remains immutable and is not modified.
     *
     * The operation is transactional — the message update and its corresponding history
     * entry are committed atomically to ensure consistency.
     *
     * @param updateState A value object containing the external reference id, message type,
     *        previous and new external states and the timestamp at which the change occurred.
     * @return A [MessageStateSnapshot] containing the updated message and its complete history.
     */
    suspend fun recordStateChange(updateState: UpdateState): MessageStateSnapshot

    /**
     * Retrieves the current snapshot of a tracked message, including its delivery state and full history.
     *
     * Used when inspecting a specific message’s lifecycle — for example, in diagnostics, API queries,
     * or internal monitoring. The returned snapshot includes both the current delivery state
     * and all previously recorded state transitions.
     *
     * @param messageId The unique identifier of the tracked message.
     * @return A [MessageStateSnapshot] containing the message’s current state and full history
     *         or `null` if no message with the given ID is being tracked.
     */
    suspend fun getMessageSnapshot(messageId: Uuid): MessageStateSnapshot?

    /**
     * Returns messages that are candidates for polling against the external system.
     *
     * A message is considered *pollable* when:
     *
     * 1. Its external delivery state indicates that the external processing is still in progress:
     *    - `externalDeliveryState` is `null` (initial NEW state), or
     *    - `externalDeliveryState` is `ACKNOWLEDGED` or `UNCONFIRMED` (PENDING states).
     *
     * 2. No application-level receipt has been recorded yet (`appRecStatus` is `null`).
     *    This ensures that messages in terminal states such as `COMPLETED` or `REJECTED`
     *    are **never** polled again.
     *
     * 3. The message has either never been polled before (`lastPolledAt` is `null`),
     *    or it was last polled sufficiently long ago, as defined by
     *    [PollerConfig.minAgeSeconds]. This prevents repeatedly hammering the same
     *    in-progress messages.
     *
     * The query is additionally restricted by [PollerConfig.fetchLimit], which defines
     * the maximum number of messages returned in a single polling cycle. This bound
     * controls database load and provides predictable throughput when many messages
     * are in an in-progress state.
     *
     * Note that `fetchLimit` only determines how many messages are *fetched* from the
     * database. Polling and write-back are performed in smaller logical units based on
     * [PollerConfig.batchSize], which controls how many messages are processed and
     * marked as polled at a time. This allows tuning of external load (e.g., NHN)
     * independently of database fetch size.
     *
     * Default configuration values:
     * - `minAgeSeconds`: 30 seconds — minimum age since last poll.
     * - `fetchLimit`: 200 messages — maximum number fetched per cycle.
     * - `batchSize`: 25 messages — number processed per batch.
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
    override suspend fun createInitialState(createState: CreateState): MessageStateSnapshot =
        transactionRepository.createInitialState(createState)

    override suspend fun recordStateChange(updateState: UpdateState): MessageStateSnapshot =
        transactionRepository.recordStateChange(updateState)

    override suspend fun getMessageSnapshot(messageId: Uuid): MessageStateSnapshot? {
        val state = messageRepository.findOrNull(messageId) ?: return null
        val history = historyRepository.findAll(messageId)
        return MessageStateSnapshot(state, history)
    }

    override suspend fun findPollableMessages(): List<MessageState> = messageRepository.findForPolling()

    override suspend fun markAsPolled(externalRefIds: List<Uuid>): Int = messageRepository.markPolled(externalRefIds)
}

class FakeTransactionalMessageStateService() : MessageStateService {
    private val messageRepository = FakeMessageRepository()
    private val historyRepository = FakeMessageStateHistoryRepository()
    private val transactionRepository =
        FakeMessageStateTransactionRepository(
            messageRepository,
            historyRepository
        )

    private val transactionalMessageStateService =
        TransactionalMessageStateService(
            messageRepository,
            historyRepository,
            transactionRepository
        )

    override suspend fun createInitialState(createState: CreateState): MessageStateSnapshot =
        transactionalMessageStateService.createInitialState(createState)

    override suspend fun recordStateChange(updateState: UpdateState): MessageStateSnapshot =
        transactionalMessageStateService.recordStateChange(updateState)

    override suspend fun getMessageSnapshot(messageId: Uuid): MessageStateSnapshot? =
        transactionalMessageStateService.getMessageSnapshot(messageId)

    override suspend fun findPollableMessages(): List<MessageState> =
        transactionalMessageStateService.findPollableMessages()

    override suspend fun markAsPolled(externalRefIds: List<Uuid>): Int =
        transactionalMessageStateService.markAsPolled(externalRefIds)
}
