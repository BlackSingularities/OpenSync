package com.blacksingularities.opensync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blacksingularities.opensync.ui.theme.Palette

/** Ekran z paskiem tytułu, przewijaną treścią i opcjonalnym przyciskiem na dole. */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable () -> Unit = {},
    bottom: (@Composable () -> Unit)? = null,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Palette.Ink).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButtonRound(Icons.AutoMirrored.Rounded.ArrowBack, "Wstecz", onBack)
            } else {
                Spacer(Modifier.width(10.dp))
            }
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 6.dp))
            actions()
        }
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .imePadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (scrollable) Spacer(Modifier.height(2.dp))
            content()
            if (scrollable) Spacer(Modifier.height(24.dp))
        }
        if (bottom != null) {
            Box(Modifier.fillMaxWidth().background(Palette.Ink).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) { bottom() }
        }
    }
}

@Composable
fun IconButtonRound(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = Color.White) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = tint, modifier = Modifier.size(22.dp)) }
}

/** Nagłówek sekcji jak w OpenPDF: ikona, pogrubiony tytuł i cienka linia do krawędzi. */
@Composable
fun SectionHeader(title: String, icon: ImageVector? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.12f)))
    }
}

/** Sekcja: nagłówek i zaokrąglony panel. */
@Composable
fun Section(title: String? = null, footer: String? = null, icon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    Column {
        if (title != null) SectionHeader(title, icon)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.PanelHigh).padding(vertical = 4.dp), content = content)
        if (footer != null) {
            Text(footer, color = Palette.TextFaint, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp))
        }
    }
}

@Composable
fun RowIcon(icon: ImageVector, tint: Color = Color.White) {
    Box(Modifier.size(36.dp).clip(CircleShape).background(Palette.PanelHigher), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp))
    }
}

@Composable
fun ActionRow(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            RowIcon(icon, if (danger) Palette.Record else Color.White)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = when { !enabled -> Palette.TextFaint; danger -> Palette.Record; else -> Color.White },
            )
            if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 11.sp, color = Palette.TextDim, lineHeight = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun ToggleRow(icon: ImageVector?, title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ActionRow(icon, title, subtitle, enabled = enabled, trailing = {
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.Ink, checkedTrackColor = Color.White,
                uncheckedThumbColor = Palette.TextDim, uncheckedTrackColor = Palette.PanelHigher, uncheckedBorderColor = Palette.Hairline,
            ),
        )
    }) { if (enabled) onChange(!checked) }
}

/** Liczba z przyciskami −/+. */
@Composable
fun StepperRow(title: String, subtitle: String? = null, value: Int, range: IntRange, step: Int = 1, format: (Int) -> String = { it.toString() }, onChange: (Int) -> Unit) {
    ActionRow(null, title, subtitle, trailing = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallRound(Icons.Rounded.Remove, value > range.first) { onChange((value - step).coerceIn(range)) }
            Text(format(value), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            SmallRound(Icons.Rounded.Add, value < range.last) { onChange((value + step).coerceIn(range)) }
        }
    })
}

@Composable
private fun SmallRound(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(Palette.PanelHigher).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (enabled) Color.White else Palette.TextFaint, modifier = Modifier.size(16.dp)) }
}

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minLines: Int = 1,
    supporting: String? = null,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = if (placeholder.isNotEmpty()) ({ Text(placeholder, color = Palette.TextFaint) }) else null,
        singleLine = singleLine,
        minLines = minLines,
        supportingText = supporting?.let { { Text(it, fontSize = 11.sp) } },
        visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
        trailingIcon = if (password) ({
            Icon(
                if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Pokaż",
                tint = Palette.TextDim, modifier = Modifier.clip(CircleShape).clickable { visible = !visible }.padding(8.dp),
            )
        }) else null,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.White, unfocusedBorderColor = Palette.Hairline,
            focusedLabelColor = Color.White, unfocusedLabelColor = Palette.TextDim, cursorColor = Color.White,
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
        ),
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** Wybór jednej z opcji jako rząd „pigułek”. */
@Composable
fun <T> ChoiceChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { o ->
            val on = o == selected
            Text(
                label(o),
                color = if (on) Palette.Ink else Color.White,
                fontSize = 13.sp,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else Palette.PanelHigher)
                    .clickable { onSelect(o) }.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(if (enabled) Color.White else Palette.PanelHigher)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) Palette.Ink else Palette.TextFaint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = if (enabled) Palette.Ink else Palette.TextFaint, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(50)).border(1.dp, if (danger) Palette.Record else Palette.Hairline, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (danger) Palette.Record else Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = if (danger) Palette.Record else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable
fun Pill(text: String, color: Color = Palette.PanelHigher, textColor: Color = Color.White) {
    Text(text, color = textColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
fun Hint(text: String, color: Color = Palette.TextDim) {
    Text(text, color = color, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.PanelHigh).padding(14.dp))
}

@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().padding(start = 60.dp).height(1.dp).background(Palette.Hairline))
}

val ContentPadding = PaddingValues(horizontal = 16.dp)

/** Główna akcja ekranu: duży biały blok z czarną ikoną (jak „Otwórz dokument” w OpenPDF). */
@Composable
fun BigAction(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(if (enabled) Color.White else Palette.PanelHigh)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (enabled) Palette.Ink else Palette.TextFaint
        Icon(icon, null, tint = ink, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = ink.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Kafelek akcji: ikona nad podpisem na ciemnym tle. */
@Composable
fun HomeAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, badge: String? = null, onClick: () -> Unit) {
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.PanelHigh).clickable(onClick = onClick).padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        if (badge != null) {
            Text(
                badge, color = Palette.Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(50)).background(Color.White).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

class Tile(val icon: ImageVector, val label: String, val value: Boolean, val onChange: (Boolean) -> Unit)

/** Siatka kafelków-przełączników: włączony = biały blok z czarną ikoną (jak w OpenPDF). */
@Composable
fun TileGrid(tiles: List<Tile>, columns: Int = 4) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { t ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                            .background(if (t.value) Color.White else Color.White.copy(alpha = 0.06f))
                            .clickable { t.onChange(!t.value) }
                            .padding(vertical = 10.dp, horizontal = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(t.icon, null, tint = if (t.value) Palette.Ink else Palette.TextDim, modifier = Modifier.size(20.dp))
                        Text(
                            t.label, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 2,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = if (t.value) Palette.Ink else Palette.TextDim,
                            fontWeight = if (t.value) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Znak marki: ikona aplikacji na czarnym, zaokrąglonym kwadracie. */
@Composable
fun BrandMark(size: androidx.compose.ui.unit.Dp = 58.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 3.6f)).background(Palette.PanelHigh),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(com.blacksingularities.opensync.R.drawable.ic_launcher_foreground),
            null, modifier = Modifier.size(size * 1.5f),
        )
    }
}

/** Stopka „o aplikacji”: autor i wsparcie – jednolita w całej serii Open*. */
@Composable
fun About(version: String) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("OpenSync $version", fontSize = 13.sp, color = Color.White)
        Text("Licencja MIT · bez reklam i śledzenia", fontSize = 11.sp, color = Palette.TextDim)
        Text(
            "Pomysł i wykonanie: Adam Rędzikowski · redzikowski.com",
            fontSize = 12.sp, color = Palette.TextDim,
            modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp))
                .clickable { runCatching { uri.openUri("https://redzikowski.com") } }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Text(
            "☕  Postaw kawę", fontSize = 13.sp, color = Palette.Ink, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(50)).background(Color.White)
                .clickable { runCatching { uri.openUri("https://buycoffee.to/adamredzikowski") } }
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}
