package com.v2ray.ang.ui.qsn.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether a finished scan continues or fails.
 *
 * This is the post-scan bug as a test: a scan that measured candidates but
 * produced no ranker winner (a timeout, a dropped callback) must still offer a
 * choice instead of reporting "no working IP".
 */
class CandidateSelectionTest {

    private fun candidate(
        ip: String,
        ping: Long? = 40L,
        answered: Boolean = true,
    ) = ScanCandidate(
        ip = ip,
        pingMs = if (answered) ping else null,
        jitterMs = if (answered) 3L else null,
        downloadKBps = if (answered) 800L else null,
        uploadKBps = if (answered) 120L else null,
        successes = if (answered) 3 else 0,
        failures = if (answered) 0 else 3,
    )

    @Test
    fun theRankerWinnerIsTheSuggestion() {
        val measured = listOf(candidate("104.16.0.1"), candidate("104.16.0.2", ping = 20L))
        assertEquals("104.16.0.1", CandidateSelection.suggestion("104.16.0.1", measured))
    }

    @Test
    fun withoutAWinnerTheFastestAnsweredCandidateIsSuggested() {
        val measured = listOf(
            candidate("104.16.0.3", ping = 90L),
            candidate("104.16.0.1", ping = 30L),
            candidate("104.16.0.2", ping = 60L),
        )
        assertEquals("104.16.0.1", CandidateSelection.suggestion(null, measured))
    }

    @Test
    fun unansweredCandidatesAreNeverSuggested() {
        val measured = listOf(
            candidate("104.16.0.1", answered = false),
            candidate("104.16.0.2", ping = 80L),
        )
        assertEquals("104.16.0.2", CandidateSelection.suggestion(null, measured))
    }

    @Test
    fun nothingAnsweredMeansNoSuggestion() {
        val measured = listOf(
            candidate("104.16.0.1", answered = false),
            candidate("104.16.0.2", answered = false),
        )
        assertNull(CandidateSelection.suggestion(null, measured))
        assertFalse(CandidateSelection.canContinue(null, measured, earlyPick = null))
    }

    @Test
    fun anEmptyBatchMeansNoSuggestion() {
        assertNull(CandidateSelection.suggestion(null, emptyList()))
        assertFalse(CandidateSelection.canContinue(null, emptyList(), earlyPick = null))
    }

    @Test
    fun aMeasuredCandidateContinuesTheFlowWithoutARankerWinner() {
        val measured = listOf(candidate("104.16.0.1"))
        assertTrue(CandidateSelection.canContinue(null, measured, earlyPick = null))
        assertEquals("104.16.0.1", CandidateSelection.suggestion(null, measured))
    }

    @Test
    fun anEarlyPickKeepsTheFlowAliveEvenWhenNothingElseAnswered() {
        val measured = listOf(candidate("104.16.0.1", answered = false))
        assertTrue(CandidateSelection.canContinue(null, measured, earlyPick = "104.16.0.1"))
    }

    @Test
    fun tiesAreBrokenByAddressSoTheChoiceIsStable() {
        val measured = listOf(candidate("104.16.0.9", ping = 50L), candidate("104.16.0.4", ping = 50L))
        assertEquals("104.16.0.4", CandidateSelection.suggestion(null, measured))
    }

    @Test
    fun aBlankWinnerIsTreatedAsNoWinner() {
        val measured = listOf(candidate("104.16.0.1"))
        assertEquals("104.16.0.1", CandidateSelection.suggestion("", measured))
        assertEquals("104.16.0.1", CandidateSelection.suggestion("   ", measured))
    }
}
