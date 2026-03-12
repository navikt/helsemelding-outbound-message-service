# helsemelding-outbound-message-service

The **outbound-message-service** is responsible for tracking the lifecycle of outgoing messages sent via the `edi-adapter`.
It maintains local state, polls the external system for updates, evaluates domain state transitions, and publishes application receipts when transitions complete.

## Service Rationale
The primary goal of the outbound-message-service is to guarantee reliable delivery of outbound messages and to produce a verifiable final delivery outcome for 
each message. Regardless of whether the resolution happens at the transport layer or the application layer, every outbound message ends with a single authoritative 
final state published back into the domain. 

## Why Transport vs. AppRec Are Split
Transport- and application-layer processing represent different concerns but are coupled by the external system's guarantees. If a message is transport-confirmed, 
the external system guarantees that an AppRec will be produced for it — but that AppRec may indicate either success or rejection. Conversely, if transport fails, 
no AppRec will be generated. Splitting pending and rejected states by layer makes it explicit which part of the flow has completed and avoids misinterpreting 
transport success as application-level success.

The service is structured around three core components:

---

## 1. Message Consumption & Initial State Registration

Messages are consumed from Kafka (starting with **dialog messages**, but the system is designed to support additional message types).
Each message type is expected to have its own topic.

For each consumed message:

1. The message is handed to a **message processor** which delegates further processing to **message lifecycle service**.
2. The lifecycle service checks if the **lifecycleId** of the incoming message is already tracked.
   - If it is, then further processing stops and a **LifecycleError** is returned.
   - If it is not, then the lifecycle service signs the payload, and then posts it to the `edi-adapter` using the `edi-adapter-client`.
3. The adapter returns:
   - an **external reference ID**
   - a **URL** to the external message resource
4. These are persisted through:

```kotlin
messageStateService.createInitialState(CreateState(...))
```

This establishes a baseline internal representation of the message in domain state NEW.

## 2. Poller — State Reconciliation & Domain State Machine

The **poller** runs periodically and synchronizes local state with the external system.

### Polling Workflow

For each message that should be polled:

1. The poller calls the `edi-adapter-client`.
2. The response provides:
   - transport-level delivery status
   - application-level receipt status (AppRec)
3. The values are mapped to internal enums:
   - `ExternalDeliveryState`
   - `AppRecStatus`
4. The system computes the internal domain state using the `StateEvaluator`.

### Domain State Derivation

The internal domain state results from combinations of external states:

NEW → PENDING → COMPLETED
↘
REJECTED

Examples:

- `ACKNOWLEDGED + null` → `PENDING`
- `ACKNOWLEDGED + OK` → `COMPLETED`
- `UNCONFIRMED + null` → `PENDING`
- Any `REJECTED` → `REJECTED`

Illegal combinations (e.g., `UNCONFIRMED + REJECTED`) result in an `UnresolvableState` error.

### State Transition Validation

The `StateTransitionValidator` ensures transitions follow defined business rules.

### 🧠 Transition Matrix

Outbound tracking distinguishes between *transport-layer* and *application-layer* pending and rejected states.

The domain-level states therefore expand as follows:

* **PENDING (TRANSPORT)** — awaiting transport confirmation
* **PENDING (APPREC)** — transport confirmed, awaiting AppRec
* **REJECTED (TRANSPORT)** — transport-layer failure or terminal error
* **REJECTED (APPREC)** — application-level rejection

| **From ↓ / To →**           | **NEW** | **PENDING (TRANSPORT)** | **PENDING (APPREC)** | **COMPLETED** | **REJECTED (TRANSPORT)** | **REJECTED (APPREC)** | **INVALID** |
| --------------------------- | ------- | ----------------------- | -------------------- | ------------- | ------------------------ | --------------------- | ----------- |
| **NEW**                     | =       | ✔                       | ✔                    | ❌             | ✔                        | ✔                     | ❌           |
| **PENDING (TRANSPORT)**     | ❌       | =                       | ✔                    | ✔             | ✔                        | ✔                     | ❌           |
| **PENDING (APPREC)**        | ❌       | ❌                       | =                    | ✔             | ✔                        | ✔                     | ❌           |
| **COMPLETED** 🚫            | ❌       | ❌                       | ❌                    | =             | ❌                        | ❌                     | ❌           |
| **REJECTED (TRANSPORT)** 🚫 | ❌       | ❌                       | ❌                    | ❌             | =                        | ❌                     | ❌           |
| **REJECTED (APPREC)** 🚫    | ❌       | ❌                       | ❌                    | ❌             | ❌                        | =                     | ❌           |
| **INVALID** 🚫              | ❌       | ❌                       | ❌                    | ❌             | ❌                        | ❌                     | =           |

