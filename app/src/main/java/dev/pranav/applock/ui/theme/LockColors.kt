package dev.pranav.applock.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.ui.graphics.toArgb

/**
 * The lock screens' background color, resolved without composition (same scheme [AppLockTheme]
 * picks). Used for window backgrounds, so the gap before Compose draws is the app's color
 * instead of the system default grey.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun lockBackgroundColor(context: Context): Int {
    val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> expressiveLightColorScheme()
    }
    return scheme.surfaceContainer.toArgb()
}
