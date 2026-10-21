package com.octaviantocan.pulls.ui.design

import android.os.Build

actual val BlurSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
