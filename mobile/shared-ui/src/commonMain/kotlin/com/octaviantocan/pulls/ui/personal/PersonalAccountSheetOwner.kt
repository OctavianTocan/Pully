package com.octaviantocan.pulls.ui.personal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.data.PersonalAccountRuntime

/** The bound dismissal retires sign-in before a host can defer the sheet's disposal. */
internal class PersonalAccountSheetOwner(
    signIn: PersonalAccountRuntime.SignIn?,
    private val onClose: () -> Unit,
) {
    private var closed = false
    var signIn by mutableStateOf(signIn)
        private set

    fun dismiss() {
        if (closed) return
        close()
        onClose()
    }

    fun dismissThen(action: () -> Unit) {
        if (closed) return
        dismiss()
        action()
    }

    fun replaceSignIn(open: () -> PersonalAccountRuntime.SignIn?): PersonalAccountRuntime.SignIn? {
        if (closed) return null
        signIn?.close()
        return open().also { signIn = it }
    }

    fun close() {
        if (closed) return
        closed = true
        signIn?.close()
    }
}
