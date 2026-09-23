package no.nav.helsemelding.outbound.service

import arrow.core.Either
import no.nav.helsemelding.outbound.LifecycleError
import no.nav.helsemelding.outbound.model.CreateState
import no.nav.helsemelding.outbound.model.MessageStateSnapshot
import no.nav.helsemelding.outbound.model.UpdateState
import no.nav.helsemelding.outbound.repository.FakeMessageRepository
import no.nav.helsemelding.outbound.repository.FakeMessageStateHistoryRepository
import no.nav.helsemelding.outbound.repository.FakeMessageStateTransactionRepository
import no.nav.helsemelding.outbound.repository.MessageRepository
import no.nav.helsemelding.outbound.repository.MessageStateHistoryRepository
import no.nav.helsemelding.outbound.repository.MessageStateTransactionRepository
import kotlin.uuid.Uuid

interface MessageStateService {
    /**
     * Registers a newly accepted message and initializes its tracked external state.
     *
     * This function persists the initial lifecycle state for an incoming message once the external
     * adapter has confirmed that the message has been successfully created in the external system
     * (i.e., an external reference ID has been assigned).
     *
     * The call enforces all lifecycle- and uniqueness constraints:
     * - If the lifecycle ID (`createState.id`) already exists with identical external data
     *   (external reference ID), the operation is **idempotent** and the
     *   existing message state is returned.
     * - If the lifecycle ID already exists but with conflicting external data, the operation
     *   fails with [LifecycleError.ConflictingLifecycleId].
     * - If the external reference ID is already associated with a different
     *   lifecycle ID, the operation fails with [LifecycleError.ConflictingExternalReferenceId].
     *
     * Upon successful creation (or idempotent reuse), an initial history entry is also appended so
     * that the message lifecycle begins from a well-defined and traceable starting point.
     *
     * The entire operation is transactional: the message state and its initial history entry are
     * persisted atomically.
     *
     * @param createState Initial message data, including:
     *   - lifecycle message ID
     *   - external reference ID
     *   - message type
     *   - creation timestamp
     *
     * @return Either:
     *   - `Right(MessageStateSnapshot)` containing the persisted message state and its initial
     *     history entry, or
     *   - `Left(LifecycleError)` describing any lifecycle-related conflict or persistence failure.
     */
    suspend fun createInitialState(createState: CreateState): Either<LifecycleError, MessageStateSnapshot>

    /**
     * Records an update to the external delivery state or application receipt (apprec) status
     * for an existing message.
     *
     * Called when the external system reports a new status for a previously registered
     * message. If either the delivery state or AppRecStatus has changed compared to the
     * currently stored values, the message is updated and a new history entry is recorded.
     *
     * Both tracked aspects of external state may change independently — the transport-level
     * delivery state and the application-level receipt status.
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
     * @param externalRefId The unique external reference identifier of the tracked message.
     * @return A [MessageStateSnapshot] containing the message’s current state and full history
     *         or `null` if no message with the given ID is being tracked.
     */
    suspend fun getMessageSnapshotByExternalRefId(externalRefId: Uuid): MessageStateSnapshot?

    /**
     * Retrieves the current snapshot of a tracked message, including its delivery state and full history.
     *
     * Used when inspecting a specific message’s lifecycle — for example, in diagnostics, API queries,
     * or internal monitoring. The returned snapshot includes both the current delivery state
     * and all previously recorded state transitions.
     *
     * @param id The unique identifier of the tracked message.
     * @return A [MessageStateSnapshot] containing the message’s current state and full history
     *         or `null` if no message with the given ID is being tracked.
     */
    suspend fun getMessageSnapshotById(id: Uuid): MessageStateSnapshot?
}

class TransactionalMessageStateService(
    private val messageRepository: MessageRepository,
    private val historyRepository: MessageStateHistoryRepository,
    private val transactionRepository: MessageStateTransactionRepository
) : MessageStateService {
    override suspend fun createInitialState(createState: CreateState): Either<LifecycleError, MessageStateSnapshot> =
        transactionRepository.createInitialState(createState)

    override suspend fun recordStateChange(updateState: UpdateState): MessageStateSnapshot =
        transactionRepository.recordStateChange(updateState)

    override suspend fun getMessageSnapshotByExternalRefId(externalRefId: Uuid): MessageStateSnapshot? {
        val state = messageRepository.findByExternalReferenceId(externalRefId) ?: return null
        val history = historyRepository.findAll(externalRefId)
        return MessageStateSnapshot(state, history)
    }

    override suspend fun getMessageSnapshotById(id: Uuid): MessageStateSnapshot? {
        val state = messageRepository.findById(id) ?: return null
        val history = historyRepository.findAll(state.externalRefId)
        return MessageStateSnapshot(state, history)
    }
}

class FakeTransactionalMessageStateService : MessageStateService {
    private val messageRepository = FakeMessageRepository()
    private val historyRepository = FakeMessageStateHistoryRepository()
    private val transactionRepository =
        FakeMessageStateTransactionRepository(
            messageRepository,
            historyRepository
        )
    private val messageStateById = mutableMapOf<Uuid, Either<LifecycleError, MessageStateSnapshot>>()

    fun givenInitialState(id: Uuid, either: Either<LifecycleError, MessageStateSnapshot>) {
        messageStateById[id] = either
    }

    override suspend fun createInitialState(createState: CreateState): Either<LifecycleError, MessageStateSnapshot> =
        messageStateById[createState.id] ?: transactionRepository.createInitialState(createState)

    override suspend fun recordStateChange(updateState: UpdateState): MessageStateSnapshot =
        transactionRepository.recordStateChange(updateState)

    override suspend fun getMessageSnapshotByExternalRefId(externalRefId: Uuid): MessageStateSnapshot? {
        val state = messageRepository.findByExternalReferenceId(externalRefId) ?: return null
        val history = historyRepository.findAll(externalRefId)
        return MessageStateSnapshot(state, history)
    }

    override suspend fun getMessageSnapshotById(id: Uuid): MessageStateSnapshot? {
        val state = messageRepository.findById(id) ?: return null
        val history = historyRepository.findAll(state.externalRefId)
        return MessageStateSnapshot(state, history)
    }
}
