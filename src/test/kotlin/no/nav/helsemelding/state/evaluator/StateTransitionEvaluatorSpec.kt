package no.nav.helsemelding.state.evaluator

import arrow.core.Either.Right
import arrow.core.raise.either
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import no.nav.helsemelding.state.StateTransitionError
import no.nav.helsemelding.state.model.AppRecStatus
import no.nav.helsemelding.state.model.AppRecStatus.OK
import no.nav.helsemelding.state.model.AppRecStatus.OK_ERROR_IN_MESSAGE_PART
import no.nav.helsemelding.state.model.DeliveryEvaluationState
import no.nav.helsemelding.state.model.MessageDeliveryState.NEW
import no.nav.helsemelding.state.model.MessageDeliveryState.PENDING
import no.nav.helsemelding.state.model.MessageDeliveryState.REJECTED
import no.nav.helsemelding.state.model.TransportStatus
import no.nav.helsemelding.state.model.TransportStatus.ACKNOWLEDGED
import no.nav.helsemelding.state.shouldBeLeftWhere

class StateTransitionEvaluatorSpec : StringSpec(
    {
        val transportEvaluator = TransportTransitionEvaluator()
        val appRecEvaluator = AppRecTransitionEvaluator()
        val evaluator = StateTransitionEvaluator(
            transportValidator = transportEvaluator,
            appRecValidator = appRecEvaluator
        )

        fun toDeliveryEvaluationState(transport: TransportStatus, appRec: AppRecStatus?) =
            DeliveryEvaluationState(
                transport = transport,
                appRec = appRec
            )

        "allows apprec transition null → OK when transport is ACKNOWLEDGED" {
            val old = toDeliveryEvaluationState(ACKNOWLEDGED, null)
            val new = toDeliveryEvaluationState(ACKNOWLEDGED, OK)

            either { with(evaluator) { evaluate(old, new) } } shouldBe Right(Unit)
        }

        "allows apprec transition null → OK_ERROR_IN_MESSAGE_PART when transport is ACKNOWLEDGED" {
            val old = toDeliveryEvaluationState(ACKNOWLEDGED, null)
            val new = toDeliveryEvaluationState(ACKNOWLEDGED, OK_ERROR_IN_MESSAGE_PART)

            either { with(evaluator) { evaluate(old, new) } } shouldBe Right(Unit)
        }

        "allows apprec transition null → REJECTED when transport is ACKNOWLEDGED" {
            val old = toDeliveryEvaluationState(ACKNOWLEDGED, null)
            val new = toDeliveryEvaluationState(ACKNOWLEDGED, AppRecStatus.REJECTED)

            either { with(evaluator) { evaluate(old, new) } } shouldBe Right(Unit)
        }

        "rejects changing apprec once received (OK → REJECTED)" {
            val old = toDeliveryEvaluationState(ACKNOWLEDGED, OK)
            val new = toDeliveryEvaluationState(ACKNOWLEDGED, AppRecStatus.REJECTED)

            val result = either { with(evaluator) { evaluate(old, new) } }

            result shouldBeLeftWhere {
                it is StateTransitionError.IllegalAppRecTransition &&
                    it.from == OK &&
                    it.to == AppRecStatus.REJECTED
            }
        }

        "rejects changing apprec once received (OK_ERROR_IN_MESSAGE_PART → OK)" {
            val old = toDeliveryEvaluationState(ACKNOWLEDGED, OK_ERROR_IN_MESSAGE_PART)
            val new = toDeliveryEvaluationState(ACKNOWLEDGED, OK)

            val result = either { with(evaluator) { evaluate(old, new) } }

            result shouldBeLeftWhere {
                it is StateTransitionError.IllegalAppRecTransition &&
                    it.from == OK_ERROR_IN_MESSAGE_PART &&
                    it.to == OK
            }
        }

        "rejects removing apprec once received (OK → null)" {
            val old = toDeliveryEvaluationState(ACKNOWLEDGED, OK)
            val new = toDeliveryEvaluationState(ACKNOWLEDGED, null)

            val result = either { with(evaluator) { evaluate(old, new) } }

            result shouldBeLeftWhere {
                it is StateTransitionError.IllegalAppRecTransition &&
                    it.from == OK &&
                    it.to == null
            }
        }

        "rejects having apprec when transport is NEW (cross-invariant)" {
            val old = toDeliveryEvaluationState(TransportStatus.NEW, null)
            val new = toDeliveryEvaluationState(TransportStatus.NEW, AppRecStatus.OK)

            val result = either { with(evaluator) { evaluate(old, new) } }

            result shouldBeLeftWhere {
                it is StateTransitionError.IllegalCombinedState &&
                    it.message.contains("AppRec requires transport ACKNOWLEDGED")
            }
        }

        "rejects having apprec when transport is PENDING (cross-invariant)" {
            val old = toDeliveryEvaluationState(TransportStatus.PENDING, null)
            val new = toDeliveryEvaluationState(TransportStatus.PENDING, AppRecStatus.OK_ERROR_IN_MESSAGE_PART)

            val result = either { with(evaluator) { evaluate(old, new) } }

            result shouldBeLeftWhere {
                it is StateTransitionError.IllegalCombinedState &&
                    it.message.contains("AppRec requires transport ACKNOWLEDGED")
            }
        }

        "rejects having apprec when transport is REJECTED (cross-invariant)" {
            val old = toDeliveryEvaluationState(TransportStatus.REJECTED, null)
            val new = toDeliveryEvaluationState(TransportStatus.REJECTED, AppRecStatus.REJECTED)

            val result = either { with(evaluator) { evaluate(old, new) } }

            result shouldBeLeftWhere {
                it is StateTransitionError.IllegalCombinedState &&
                    it.message.contains("AppRec requires transport ACKNOWLEDGED")
            }
        }

        "allows no-op (same snapshot) PENDING+null → PENDING+null" {
            val old = toDeliveryEvaluationState(TransportStatus.PENDING, null)
            val new = toDeliveryEvaluationState(TransportStatus.PENDING, null)

            either { with(evaluator) { evaluate(old, new) } } shouldBe Right(Unit)
        }

        "rejects resolved PENDING → NEW (PENDING+null → NEW+null)" {
            val old = toDeliveryEvaluationState(TransportStatus.PENDING, null)
            val new = toDeliveryEvaluationState(TransportStatus.NEW, null)

            val result = either { with(evaluator) { evaluate(old, new) } }

            result shouldBeLeftWhere {
                it is StateTransitionError.IllegalTransition &&
                    it.from == PENDING &&
                    it.to == NEW
            }
        }

        "allows resolved NEW → PENDING (NEW+null → PENDING+null)" {
            val old = toDeliveryEvaluationState(TransportStatus.NEW, null)
            val new = toDeliveryEvaluationState(TransportStatus.PENDING, null)

            either { with(evaluator) { evaluate(old, new) } } shouldBe Right(Unit)
        }

        "allows transport-only rejection (PENDING+null → REJECTED+null)" {
            val old = toDeliveryEvaluationState(TransportStatus.PENDING, null)
            val new = toDeliveryEvaluationState(TransportStatus.REJECTED, null)

            either { with(evaluator) { evaluate(old, new) } } shouldBe Right(Unit)
        }

        "rejects leaving resolved REJECTED (REJECTED+null → ACKNOWLEDGED+null)" {
            val old = toDeliveryEvaluationState(TransportStatus.REJECTED, null)
            val new = toDeliveryEvaluationState(ACKNOWLEDGED, null)

            val result = either { with(evaluator) { evaluate(old, new) } }

            result shouldBeLeftWhere {
                it is StateTransitionError.IllegalTransition &&
                    it.from == REJECTED &&
                    it.to == PENDING
            }
        }

        "rejects resolved COMPLETED → REJECTED (ACKNOWLEDGED+OK → ACKNOWLEDGED+REJECTED) (COMPLETED → REJECTED illegal)" {
            val old = toDeliveryEvaluationState(ACKNOWLEDGED, OK)
            val new = toDeliveryEvaluationState(ACKNOWLEDGED, AppRecStatus.REJECTED)

            val result = either { with(evaluator) { evaluate(old, new) } }

            result shouldBeLeftWhere {
                it is StateTransitionError.IllegalAppRecTransition &&
                    it.from == OK &&
                    it.to == AppRecStatus.REJECTED
            }
        }
    }
)
