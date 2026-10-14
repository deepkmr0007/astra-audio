// COMPILE-CHECK STUBS ONLY — never shipped.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.material3

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

@RequiresOptIn(level = RequiresOptIn.Level.WARNING)
annotation class ExperimentalMaterial3Api

class ColorScheme(
    val primary: Color, val onPrimary: Color, val primaryContainer: Color, val onPrimaryContainer: Color,
    val secondary: Color, val onSecondary: Color, val secondaryContainer: Color, val onSecondaryContainer: Color,
    val tertiary: Color, val onTertiary: Color, val tertiaryContainer: Color, val onTertiaryContainer: Color,
    val background: Color, val onBackground: Color, val surface: Color, val onSurface: Color,
    val surfaceVariant: Color, val onSurfaceVariant: Color, val error: Color, val onError: Color,
    val errorContainer: Color, val onErrorContainer: Color, val outline: Color,
)
private val U = Color.Unspecified
fun lightColorScheme(
    primary: Color = U, onPrimary: Color = U, primaryContainer: Color = U, onPrimaryContainer: Color = U,
    inversePrimary: Color = U, secondary: Color = U, onSecondary: Color = U, secondaryContainer: Color = U, onSecondaryContainer: Color = U,
    tertiary: Color = U, onTertiary: Color = U, tertiaryContainer: Color = U, onTertiaryContainer: Color = U,
    background: Color = U, onBackground: Color = U, surface: Color = U, onSurface: Color = U,
    surfaceVariant: Color = U, onSurfaceVariant: Color = U, surfaceTint: Color = U, inverseSurface: Color = U, inverseOnSurface: Color = U,
    error: Color = U, onError: Color = U, errorContainer: Color = U, onErrorContainer: Color = U, outline: Color = U,
): ColorScheme = TODO()
fun darkColorScheme(
    primary: Color = U, onPrimary: Color = U, primaryContainer: Color = U, onPrimaryContainer: Color = U,
    inversePrimary: Color = U, secondary: Color = U, onSecondary: Color = U, secondaryContainer: Color = U, onSecondaryContainer: Color = U,
    tertiary: Color = U, onTertiary: Color = U, tertiaryContainer: Color = U, onTertiaryContainer: Color = U,
    background: Color = U, onBackground: Color = U, surface: Color = U, onSurface: Color = U,
    surfaceVariant: Color = U, onSurfaceVariant: Color = U, surfaceTint: Color = U, inverseSurface: Color = U, inverseOnSurface: Color = U,
    error: Color = U, onError: Color = U, errorContainer: Color = U, onErrorContainer: Color = U, outline: Color = U,
): ColorScheme = TODO()

private val D = TextStyle()
class Typography(
    val displayLarge: TextStyle = D, val displayMedium: TextStyle = D, val displaySmall: TextStyle = D,
    val headlineLarge: TextStyle = D, val headlineMedium: TextStyle = D, val headlineSmall: TextStyle = D,
    val titleLarge: TextStyle = D, val titleMedium: TextStyle = D, val titleSmall: TextStyle = D,
    val bodyLarge: TextStyle = D, val bodyMedium: TextStyle = D, val bodySmall: TextStyle = D,
    val labelLarge: TextStyle = D, val labelMedium: TextStyle = D, val labelSmall: TextStyle = D,
)
class Shapes(val extraSmall: Shape = TODO(), val small: Shape = TODO(), val medium: Shape = TODO(), val large: Shape = TODO())

object MaterialTheme {
    val colorScheme: ColorScheme @Composable get() = TODO()
    val typography: Typography @Composable get() = TODO()
    val shapes: Shapes @Composable get() = TODO()
}
@Composable fun MaterialTheme(colorScheme: ColorScheme = TODO(), shapes: Shapes = TODO(), typography: Typography = TODO(), content: @Composable () -> Unit) {}
val LocalTextStyle: ProvidableCompositionLocal<TextStyle> get() = TODO()

@Composable fun Text(
    text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: Any? = null, fontWeight: FontWeight? = null, fontFamily: FontFamily? = null, letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null, textAlign: TextAlign? = null, lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip, softWrap: Boolean = true, maxLines: Int = Int.MAX_VALUE, minLines: Int = 1,
    style: TextStyle = TextStyle(),
) {}
@Composable fun Text(
    text: AnnotatedString, modifier: Modifier = Modifier, color: Color = Color.Unspecified, fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null, fontFamily: FontFamily? = null, overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE, style: TextStyle = TextStyle(),
) {}

