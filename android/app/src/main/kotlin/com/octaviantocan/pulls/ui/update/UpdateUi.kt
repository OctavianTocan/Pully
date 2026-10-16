package com.octaviantocan.pulls.ui.update

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.octaviantocan.pulls.core.ChangelogPicture
import com.octaviantocan.pulls.core.UpdateManifest
import com.octaviantocan.pulls.core.compareVersions
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.update.AppVersion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.octaviantocan.pulls.core.ChangelogEntry
import com.octaviantocan.pulls.core.plainChangelogText
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.ModalReveal
import com.octaviantocan.pulls.ui.design.Orb
import com.octaviantocan.pulls.ui.design.ProgressRing
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.edgeFade
import com.octaviantocan.pulls.ui.design.hairlineTop
import com.octaviantocan.pulls.ui.design.headerTopPadding
import com.octaviantocan.pulls.update.UpdatePrefs
import com.octaviantocan.pulls.update.UpdateStatus
import com.octaviantocan.pulls.update.Updates

/**
 * The update screen. While the server offers a newer build Pully shows only this, from the first frame of a start
 * (Tavi, 15 Oct: "When I have not updated and I enter the app, show me the changelog! Make me update!"). It names the
 * version and lists what it brings over the installed one, newest first, with Update at its foot. Update downloads and
 * installs in the app and says how far it got (a ring while it downloads, the orb while the system installs); a
 * download or install that fails offers Try again. Nothing closes it but the update: Back leaves Pully ([onLeave]), as
 * Back does on the first page. It opens as a modal (rule 73).
 */
@Composable
internal fun UpdateGate(onLeave: () -> Unit) {
    val context = LocalContext.current
    val manifest = Updates.available
    // Keeps the offer on screen while the screen leaves.
    var last by remember { mutableStateOf(manifest) }
    if (manifest != null) last = manifest
    ModalReveal(
        manifest != null, onLeave,
        DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        val offer = last ?: return@ModalReveal
        val c = LocalPullsColors.current
        val entries = remember(offer) { Updates.changes(context) }
        val scroll = rememberScrollState()
        Column(
            Modifier.fillMaxSize().background(c.base).padding(top = headerTopPadding()).navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            Column(
                Modifier.weight(1f).fillMaxWidth()
                    .edgeFade(
                        top = PullsMotion.EdgeTop, bottom = PullsMotion.EdgeTop,
                        topStrength = { if (scroll.canScrollBackward) 1f else 0f },
                        bottomStrength = { if (scroll.canScrollForward) 1f else 0f },
                    )
                    .verticalScroll(scroll)
                    .padding(bottom = 16.dp),
            ) {
                Text("Pully ${offer.versionName} is ready", style = PullsType.xxl, color = c.text)
                Text("Update to keep using Pully. This is what it brings.", Modifier.padding(top = 8.dp), style = PullsType.read, color = c.muted)
                ChangelogEntries(entries, Modifier.padding(top = 20.dp), offer)
            }
            UpdateFooter(offer.versionName) {
                // The changelog was read here, so the What's new sheet does not open with it again after the install.
                UpdatePrefs.markSeen(context, offer.versionName)
                Updates.start(context)
            }
        }
    }
}

/** What the update is doing, in words short enough for one line. */
private fun statusLine(status: UpdateStatus): String = when (status) {
    UpdateStatus.Idle -> ""
    UpdateStatus.NeedsPermission -> "Allow installs first."
    is UpdateStatus.Downloading -> "Downloading…"
    UpdateStatus.Confirming -> "Confirm the update."
    UpdateStatus.Installing -> "Installing…"
    is UpdateStatus.Failed -> status.message
}

/**
 * What's new, as a sheet: each version's summary and what it changed, newest first. It opens by itself once
 * after an update, and from Settings and Commands.
 */
@Composable
internal fun WhatsNewSheet(entries: List<ChangelogEntry>, onClose: () -> Unit) {
    PullsSheet(onClose = onClose, title = "What's new") {
        ChangelogEntries(entries)
    }
}

/** The update screen's Update: the dark main action, and once it runs, words for how far it got. */
@Composable
private fun ColumnScope.UpdateFooter(version: String, onUpdate: () -> Unit) {
    val c = LocalPullsColors.current
    val status = Updates.status
    // Back from the install settings without allowing it, Update is offered again with the reason above it.
    if (!Updates.busy) {
        if (status != UpdateStatus.Idle) {
            Text(
                statusLine(status), Modifier.padding(bottom = 10.dp).align(Alignment.CenterHorizontally),
                style = PullsType.sm, color = if (status is UpdateStatus.Failed) c.error else c.muted,
            )
        }
        CtaButton(if (status is UpdateStatus.Failed) "Try again" else "Update to $version", onUpdate, Modifier.fillMaxWidth())
    } else {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
            if (status is UpdateStatus.Downloading && status.fraction >= 0f) ProgressRing(status.fraction, size = 30.dp) else Orb(size = 30.dp)
            Text(statusLine(status), style = PullsType.sm, color = c.text, maxLines = 2)
        }
    }
}

