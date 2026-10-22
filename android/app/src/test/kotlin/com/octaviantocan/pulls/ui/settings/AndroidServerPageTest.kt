package com.octaviantocan.pulls.ui.settings

import com.octaviantocan.pulls.data.RepoIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidServerPageTest {
    @Test
    fun registrationReplaysASourceChangeMissedByTheInitialComposeReadAndCancelsExactly() {
        var observed = RepoIdentity.generation
        RepoIdentity.invalidate() // Between remember's initial read and DisposableEffect registration.
        val stop = observeServerPageEpoch { observed = RepoIdentity.generation }
        try {
            assertEquals(RepoIdentity.generation, observed)
            RepoIdentity.invalidate()
            assertEquals(RepoIdentity.generation, observed)
        } finally {
            stop()
        }
        val stoppedAt = observed
        RepoIdentity.invalidate()
        assertEquals(stoppedAt, observed)
    }
}
