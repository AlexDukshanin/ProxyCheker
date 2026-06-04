package com.example.proxycheker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ProxyDarkColorScheme = darkColorScheme(
    primary = PureWhite,
    onPrimary = InkBlack,
    secondary = PureWhite,
    onSecondary = InkBlack,
    background = MidnightBlack,
    onBackground = PureWhite,
    surface = ElevatedBlack,
    onSurface = PureWhite,
    surfaceVariant = ElevatedBlack,
    onSurfaceVariant = SoftGray,
    outline = OutlineGray
)

@Composable
fun ProxyChekerTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ProxyDarkColorScheme,
        typography = Typography,
        content = content
    )
}
