package com.octaviantocan.pulls.ui.ai

import android.content.Context
import com.octaviantocan.pulls.data.ENGINES
import com.octaviantocan.pulls.data.ServerStore

/** Claude Code, Codex and Pi run as server tasks; other engines can't review, ask or chat (web `isCliEngine`). */
internal fun cliEngine(context: Context): Boolean = ServerStore.load(context).engine in ENGINES
