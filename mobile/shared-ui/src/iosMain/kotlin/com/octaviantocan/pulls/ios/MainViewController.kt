package com.octaviantocan.pulls.ios

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.ComposeUIViewController
import com.octaviantocan.pulls.data.sourceStorageNamespace
import com.octaviantocan.pulls.preview.PREVIEW_SERVER
import com.octaviantocan.pulls.preview.PullyPreview
import com.octaviantocan.pulls.preview.previewServices
import com.octaviantocan.pulls.settings.SettingsEffects
import com.octaviantocan.pulls.settings.createIosSettings
import com.octaviantocan.pulls.ui.home.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Native clipboard/links/lifecycle/back are mandatory; Compose owns the actual screens and shared stack. */
fun MainViewController(homeActions: NativeHomeActions): platform.UIKit.UIViewController {
    val limitation = mutableStateOf<HomePreviewFeature?>(null)
    val settings = createIosSettings(object : SettingsEffects {
        // This labelled fixture host has no production account/settings-sync or analytics connection.
        override fun touched(name: String) = Unit
        override fun settingsChanged(key: String, value: String) = Unit
        override fun usageStatsEnabled(enabled: Boolean) = Unit
    })
    val namespace = requireNotNull(sourceStorageNamespace(PREVIEW_SERVER, null, false))
    val storage = UserDefaultsHomeStore(namespace, { true })
    val pages = object : RepoPageStorage {
        private fun key(owner: String, name: String) = "repo-pulls.${"$owner/$name".lowercase()}"
        override suspend fun read(owner: String, name: String): JsonObject? = storage.get(key(owner, name))?.let { raw ->
            runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        }
        override suspend fun write(owner: String, name: String, raw: JsonObject) = storage.set(key(owner, name), raw.toString())
    }
    val services = previewServices(homeActions, settings, storage, pages) { limitation.value = it }
    return ComposeUIViewController {
        CompositionLocalProvider(LocalNativeHomeActions provides homeActions) {
            PullyPreview(services, limitation.value) { limitation.value = null }
        }
    }
}
