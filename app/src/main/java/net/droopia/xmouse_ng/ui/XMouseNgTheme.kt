package net.droopia.xmouse_ng.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import net.droopia.xmouse_ng.R

/** Shared resource-backed theme for the existing Compose layout editor and manager. */
@Composable
fun XMouseNgTheme(content: @Composable () -> Unit) {
    val background = colorResource(R.color.ng_background)
    val surface = colorResource(R.color.ng_surface)
    val softSurface = colorResource(R.color.ng_surface_soft)
    val primaryText = colorResource(R.color.ng_text_primary)
    val secondaryText = colorResource(R.color.ng_text_secondary)
    val border = colorResource(R.color.ng_border)
    val accent = colorResource(R.color.ng_accent)
    val onAccent = colorResource(R.color.ng_on_accent)
    val softAccent = colorResource(R.color.ng_accent_soft)
    val danger = colorResource(R.color.ng_danger)
    val softDanger = colorResource(R.color.ng_danger_soft)
    val blueSurface = colorResource(R.color.ng_action_blue_soft)
    val blueText = colorResource(R.color.ng_on_action_blue)

    val colorScheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = softAccent,
            onPrimaryContainer = primaryText,
            secondary = accent,
            onSecondary = onAccent,
            secondaryContainer = softSurface,
            onSecondaryContainer = primaryText,
            tertiary = blueText,
            onTertiary = blueSurface,
            tertiaryContainer = blueSurface,
            onTertiaryContainer = blueText,
            error = danger,
            onError = surface,
            errorContainer = softDanger,
            onErrorContainer = danger,
            background = background,
            onBackground = primaryText,
            surface = surface,
            onSurface = primaryText,
            surfaceVariant = softSurface,
            onSurfaceVariant = secondaryText,
            outline = border,
            outlineVariant = border,
            inverseSurface = primaryText,
            inverseOnSurface = background,
            inversePrimary = accent,
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = softAccent,
            onPrimaryContainer = primaryText,
            secondary = accent,
            onSecondary = onAccent,
            secondaryContainer = softSurface,
            onSecondaryContainer = primaryText,
            tertiary = blueText,
            onTertiary = blueSurface,
            tertiaryContainer = blueSurface,
            onTertiaryContainer = blueText,
            error = danger,
            onError = surface,
            errorContainer = softDanger,
            onErrorContainer = danger,
            background = background,
            onBackground = primaryText,
            surface = surface,
            onSurface = primaryText,
            surfaceVariant = softSurface,
            onSurfaceVariant = secondaryText,
            outline = border,
            outlineVariant = border,
            inverseSurface = primaryText,
            inverseOnSurface = background,
            inversePrimary = accent,
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        // Material's default typography uses FontFamily.Default (the platform font).
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}
