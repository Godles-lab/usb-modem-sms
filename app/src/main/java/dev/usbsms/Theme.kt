package dev.usbsms

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 航电仪表盘配色：深靛蓝面板 + 琥珀强调
val Ink = Color(0xFF0B1020)        // 背景
val Panel = Color(0xFF141B2E)      // 卡片
val PanelHi = Color(0xFF1B2438)    // 输入框 / 悬浮
val Hairline = Color(0xFF2A3454)   // 分隔线、描边
val Amber = Color(0xFFFFB020)      // 主强调
val Signal = Color(0xFF3DDC97)     // 在线 / 信号
val Alert = Color(0xFFFF6B5A)      // 告警
val TextHi = Color(0xFFE8ECF6)     // 正文
val TextLo = Color(0xFF8494B8)     // 次要信息

/** 遥测读数用等宽，宽字距，模仿设备状态行 */
val Readout = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    letterSpacing = 0.6.sp,
    fontWeight = FontWeight.Medium,
)

val Mono = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    letterSpacing = 0.3.sp,
)

private val scheme = darkColorScheme(
    primary = Amber,
    onPrimary = Ink,
    secondary = Signal,
    background = Ink,
    onBackground = TextHi,
    surface = Panel,
    onSurface = TextHi,
    surfaceVariant = PanelHi,
    onSurfaceVariant = TextLo,
    outline = Hairline,
    error = Alert,
)

private val typography = Typography(
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
)

@Composable
fun DjiSmsTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
