package com.octaviantocan.pulls.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * The app's modal. Below 640dp it is the prototype's sheet (paper, 24dp top corners, a grabber, a
 * header with the close button on the left and the title centered); from there up, a centered paper
 * dialog (`max-w-xl`, or `max-w-2xl` when [spacious]) over the scrim. The body scrolls; an optional
 * footer row of actions sits under a hairline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppModal(
    onClose: () -> Unit,
    title: String? = null,
    description: String? = null,
    spacious: Boolean = false,
    scrollBody: Boolean = true,
    bodyPadding: Boolean = true,
    footer: (@Composable RowScope.() -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalPullsColors.current
    val sm = LocalConfiguration.current.screenWidthDp >= 640

    @Composable
    fun ColumnScope.Body(sheet: Boolean, close: () -> Unit) {
        if (title != null || description != null || header != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = if (sheet) 16.dp else 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FloatIcon(Lu.close, t("common.close"), close, flat = true, iconSize = 22.dp)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (title != null) Text(title, style = PullsType.lg.copy(fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center), color = c.text)
                    if (description != null) Text(description, style = PullsType.sm.copy(textAlign = TextAlign.Center), color = c.fade)
                    header?.invoke()
                }
                Spacer(Modifier.size(44.dp))
            }
        }
        val pad = when {
            !bodyPadding -> 0.dp
            spacious -> if (sm) 24.dp else 16.dp
            else -> 16.dp
        }
        val body = Modifier.fillMaxWidth().weight(1f, fill = false)
        Column(
            (if (scrollBody) body.verticalScroll(rememberScrollState()) else body)
                .padding(pad)
                .let { if (sheet && footer == null) it.navigationBarsPadding() else it },
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
        if (footer != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderMute))
            Row(
                Modifier.fillMaxWidth().padding(12.dp).let { if (sheet) it.navigationBarsPadding() else it },
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                content = footer,
            )
        }
    }

    if (sm) {
        Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(
                Modifier.fillMaxSize().background(c.scrim)
                    .clickable(remember { MutableInteractionSource() }, null, onClick = onClose)
                    .safeDrawingPadding().imePadding().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                val shape = RoundedCornerShape(24.dp)
                Column(
                    Modifier.widthIn(max = if (spacious) 672.dp else 576.dp).fillMaxWidth().heightIn(max = 900.dp)
                        .shadow(24.dp, shape).clip(shape).background(c.paper)
                        .clickable(remember { MutableInteractionSource() }, null) {},
                ) { Body(sheet = false, close = onClose) }
            }
        }
    } else {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ModalBottomSheet(
            onDismissRequest = onClose,
            sheetState = state,
            shape = shape,
            containerColor = c.paper,
            contentColor = c.text,
            tonalElevation = 0.dp,
            scrimColor = c.scrim,
            dragHandle = null,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        ) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val top = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
            val maxHeight = LocalConfiguration.current.screenHeightDp.dp - maxOf(top, 16.dp)
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).imePadding()) {
                Box(Modifier.padding(top = 10.dp, bottom = 10.dp).align(Alignment.CenterHorizontally).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFD9D9D6)))
                Body(sheet = true) { scope.launch { state.hide() }.invokeOnCompletion { onClose() } }
            }
        }
    }
}
