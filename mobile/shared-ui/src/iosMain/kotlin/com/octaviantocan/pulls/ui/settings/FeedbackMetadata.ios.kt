package com.octaviantocan.pulls.ui.settings

import platform.Foundation.NSBundle
import platform.UIKit.UIDevice

/** Actual native app/OS metadata, deliberately excluding the device's assigned name and identifiers. */
fun nativeFeedbackMetadata(): FeedbackMetadata {
    val bundle = NSBundle.mainBundle
    val device = UIDevice.currentDevice
    return FeedbackMetadata(
        version = bundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "",
        versionCode = (bundle.objectForInfoDictionaryKey("CFBundleVersion") as? String)?.toIntOrNull() ?: 0,
        manufacturer = "Apple",
        model = device.model,
        osLabel = "${device.systemName} ${device.systemVersion}",
        ios = device.systemVersion,
    )
}