/**
 * Versions as the changelog writes them, each its own block as good release notes are (J6): the number in Mona Sans
 * as a small header, the newest one tagged New, the summary as the headline, then the bullets one to a line with a
 * dot and room between them, under their `### New` or `### Fixed` heading when the section has those. A hairline
 * parts one version from the next. No card around them: a sheet's body is its list (rule 79). Each picture or GIF
 * shows under the line it follows (Tavi, 16 Oct 10:49). The pictures of the versions this build carries come from its
 * assets; on the update screen, those of a version not installed yet come from the server next to [offer]'s APK.
 */
@Composable
internal fun ChangelogEntries(entries: List<ChangelogEntry>, modifier: Modifier = Modifier, offer: UpdateManifest? = null) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val pictures = remember(offer) { ChangelogPictures(context, offer) }
    Column(modifier.fillMaxWidth().padding(top = 8.dp)) {
        if (entries.isEmpty()) Text("Nothing is written down for this version.", style = PullsType.read, color = c.muted)
        entries.forEachIndexed { index, entry ->
            Column(
                Modifier.fillMaxWidth()
                    .then(if (index > 0) Modifier.hairlineTop(c.borderMute).padding(top = 24.dp) else Modifier)
                    .padding(bottom = 28.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(entry.version, style = PullsType.num, color = c.muted)
                    if (index == 0) NewTag()
                }
                if (entry.summary.isNotBlank()) {
                    Text(plainChangelogText(entry.summary), Modifier.padding(top = 6.dp), style = PullsType.lg.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                }
                entry.picturesAfter(-1).forEach { ChangelogPictureView(pictures, entry.version, it, Modifier.padding(top = 14.dp)) }
                // Pictures point at a bullet by its place in the whole section, across the groups.
                var at = 0
                entry.groups.forEach { group ->
                    val title = group.title
                    if (title != null) Text(title, Modifier.padding(top = 18.dp), style = PullsType.sm.copy(fontWeight = FontWeight.SemiBold), color = c.muted)
                    Column(Modifier.padding(top = if (title != null) 8.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        group.items.forEach { item ->
                            val index = at++
                            ChangelogItem(item)
                            entry.picturesAfter(index).forEach { ChangelogPictureView(pictures, entry.version, it, Modifier.padding(start = 18.dp)) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Where a changelog's pictures load from. A version this build carries has its pictures in the assets (the build
 * copies `android/changelog/` there); a newer one, read on the update screen before it is installed, has them on the
 * server beside its APK, asked for with the same Access headers as the update itself.
 */
internal class ChangelogPictures(context: Context, offer: UpdateManifest?) {
    private val installed = AppVersion.name(context)
    private val serverDir = offer?.apkUrl?.substringBeforeLast('/', "")?.takeIf { it.isNotBlank() }
    private val headers = ServerStore.load(context).headers()

    fun request(context: Context, version: String, path: String): ImageRequest {
        val local = serverDir == null || compareVersions(version, installed) <= 0
        val builder = ImageRequest.Builder(context).data(if (local) "file:///android_asset/$path" else "$serverDir/$path")
        if (!local && headers.isNotEmpty()) builder.httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (name, value) -> set(name, value) } }.build())
        return builder.build()
    }
}

/**
 * One picture under its line: the whole width of the text, its corners rounded and a hairline round it so a light
 * screenshot does not melt into the sheet, never taller than most of the screen. A GIF plays on a loop.
 */
@Composable
private fun ChangelogPictureView(pictures: ChangelogPictures, version: String, picture: ChangelogPicture, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val request = remember(version, picture.path) { pictures.request(context, version, picture.path) }
    val loader = remember(context) {
        ImageLoader.Builder(context).components { add(if (Build.VERSION.SDK_INT >= 28) AnimatedImageDecoder.Factory() else GifDecoder.Factory()) }.build()
    }
    val shape = RoundedCornerShape(PullsRadius.xl)
    AsyncImage(
        request, contentDescription = picture.alt, imageLoader = loader,
        modifier = modifier.padding(top = 4.dp).fillMaxWidth().heightIn(max = 420.dp).clip(shape).border(1.dp, c.borderMute, shape),
        contentScale = ContentScale.Fit,
    )
}

/** The newest version's mark: one word in the accent's wash. */
@Composable
private fun NewTag() {
    val c = LocalPullsColors.current
    Text(
        "New", Modifier.clip(CircleShape).background(c.accentWash).padding(horizontal = 8.dp, vertical = 1.dp),
        style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.accentWashInk,
    )
}

/** One bullet: an accent dot centred on its first line, and the change at reading size beside it. */
@Composable
private fun ChangelogItem(markdown: String) {
    val c = LocalPullsColors.current
    val style = PullsType.read.copy(color = c.text)
    val line = with(LocalDensity.current) { style.lineHeight.toDp() }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.height(line), contentAlignment = Alignment.Center) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(c.accentTeal))
        }
        Markdown(markdown, Modifier.weight(1f), style = style)
    }
}
