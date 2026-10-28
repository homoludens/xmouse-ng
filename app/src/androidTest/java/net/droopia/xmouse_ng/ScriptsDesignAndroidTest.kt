package net.droopia.xmouse_ng

import android.graphics.Rect
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Native script-manager sizing and text-field checks; synthetic labels/commands never reach SSH. */
@RunWith(AndroidJUnit4::class)
class ScriptsDesignAndroidTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun managerRowsAndRawCommandFormFit320dpAtLargeFontInBothThemes() {
        for (dark in listOf(false, true)) {
            ActivityScenario.launch<MainInsetsFixtureActivity>(
                MainInsetsFixtureActivity.configuredIntent(context, 1.5f, dark, 320),
            ).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContentView(R.layout.activity_script_manager)
                    val manager = activity.findViewById<ViewGroup>(R.id.list_edit_root)
                    SystemWindowInsets.applyTo(activity.window, manager)
                    measure(manager, 320, 720)
                    val density = manager.resources.displayMetrics.density
                    val screenWidth = 320 * density

                    val back = manager.findViewById<View>(R.id.scripts_manager_back)
                    assertEquals("Back", back.contentDescription.toString())
                    assertEquals("Back", back.tooltipText.toString())
                    assertTrue("script-manager Back retains a 48dp target",
                        back.width >= 48 * density - 1 && back.height >= 48 * density - 1)
                    val add = manager.findViewById<MaterialButton>(R.id.scripts_manager_add)
                    assertEquals("Add script", add.text.toString())
                    assertNotNull("manager Add has a visible icon", add.icon)
                    assertTrue("manager Add remains a 48dp target", add.height >= 48 * density - 1)

                    val fullName = "Synthetic manager script name that wraps without losing its accessible text"
                    val item = HostItem().apply {
                        setAlias(fullName)
                        setIP("not-rendered-command-metadata")
                        setUsername("914")
                    }
                    val edits = mutableListOf<Int>()
                    val listener = object : MyInterface {
                        override fun performCallback() = Unit
                        override fun performCallback2(i: Int?) { edits += requireNotNull(i) }
                    }
                    val list = manager.findViewById<ListView>(R.id.myListView)
                    list.adapter = HostListItemAdapter(
                        activity, listOf(item), listener, ListEditActivity.ActivityType.type_script,
                    )
                    measure(manager, 320, 720)
                    val row = list.getChildAt(0) as ViewGroup
                    val rowBounds = Rect()
                    assertTrue("script row is visible", row.getGlobalVisibleRect(rowBounds))
                    assertTrue("script row remains inside the narrow viewport",
                        rowBounds.left >= 0 && rowBounds.right <= screenWidth)
                    assertEquals(activity.getString(R.string.ng_script_named, fullName),
                        row.findViewById<TextView>(R.id.hostAlias).contentDescription.toString())
                    assertEquals(activity.getString(R.string.ng_script_ssh_label),
                        row.findViewById<TextView>(R.id.hostDesc).text.toString())
                    assertFalse("script command text is not rendered as row metadata",
                        row.findViewById<TextView>(R.id.hostDesc).text.contains("not-rendered-command-metadata"))
                    val rowEdit = row.findViewById<MaterialButton>(R.id.script_manager_edit)
                    assertEquals("Edit", rowEdit.text.toString())
                    assertNotNull("row Edit has a visible icon", rowEdit.icon)
                    assertEquals(activity.getString(R.string.ng_edit_named_script, fullName),
                        rowEdit.contentDescription.toString())
                    assertTrue("row Edit remains a 48dp target",
                        rowEdit.width >= 48 * density - 1 && rowEdit.height >= 48 * density - 1)
                    rowEdit.performClick()
                    assertEquals(listOf(0), edits)

                    val form = activity.layoutInflater.inflate(R.layout.manage_script_item, null)
                    activity.setContentView(form)
                    measure(form, 296, 640)
                    assertNotNull(form.findViewById<TextInputLayout>(R.id.new_script_alias_layout))
                    assertNotNull(form.findViewById<TextInputLayout>(R.id.new_script_command_layout))
                    val alias = form.findViewById<EditText>(R.id.new_script_alias)
                    val command = form.findViewById<EditText>(R.id.new_script_command)
                    val rawCommand = "printf 'synthetic λ'\n  exact spacing  \n"
                    alias.setText("Synthetic form")
                    command.setText(rawCommand)
                    assertEquals("form editing preserves Unicode, newlines, and trailing whitespace",
                        rawCommand, command.text.toString())
                    assertEquals(TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP, 13f, form.resources.displayMetrics,
                    ), command.textSize, 0.5f)
                    assertEquals(Typeface.MONOSPACE, command.typeface)
                    assertFalse("raw command text is never saved in view state/Bundle", command.isSaveEnabled)
                    assertTrue("SSH command editor is multiline",
                        command.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
                    assertTrue("script name target is at least 48dp high", alias.height >= 48 * density - 1)
                    assertTrue("command editor remains inside the dialog width", command.width <= 296 * density)
                }
            }
        }
    }

    private fun measure(root: View, widthDp: Int, heightDp: Int) {
        val density = root.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val height = (heightDp * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }
}
