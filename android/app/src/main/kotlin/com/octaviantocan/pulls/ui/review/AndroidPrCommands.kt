package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.ui.home.LocalNativeHomeActions

@Composable
fun PrCommands(session: ReviewSession, files: () -> List<ReviewFile>, currentPath: String?, onGo: (String) -> Unit, onAnalyze: () -> Unit, onDiff: Boolean = false) =
    PrCommands(session, files, currentPath, onGo, onAnalyze, LocalNativeHomeActions.current, onDiff)
