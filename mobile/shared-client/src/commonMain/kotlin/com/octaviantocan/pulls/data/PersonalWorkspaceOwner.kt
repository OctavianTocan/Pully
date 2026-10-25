package com.octaviantocan.pulls.data

import kotlinx.coroutines.CancellationException

/** Forms/routes belong to this fixed verified session; dispatch belongs to a foreground workspace. */
class PersonalWorkspaceOwner internal constructor(
    private val first: PersonalWorkspace,
    private val owns: () -> Boolean,
    private val acquire: () -> PersonalWorkspace?,
) {
    val accountId = first.accountId
    val githubId = first.githubId
    val login = first.login
    val server = first.server
    private var retired = false
    private val retirements = mutableListOf<() -> Unit>()
    val current: Boolean get() = !retired && owns()

    fun foregroundWorkspace(): PersonalWorkspace? = if (current) acquire() else null

    fun repository(
        engine: () -> String = { "claude-code" },
        model: () -> String = { "" },
        effort: () -> String = { "" },
        admitted: () -> Boolean = { true },
        observer: RpcObserver = RpcObserver { _, _, _, _ -> },
    ): Repo = first.presentationRepository(this, engine, model, effort, admitted, observer)

    fun onRetired(action: () -> Unit): () -> Unit {
        if (!current) { action(); return {} }
        retirements += action
        return { retirements -= action }
    }

    internal fun requireCurrent() {
        if (!current) throw CancellationException("The workspace presentation changed.")
    }

    internal fun retire() {
        if (retired) return
        retired = true
        retirements.toList().forEach { runCatching(it) }
        retirements.clear()
    }

    override fun toString(): String = "PersonalWorkspaceOwner([bound])"
}
