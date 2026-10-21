package app.nagatani.dev.attendance.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val UniversityLightColors = lightColorScheme(
    primary = Color(0xFF1E3A8A),          // 濃紺（アカデミックネイビー）
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = Color(0xFF0F766E),        // ティールグリーン
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF134E4A),
    tertiary = Color(0xFFB45309),         // アンバー（注意・重複表示等）
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    error = Color(0xFFB91C1C),
    onError = Color.White
)

@Composable
fun UniversityAttendanceTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = UniversityLightColors,
        content = content
    )
}
