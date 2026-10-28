package net.droopia.xmouse_ng.layout

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag

/** Typography used only by the compact editor controls; the app and user font scale are untouched. */
internal object LayoutEditorTypography {
    val control: TextStyle
        @Composable get() = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val body: TextStyle
        @Composable get() = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
    val metadata: TextStyle
        @Composable get() = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp)
    val heading: TextStyle
        @Composable get() = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium)
}

@Composable
internal fun LayoutEditorPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String? = null,
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).editorTestTag(testTag),
        shape = RoundedCornerShape(11.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        CompactControlContent(content)
    }
}

@Composable
internal fun LayoutEditorOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String? = null,
    content: @Composable () -> Unit,
) {
    val colors = ButtonDefaults.outlinedButtonColors(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        disabledContainerColor = MaterialTheme.colorScheme.surface,
        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).editorTestTag(testTag),
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = colors,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        CompactControlContent(content)
    }
}

@Composable
internal fun LayoutEditorAccentOutlineButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String? = null,
    content: @Composable () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).editorTestTag(testTag),
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContainerColor = MaterialTheme.colorScheme.surface,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        CompactControlContent(content)
    }
}

@Composable
internal fun LayoutEditorIconButton(
    icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    LayoutEditorOutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        testTag = testTag,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
internal fun LayoutEditorTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    val semanticsModifier = Modifier.semantics {
        role = Role.Tab
        this.selected = selected
    }
    val content: @Composable () -> Unit = { Text(label, maxLines = 2) }
    if (selected) {
        LayoutEditorPrimaryButton(
            onClick = onClick,
            modifier = modifier.then(semanticsModifier),
            testTag = testTag,
            content = content,
        )
    } else {
        LayoutEditorOutlinedButton(
            onClick = onClick,
            modifier = modifier.then(semanticsModifier),
            testTag = testTag,
            content = content,
        )
    }
}

@Composable
private fun CompactControlContent(content: @Composable () -> Unit) {
    CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides LayoutEditorTypography.control) {
        content()
    }
}

private fun Modifier.editorTestTag(tag: String?): Modifier =
    if (tag == null) this else testTag(tag)