---

### ✔ Notes on Specific States

#### NEW → PENDING (TRANSPORT) / PENDING (APPREC)

Occurs when the external system acknowledges receiving the message.

* Transport acknowledged → **PENDING (TRANSPORT)**
* Transport confirmed but AppRec pending → **PENDING (APPREC)**

#### NEW → REJECTED (TRANSPORT) / REJECTED (APPREC)

Represents an immediate negative outcome.

* Transport-level rejection → **REJECTED (TRANSPORT)**
* Immediate AppRec rejection → **REJECTED (APPREC)**

#### PENDING (TRANSPORT) → PENDING (APPREC)

Transport delivery confirmed; waiting for AppRec.

#### PENDING (TRANSPORT) → COMPLETED

This transition is **not possible**. Completion always requires AppRec, which means the message must pass through **PENDING (APPREC)** first.

#### PENDING (APPREC) → COMPLETED

Standard success path once AppRec is OK or OK_ERROR_IN_MESSAGE_PART.

#### PENDING (TRANSPORT) / PENDING (APPREC) → REJECTED (TRANSPORT)

Transport failure after initial acknowledgment.

#### PENDING (APPREC) → REJECTED (APPREC)

Application-level rejection.

#### COMPLETED → COMPLETED

Idempotent. The poller may observe the same resolved state repeatedly.

#### REJECTED (TRANSPORT) → REJECTED (TRANSPORT)

Terminal transport-level failure.

#### REJECTED (APPREC) → REJECTED (APPREC)

Terminal application-level failure.

---

Invalid transitions include:

- `PENDING → NEW`
- `COMPLETED → PENDING`
- `COMPLETED → REJECTED`
- Any transition **out** of `REJECTED`
- Any transition **out** of `INVALID`

Illegal transitions raise `IllegalTransition`, and no state is persisted.

### Persisting & Publishing

If a valid transition occurs:

- Persisted using:

```kotlin
messageStateService.recordStateChange(UpdateState(...))
```

- A history entry is appended.
- If entering a terminal state:

- The service **publishes a final delivery outcome back into the domain once resolution is reached**.

- This outcome may be either:

    * an **application-level receipt (AppRec)**, or
    * a **transport-level terminal error**.

If the state is `INVALID`, no writes or publications occur.

## 3. Persistence Model

The persistence layer provides a durable and transparent view of message lifecycle state.
It consists of a **rich domain model** stored in two tables: a *current state table* and a *state history table*.

### Current Message State

Each message tracked in the system has a single **current state record**, which stores:

- the external reference ID (UUID from the external system)
- the external message URL (link to the resource in the external system)
- the latest resolved domain state (`MessageDeliveryState`)
- the raw external data:
   - `ExternalDeliveryState?`
   - `AppRecStatus?`
- timestamps for:
   - last state change
   - last poll time
   - creation and update time

This enables the system to determine:
- which messages must be polled,
- whether a new external change occurred,
- and what the next domain state should be.

### State History

Every time a message changes state, a **state history entry** is appended.
History entries store:

- old delivery state (raw external)
- new delivery state (raw external)
- old AppRec value
- new AppRec value
- the timestamp of when the change was detected

This history allows:

- complete auditability,
- debugging of incorrect external systems,
- verification of state machine correctness,
- and future observability/analytics.

### Domain Separation

The persistence model deliberately separates:

- **raw external state** (transport + apprec),
- **derived internal domain state**, and
- **validated state transitions**.

This ensures:

- external inconsistencies do not corrupt domain flow,
- the poller can detect invalid or illegal transitions,
- the domain model remains stable even if the external API evolves.

Together, the current state + history tables form a fully traceable and deterministic message state engine.