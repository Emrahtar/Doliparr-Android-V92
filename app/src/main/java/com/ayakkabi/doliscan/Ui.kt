package com.ayakkabi.doliscan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Blue = Color(0xFF1E88E5)
val Cyan = Color(0xFF00ACC1)
val PageBg = Color(0xFFF4F7FB)
val Green = Color(0xFF2E9E5B)
val Red = Color(0xFFE5484D)

sealed class Screen {
    object Home : Screen()
    object Scan : Screen()
    object Products : Screen()
    object Cart : Screen()
    object Settings : Screen()
    class New(val barcode: String) : Screen()
    class Detail(val p: Product) : Screen()
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Blue, onPrimary = Color.White, secondary = Cyan,
            background = PageBg, surface = Color.White,
            surfaceVariant = Color(0xFFE8EEF6), primaryContainer = Color(0xFFD6E9FB)
        )
    ) { Surface(color = PageBg, modifier = Modifier.fillMaxSize()) { content() } }
}

@Composable
fun AppBar(title: String, onBack: (() -> Unit)?, onMenu: (Screen) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val white = ButtonDefaults.textButtonColors(contentColor = Color.White)
    Row(
        Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Blue, Cyan))).height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) TextButton(onBack, colors = white) { Text("←", fontSize = 24.sp) }
        else Spacer(Modifier.width(16.dp))
        Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Box {
            TextButton({ open = true }, colors = white) { Text("☰", fontSize = 24.sp) }
            DropdownMenu(open, { open = false }) {
                listOf(
                    "🏠  Ana sayfa" to Screen.Home,
                    "📷  Barkod tara" to Screen.Scan,
                    "🧾  Satış sepeti" to Screen.Cart,
                    "🏷️  Ürünler" to Screen.Products,
                    "➕  Ürün ekle" to Screen.New(""),
                    "⚙️  Ayarlar" to Screen.Settings
                ).forEach { (label, target) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onMenu(target) })
                }
            }
        }
    }
}

@Composable
fun Tile(icon: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier.height(120.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, fontSize = 36.sp)
            Spacer(Modifier.height(6.dp))
            Text(label, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp)
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}

@Composable
fun StatusCard(text: String, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    val bad = text.startsWith("✖") || text.startsWith("Hata") || text.startsWith("⚠")
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = if (bad) Color(0xFFFDECEC) else Color(0xFFE3F2FD))
    ) { Text(text, Modifier.padding(12.dp), color = if (bad) Color(0xFF9B1C1C) else Color(0xFF0D3B66)) }
}

@Composable
fun BigButton(text: String, modifier: Modifier = Modifier, color: Color = Blue, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick, modifier.height(52.dp), enabled = enabled, shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color)
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Blue else Color(0xFFE3ECF7))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) { Text(text, color = if (selected) Color.White else Color(0xFF1B3A5C)) }
}

@Composable
fun Field(
    value: String, onChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier.fillMaxWidth(), singleLine: Boolean = true, numeric: Boolean = false
) {
    OutlinedTextField(
        value, onChange, modifier, label = { Text(label) }, singleLine = singleLine,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text)
    )
}

@Composable
fun PickerField(label: String, value: String, options: List<Pair<String, String>>, modifier: Modifier = Modifier.fillMaxWidth(), onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value, {}, Modifier.fillMaxWidth(), readOnly = true, label = { Text(label) },
            trailingIcon = { Text("▾", fontSize = 18.sp) }, shape = RoundedCornerShape(14.dp)
        )
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(open, { open = false }) {
            if (options.isEmpty()) DropdownMenuItem(text = { Text("(liste boş)") }, onClick = { open = false })
            options.forEach { (id, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(id) })
            }
        }
    }
}
