package com.octaviantocan.pulls.ui.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.data.ReviewLens
import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.ui.review.SessionStateKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private val lensesKey = SessionStateKey<ReviewLenses>()

/** The existing short, cached-first skills list belongs to the same opaque source as every review. */
internal fun reviewLenses(session: ReviewSession): ReviewLenses? = session.sourcePresentation(lensesKey) { ReviewLenses() }

internal class ReviewLenses {
    var list by mutableStateOf<List<ReviewLens>?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var loading: Any? = null

    fun titleOf(name: String): String? = list?.firstOrNull { it.name == name }?.title

    fun load(session: ReviewSession) {
        if (reviewLenses(session) !== this || loading != null) return
        val request = Any()
        loading = request
        session.scope.launch {
            try {
                val value = session.repo.lenses()
                currentCoroutineContext().ensureActive()
                if (reviewLenses(session) !== this@ReviewLenses || loading !== request) return@launch
                list = value
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (reviewLenses(session) === this@ReviewLenses && loading === request) error = failure.message ?: failure.toString()
            } finally {
                if (loading === request) loading = null
            }
        }
    }
}
