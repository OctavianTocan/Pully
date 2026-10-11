package com.octaviantocan.pulls.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * Port of `AppModal.vue`. Below `sm` (640dp) it is a bottom sheet (`rounded-t-xl`, no bottom
 * border); from `sm` up a centered dialog (`rounded-lg shadow-xl`, `max-w-xl`, or `max-w-2xl`
 * when [spacious]) over the `bg-black/40` backdrop. Header: title (`font-medium`), optional
 * description (`text-sm op-fade`) and the close button, with a bottom border; body `p-3`
 * (`p-4 sm:p-6` spacious) that scrolls; optional footer row with a top border.
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
    val scrim = if (c.dark) Color.Black.copy(alpha = 0.4f) else Color(0x66DDDDDD)

    @Composable
    fun ColumnScope.Body(sheet: Boolean, close: () -> Unit) {
        if (title != null || description != null || header != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = if (spacious) (if (sm) 24.dp else 16.dp) else 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    if (title != null) Text(title, style = PullsType.base.copy(fontWeight = FontWeight.Medium), color = c.text)
                    if (description != null) Text(description, style = PullsType.sm, color = c.fade)
                    header?.invoke()
                }
                IconButton("x", t("common.close"), close, compact = true)
            }
            HorizontalDivider(color = c.border)
        }
        val pad = when {
            !bodyPadding -> 0.dp
            spacious -> if (sm) 24.dp else 16.dp
            else -> 12.dp
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
            HorizontalDivider(color = c.border)
            Row(
                Modifier.fillMaxWidth().padding(8.dp).let { if (sheet) it.navigationBarsPadding() else it },
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                content = footer,
            )
        }
    }

    if (sm) {
        Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(
                Modifier.fillMaxSize().background(scrim)
                    .clickable(remember { MutableInteractionSource() }, null, onClick = onClose)
                    .safeDrawingPadding().imePadding().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                val shape = RoundedCornerShape(8.dp)
                Column(
                    Modifier.widthIn(max = if (spacious) 672.dp else 576.dp).fillMaxWidth().heightIn(max = 900.dp)
                        .shadow(24.dp, shape).clip(shape).background(c.base).border(1.dp, c.border, shape)
                        .clickable(remember { MutableInteractionSource() }, null) {},
                ) { Body(sheet = false, close = onClose) }
            }
        }
    } else {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        val shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        ModalBottomSheet(
            onDismissRequest = onClose,
            sheetState = state,
            shape = shape,
            containerColor = c.base,
            contentColor = c.text,
            tonalElevation = 0.dp,
            scrimColor = scrim,
            dragHandle = null,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        ) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val top = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
            val maxHeight = LocalConfiguration.current.screenHeightDp.dp - maxOf(top, 16.dp)
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).border(1.dp, c.border, shape).imePadding()) {
                Body(sheet = true) { scope.launch { state.hide() }.invokeOnCompletion { onClose() } }
            }
        }
    }
}
