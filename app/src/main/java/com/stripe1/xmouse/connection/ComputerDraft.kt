package com.stripe1.xmouse.connection

/** Non-secret form validation shared by Save, Test and the repository. Never normalizes an account. */
object ComputerDraft {
    @JvmStatic fun validUsername(user: String): Boolean = user.isNotBlank() && user == user.trim() &&
        user.none { it.isISOControl() }

    @JvmStatic fun requireUsername(user: String) {
        require(validUsername(user)) { "Enter a username without leading or trailing whitespace" }
    }
}