class ButtonColors
object ButtonDefaults {
    @Composable fun buttonColors(containerColor: Color = Color.Unspecified, contentColor: Color = Color.Unspecified,
                                 disabledContainerColor: Color = Color.Unspecified, disabledContentColor: Color = Color.Unspecified): ButtonColors = TODO()
}
@Composable fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = TODO(),
                       colors: ButtonColors = TODO(), border: BorderStroke? = null, contentPadding: PaddingValues = TODO(),
                       content: @Composable RowScope.() -> Unit) {}
@Composable fun OutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = TODO(),
                               colors: ButtonColors = TODO(), border: BorderStroke? = null, contentPadding: PaddingValues = TODO(),
                               content: @Composable RowScope.() -> Unit) {}
@Composable fun TextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = TODO(),
                           colors: ButtonColors = TODO(), border: BorderStroke? = null, contentPadding: PaddingValues = TODO(),
                           content: @Composable RowScope.() -> Unit) {}
@Composable fun IconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {}
@Composable fun Icon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {}

@Composable fun Surface(modifier: Modifier = Modifier, shape: Shape = TODO(), color: Color = Color.Unspecified, contentColor: Color = Color.Unspecified,
                        tonalElevation: Dp = 0.dp, shadowElevation: Dp = 0.dp, border: BorderStroke? = null, content: @Composable () -> Unit) {}
@Composable fun Surface(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = TODO(),
                        color: Color = Color.Unspecified, contentColor: Color = Color.Unspecified, tonalElevation: Dp = 0.dp,
                        shadowElevation: Dp = 0.dp, border: BorderStroke? = null, content: @Composable () -> Unit) {}

class CardColors
object CardDefaults {
    @Composable fun cardColors(containerColor: Color = Color.Unspecified, contentColor: Color = Color.Unspecified): CardColors = TODO()
    @Composable fun outlinedCardBorder(enabled: Boolean = true): BorderStroke = TODO()
}
@Composable fun Card(modifier: Modifier = Modifier, shape: Shape = TODO(), colors: CardColors = TODO(), border: BorderStroke? = null,
                     content: @Composable ColumnScope.() -> Unit) {}

@Composable fun AlertDialog(
    onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit, modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null, icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null, text: (@Composable () -> Unit)? = null,
) {}

@Composable fun Scaffold(modifier: Modifier = Modifier, topBar: @Composable () -> Unit = {}, bottomBar: @Composable () -> Unit = {},
                         snackbarHost: @Composable () -> Unit = {}, floatingActionButton: @Composable () -> Unit = {},
                         content: @Composable (PaddingValues) -> Unit) {}
@ExperimentalMaterial3Api
@Composable fun TopAppBar(title: @Composable () -> Unit, modifier: Modifier = Modifier, navigationIcon: @Composable () -> Unit = {},
                          actions: @Composable RowScope.() -> Unit = {}) {}

@Composable fun OutlinedTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, readOnly: Boolean = false,
    textStyle: TextStyle = TextStyle(), label: (@Composable () -> Unit)? = null, placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null, isError: Boolean = false,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, minLines: Int = 1,
) {}
@Composable fun Switch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier,
                       thumbContent: (@Composable () -> Unit)? = null, enabled: Boolean = true) {}
@Composable fun Checkbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {}
@Composable fun Slider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                       valueRange: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0, onValueChangeFinished: (() -> Unit)? = null) {}

@Composable fun FilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier,
                           enabled: Boolean = true, leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null) {}
@Composable fun AssistChip(onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                           leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null) {}

@Composable fun Tab(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                    text: (@Composable () -> Unit)? = null, icon: (@Composable () -> Unit)? = null) {}
@Composable fun TabRow(selectedTabIndex: Int, modifier: Modifier = Modifier, containerColor: Color = Color.Unspecified,
                       contentColor: Color = Color.Unspecified, tabs: @Composable () -> Unit) {}

@Composable fun DropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {}
@Composable fun DropdownMenuItem(text: @Composable () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier,
                                 leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null, enabled: Boolean = true) {}

@Composable fun LinearProgressIndicator(progress: () -> Float, modifier: Modifier = Modifier, color: Color = Color.Unspecified, trackColor: Color = Color.Unspecified) {}
@Composable fun LinearProgressIndicator(modifier: Modifier = Modifier, color: Color = Color.Unspecified, trackColor: Color = Color.Unspecified) {}
@Composable fun CircularProgressIndicator(modifier: Modifier = Modifier, color: Color = Color.Unspecified) {}
@Composable fun HorizontalDivider(modifier: Modifier = Modifier, thickness: Dp = 1.dp, color: Color = Color.Unspecified) {}
