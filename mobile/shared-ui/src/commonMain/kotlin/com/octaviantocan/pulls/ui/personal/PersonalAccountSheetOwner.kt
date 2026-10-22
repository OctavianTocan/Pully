package com.octaviantocan.pulls.ui.personal

import com.octaviantocan.pulls.data.PersonalAccountRuntime

/** The bound dismissal retires sign-in before a host can defer the sheet's disposal. */
internal class PersonalAccountSheetOwner(
    val signIn: PersonalAccountRuntime.SignIn?,
    private val onClose: () -> Unit,
) {
    private var closed = false

    fun dismiss() {
        if (closed) return
        close()
        onClose()
    }

    fun close() {
        if (closed) return
        closed = true
        signIn?.close()
    }
}
