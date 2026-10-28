package net.droopia.xmouse_ng

import android.content.Context
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.appcompat.widget.AppCompatEditText

/** Multiline composer that asks IMEs to expose Send instead of replacing it with a Return key. */
class SendImeEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatEditText(context, attrs) {
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs)
        if (connection != null &&
            (outAttrs.imeOptions and EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEND
        ) {
            outAttrs.imeOptions = outAttrs.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION.inv()
        }
        return connection
    }
}
