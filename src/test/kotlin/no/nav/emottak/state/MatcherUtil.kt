package no.nav.emottak.state

import arrow.core.Either
import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

private fun beEqualInstant(expected: Instant): Matcher<Instant> = object : Matcher<Instant> {
    override fun test(value: Instant): MatcherResult {
        val v = value.truncateToMillis()
        val e = expected.truncateToMillis()
        return MatcherResult(
            v == e,
            { "Expected Instant <$e> but got <$v>" },
            { "Instants should not have been equal" }
        )
    }
}

infix fun Instant.shouldBeInstant(expected: Instant): Any = this shouldBe beEqualInstant(expected)

infix fun <L> Either<L, *>.shouldBeEitherLeftWhere(predicate: (L) -> Boolean) {
    this.isLeft() shouldBe true
    predicate(this.swap().getOrNull()!!) shouldBe true
}
