package io.github.nainiglesias.nb.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Colors for code, outputs and the 16 ANSI terminal colors. */
@Immutable
data class CodeColors(
    val keyword: Color,
    val builtin: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val decorator: Color,
    val function: Color,
    val magic: Color,
    val codeBackground: Color,
    val stderrBackground: Color,
    val ansi: List<Color>,
)

private val LightCode = CodeColors(
    keyword = Color(0xFF8B2BB9),
    builtin = Color(0xFF00708A),
    string = Color(0xFF9E4014),
    comment = Color(0xFF6E7781),
    number = Color(0xFF0B6E2F),
    decorator = Color(0xFF8A5A00),
    function = Color(0xFF0550AE),
    magic = Color(0xFF8A5A00),
    codeBackground = Color(0xFFF3F4F6),
    stderrBackground = Color(0xFFFDECEC),
    ansi = listOf(
        0xFF2E3436, 0xFFC01C28, 0xFF26A269, 0xFFA2734C, 0xFF12488B, 0xFFA347BA, 0xFF2AA1B3, 0xFF7D8085,
        0xFF5E5C64, 0xFFE01B24, 0xFF2EC27E, 0xFFC88800, 0xFF2A7BDE, 0xFFC061CB, 0xFF33C7DE, 0xFF3D3D3D,
    ).map { Color(it) },
)

private val DarkCode = CodeColors(
    keyword = Color(0xFFD2A8FF),
    builtin = Color(0xFF79C0FF),
    string = Color(0xFFFFA67A),
    comment = Color(0xFF8B949E),
    number = Color(0xFF7EE787),
    decorator = Color(0xFFE3B341),
    function = Color(0xFF8CC8FF),
    magic = Color(0xFFE3B341),
    codeBackground = Color(0xFF1B1D21),
    stderrBackground = Color(0xFF3A1F1F),
    ansi = listOf(
        0xFF6E7681, 0xFFFF7B72, 0xFF7EE787, 0xFFE3B341, 0xFF79C0FF, 0xFFD2A8FF, 0xFF56D4DD, 0xFFC9D1D9,
        0xFF8B949E, 0xFFFFA198, 0xFFA5F0B0, 0xFFF2CC60, 0xFFA5D6FF, 0xFFE2C5FF, 0xFFB3F0FF, 0xFFF0F6FC,
    ).map { Color(it) },
)

val LocalCodeColors = staticCompositionLocalOf { LightCode }

@Composable
fun NbTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalCodeColors provides if (dark) DarkCode else LightCode) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
