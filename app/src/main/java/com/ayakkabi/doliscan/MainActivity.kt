package com.ayakkabi.doliscan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("doliscan", Context.MODE_PRIVATE)
    private val defs = mapOf(
        "baseUrl" to "http://192.168.1.20/dolibarr", "apiKey" to "",
        "wh" to "1", "whName" to "", "pc" to "192.168.1.20", "token" to "",
        "vat" to "10", "cat" to "", "bcSeq" to "0", "bcType" to "auto",
        "bcPrefix" to "", "bcLen" to ""
    )
    fun get(k: String): String = sp.getString(k, defs[k] ?: "") ?: ""
    fun set(k: String, v: String) = sp.edit().putString(k, v).apply()
}

class CartLine(val p: Product, q: Int) {
    var qty by mutableStateOf(q)
}

/** Ürün oluşturma ve güncelleme ekranlarının ortak form durumu. */
class ProductForm(prefs: Prefs, barcode0: String = "", isNew: Boolean = true) {
    var barcode by mutableStateOf(barcode0)
    var ref by mutableStateOf(barcode0)
    var refTouched by mutableStateOf(false)
    var label by mutableStateOf("")
    var desc by mutableStateOf("")
    var price by mutableStateOf("")
    var vat by mutableStateOf(prefs.get("vat"))
    var useDefVat by mutableStateOf(isNew)
    var wh by mutableStateOf(prefs.get("wh"))
    var useDefWh by mutableStateOf(isNew)
    var catId by mutableStateOf(if (isNew) prefs.get("cat") else "")
    var tosell by mutableStateOf(true)
    var tobuy by mutableStateOf(true)
    var alertStock by mutableStateOf("")
    var desiredStock by mutableStateOf("")
    var supplierId by mutableStateOf("")
    var buyPrice by mutableStateOf("")
    var minQty by mutableStateOf("1")
    var autoBc by mutableStateOf(isNew)

    fun changeBarcode(v: String) { barcode = v; if (!refTouched) ref = v }

    val isNewForm = isNew
    var origBarcode by mutableStateOf("")
    /** Yalnızca güncelleme ekranında ve barkod elle değiştirildiyse denetler; Dolibarr'ın verdiği barkoda karışmaz. */
    fun barcodeIssue(): String? {
        val b = barcode.trim()
        return if (isNewForm || b == origBarcode.trim()) null else barcodeProblem(b)
    }

    fun load(p: Product) {
        origBarcode = p.barcode
        ref = p.ref; refTouched = true
        barcode = p.barcode; label = p.label; desc = p.description
        price = p.priceTtc; vat = p.tva
        if (p.defWarehouse.isNotEmpty()) wh = p.defWarehouse
        tosell = p.tosell; tobuy = p.tobuy
        alertStock = p.alertStock; desiredStock = p.desiredStock
    }
}

class FormLists {
    var cats by mutableStateOf(listOf<Pair<String, String>>())
    var whs by mutableStateOf(listOf<Pair<String, String>>())
    var sups by mutableStateOf(listOf<Pair<String, String>>())
    var err by mutableStateOf("")
}

@Composable
fun rememberFormLists(api: DoliApi): FormLists {
    val l = remember(api) { FormLists() }
    LaunchedEffect(api) {
        try { l.cats = api.categories() } catch (e: Exception) { l.err = "Hata: Kategoriler alınamadı → ${e.message}" }
        try { l.whs = api.warehouses() } catch (_: Exception) { }
        try { l.sups = api.suppliers() } catch (_: Exception) { }
    }
    return l
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.parseColor("#1E88E5")
        setContent { AppTheme { App() } }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var cfgVer by remember { mutableStateOf(0) }
    val api = remember(cfgVer) { DoliApi(prefs.get("baseUrl"), prefs.get("apiKey")) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val stack = remember { mutableStateListOf<Screen>() }
    var refresh by remember { mutableStateOf(0) }
    val cart = remember { mutableStateListOf<CartLine>() }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) perm.launch(Manifest.permission.CAMERA) }

    fun go(s: Screen) { stack.add(screen); screen = s }
    fun back() { screen = if (stack.isEmpty()) Screen.Home else stack.removeAt(stack.lastIndex); refresh++ }
    fun jump(s: Screen) { stack.clear(); screen = s }
    BackHandler(screen != Screen.Home) { back() }

    val s = screen
    val title = when (s) {
        is Screen.Home -> "Doliparr Android"
        is Screen.Scan -> "Barkod Tara"
        is Screen.Products -> "Ürünler"
        is Screen.Cart -> "Satış Sepeti"
        is Screen.Settings -> "Ayarlar"
        is Screen.New -> "Yeni Ürün Kartı"
        is Screen.Detail -> "Ürün Kartı"
    }

    Scaffold(
        containerColor = PageBg,
        topBar = { AppBar(title, if (s is Screen.Home) null else ({ back() }), { jump(it) }) }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (s) {
                is Screen.Home -> HomeScreen { go(it) }
                is Screen.Scan -> ScanScreen(api, prefs, granted, { go(Screen.Detail(it)) }, { go(Screen.New(it)) })
                is Screen.Products -> ProductsScreen(api, refresh) { go(Screen.Detail(it)) }
                is Screen.Cart -> CartScreen(api, prefs, granted, cart)
                is Screen.Settings -> SettingsScreen(api, prefs) { cfgVer++ }
                is Screen.New -> NewProductScreen(api, prefs, granted, s.barcode) { screen = Screen.Detail(it) }
                is Screen.Detail -> DetailScreen(api, prefs, granted, s.p)
            }
        }
    }
}

@Composable
fun HomeScreen(go: (Screen) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Cyan, Blue)), RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
                .padding(horizontal = 24.dp, vertical = 28.dp)
        ) {
            Column {
                Text("Hoş geldiniz", color = Color.White.copy(alpha = 0.85f))
                Text("Mağaza Yönetimi", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Tile("🧾", "Satış Sepeti", Modifier.weight(1f)) { go(Screen.Cart) }
                Tile("📷", "Barkod Tara", Modifier.weight(1f)) { go(Screen.Scan) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Tile("🏷️", "Ürünler", Modifier.weight(1f)) { go(Screen.Products) }
                Tile("➕", "Ürün Ekle", Modifier.weight(1f)) { go(Screen.New("")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Tile("⚙️", "Ayarlar", Modifier.weight(1f)) { go(Screen.Settings) }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun ProductThumb(api: DoliApi, p: Product, size: Dp, version: Int = 0) {
    var photo by remember(p.id, version) { mutableStateOf(if (version == 0) ThumbCache.peek(p.id) else null) }
    LaunchedEffect(p.id, version) { if (photo == null) photo = ThumbCache.load(api, p) }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(Color(0xFFE8EEF6)),
        contentAlignment = Alignment.Center
    ) {
        when (val ph = photo) {
            is Photo.Img -> Image(ph.bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Photo.None -> Text("📷\nyok", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            is Photo.Error -> Text("⚠", style = MaterialTheme.typography.titleMedium)
            null -> Text("…")
        }
    }
}

@Composable
fun ScanScreen(
    api: DoliApi, prefs: Prefs, granted: Boolean,
    onOpen: (Product) -> Unit, onCreate: (String) -> Unit
) {
    var mode by remember { mutableStateOf(0) }        // 0 = kasaya gönder, 1 = ürünü bul
    var armed by remember { mutableStateOf(false) }
    var serial by remember { mutableStateOf(false) }
    var unknown by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Başlat'a basın, sonra barkodu okutun") }
    val scope = rememberCoroutineScope()
    val tone = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }

    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Kasaya gönder", mode == 0) { mode = 0 }
            Pill("Ürünü bul", mode == 1) { mode = 1 }
        }
        StatusCard(status)
        unknown?.let { u ->
            if (mode == 1) BigButton("➕ $u için yeni ürün kartı oluştur", Modifier.fillMaxWidth(), Green) { onCreate(u) }
        }
        if (granted) {
            BarcodeScanner(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp)), enabled = armed) { code ->
                if (!armed) return@BarcodeScanner
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                if (!serial) armed = false
                unknown = null
                scope.launch {
                    if (mode == 0) {
                        status = try {
                            api.sendToPc(prefs.get("pc"), prefs.get("token"), code)
                            "✔ Kasaya gönderildi: $code" + if (!serial) "\nYeni okuma için Başlat'a basın" else ""
                        } catch (e: Exception) {
                            "✖ PC'ye gönderilemedi ($code): ${e.message}"
                        }
                    } else {
                        try {
                            val p = api.byBarcode(code)
                            if (p != null) onOpen(p) else { unknown = code; status = "Bu barkodlu ürün kayıtlı değil: $code" }
                        } catch (e: Exception) {
                            status = "Hata: ${e.message}"
                        }
                    }
                }
            }
        } else {
            Text("Kamera izni gerekli. Telefon ayarlarından uygulamaya kamera izni verin.", Modifier.padding(16.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BigButton(if (armed) "■ Durdur" else "▶ Taramayı başlat", Modifier.weight(1f), if (armed) Red else Blue) {
                armed = !armed
                status = if (armed) "Taranıyor... barkodu kameraya tutun" else "Durduruldu"
            }
            Spacer(Modifier.width(12.dp))
            Text("Seri")
            Switch(serial, { serial = it }, Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
fun ProductsScreen(api: DoliApi, refreshKey: Int, onOpen: (Product) -> Unit) {
    var q by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(0) }  // 0 tümü, 1 stoktakiler, 2 stokta olmayanlar
    var list by remember { mutableStateOf(listOf<Product>()) }
    var page by remember { mutableStateOf(0) }
    var more by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun load(reset: Boolean) {
        loading = true; msg = ""
        try {
            val pg = if (reset) 0 else page + 1
            val r = api.products(q, pg)
            list = if (reset) r else list + r
            page = pg; more = r.size >= 50
            if (list.isEmpty()) msg = "Ürün bulunamadı"
        } catch (e: Exception) {
            msg = "Hata: ${e.message}"
        }
        loading = false
    }

    LaunchedEffect(q, refreshKey) { delay(400); load(true) }

    val shown = when (filter) {
        1 -> list.filter { (it.stock.toDoubleOrNull() ?: 0.0) > 0 }
        2 -> list.filter { (it.stock.toDoubleOrNull() ?: 0.0) <= 0 }
        else -> list
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(q, { q = it }, "Ara: ad, kod veya barkod")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Tümü", filter == 0) { filter = 0 }
            Pill("Stoktakiler", filter == 1) { filter = 1 }
            Pill("Stok yok", filter == 2) { filter = 2 }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        StatusCard(msg)
        if (ThumbCache.lastError.isNotEmpty())
            StatusCard("Hata: Fotoğraflar alınamadı → ${ThumbCache.lastError}")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
            items(shown, key = { it.id }) { p ->
                val inStock = (p.stock.toDoubleOrNull() ?: 0.0) > 0
                Card(
                    Modifier.fillMaxWidth().clickable { onOpen(p) },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProductThumb(api, p, 68.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.label, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text("${p.ref} • ${p.barcode.ifEmpty { "barkod yok" }}", fontSize = 12.sp, color = Color.Gray)
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("₺${p.priceTtc}", color = Blue, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(10.dp))
                                Box(
                                    Modifier.clip(RoundedCornerShape(50))
                                        .background(if (inStock) Color(0xFFE1F5EA) else Color(0xFFFDECEC))
                                        .padding(horizontal = 10.dp, vertical = 2.dp)
                                ) { Text("Stok: ${p.stock}", fontSize = 12.sp, color = if (inStock) Green else Red) }
                            }
                        }
                    }
                }
            }
            if (more) item {
                TextButton({ scope.launch { load(false) } }, Modifier.fillMaxWidth()) { Text("Daha fazla yükle") }
            }
        }
    }
}

@Composable
fun CartScreen(api: DoliApi, prefs: Prefs, granted: Boolean, lines: MutableList<CartLine>) {
    var input by remember { mutableStateOf("") }
    var camera by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tone = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }

    fun add(p: Product) {
        val l = lines.firstOrNull { it.p.id == p.id }
        if (l != null) l.qty++ else lines.add(CartLine(p, 1))
    }

    suspend fun lookup(code: String) {
        val c = code.trim()
        if (c.isEmpty()) return
        status = "Aranıyor..."
        try {
            val p = api.byBarcode(c) ?: api.products(c, 0).firstOrNull()
            if (p != null) { add(p); status = "✔ Eklendi: ${p.label}" } else status = "Ürün bulunamadı: $c"
        } catch (e: Exception) {
            status = "Hata: ${e.message}"
        }
    }

    val totalQty = lines.sumOf { it.qty }
    val total = lines.sumOf { (it.p.priceTtc.toDoubleOrNull() ?: 0.0) * it.qty }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(input, { input = it }, "Barkod yazın veya okutun", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            BigButton("Ekle", color = Blue) { val c = input; input = ""; scope.launch { lookup(c) } }
        }
        if (granted) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (camera) "📷 Kamerayı kapat" else "📷 Kamerayla okut", camera) { camera = !camera }
            }
            if (camera) BarcodeScanner(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(20.dp)), enabled = true) { code ->
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                scope.launch { lookup(code) }
            }
        }
        StatusCard(status)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(lines.toList(), key = { it.p.id }) { l ->
                val unit = l.p.priceTtc.toDoubleOrNull() ?: 0.0
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(l.p.label, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                Text("${l.p.ref} • ₺${l.p.priceTtc}", fontSize = 12.sp, color = Color.Gray)
                            }
                            TextButton({ lines.remove(l) }) { Text("✕", color = Red, fontSize = 18.sp) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton({ if (l.qty > 1) l.qty-- else lines.remove(l) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp)) { Text("−", fontSize = 20.sp) }
                            Text("${l.qty}", Modifier.padding(horizontal = 16.dp), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            OutlinedButton({ l.qty++ }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp)) { Text("+", fontSize = 20.sp) }
                            Spacer(Modifier.weight(1f))
                            Text(String.format(Locale.US, "₺%.2f", unit * l.qty), fontWeight = FontWeight.Bold, color = Blue)
                        }
                    }
                }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$totalQty adet", Modifier.weight(1f))
                Text(String.format(Locale.US, "Toplam: ₺%.2f", total), fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("Temizle", Modifier.weight(1f), Color(0xFF8A99AD), enabled = !busy && lines.isNotEmpty()) { lines.clear(); status = "" }
                BigButton("Kasaya gönder", Modifier.weight(2f), Green, enabled = !busy && lines.isNotEmpty()) {
                    scope.launch {
                        busy = true
                        var n = 0
                        val all = lines.sumOf { it.qty }
                        try {
                            for (l in lines.toList()) {
                                val code = l.p.barcode.ifEmpty { l.p.ref }
                                repeat(l.qty) {
                                    api.sendToPc(prefs.get("pc"), prefs.get("token"), code)
                                    n++; status = "Kasaya gönderiliyor $n/$all"
                                    delay(450)
                                }
                            }
                            lines.clear()
                            status = "✔ Sepet kasaya gönderildi ($all adet). Ödemeyi TakePOS'ta tamamlayın."
                        } catch (e: Exception) {
                            status = "✖ Gönderilemedi ($n/$all gitti): ${e.message}"
                        }
                        busy = false
                    }
                }
            }
        }
    }
}

/** Ürün oluşturma ve güncelleme ekranlarında ortak kullanılan alanlar. */
@Composable
fun ProductFormFields(
    f: ProductForm, api: DoliApi, prefs: Prefs, granted: Boolean, isNew: Boolean,
    lists: FormLists, onMsg: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val tone = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }
    var scanning by remember { mutableStateOf(false) }
    val defWhName = prefs.get("whName").ifEmpty { "Depo ${prefs.get("wh")}" }
    val catName = lists.cats.firstOrNull { it.first == f.catId }?.second ?: ""
    val supName = lists.sups.firstOrNull { it.first == f.supplierId }?.second ?: ""

    SectionCard {
        Text("Ürün bilgisi", fontWeight = FontWeight.Bold)
        val scannedStart = remember { f.barcode.isNotEmpty() }
        if (isNew && !scannedStart) {
            Text(
                "Barkod, ürün oluşturulunca Dolibarr tarafından otomatik verilir. Gerekirse ürün kartından değiştirebilirsiniz.",
                fontSize = 13.sp
            )
        } else {
            Field(f.barcode, { f.changeBarcode(it) }, "Barkod")
            f.barcodeIssue()?.let { Text("⚠ $it", color = Red, fontSize = 12.sp) }
            if (isNew) Text(
                "Taranan barkod kullanılır; Dolibarr kabul etmezse barkodu Dolibarr otomatik verir.",
                fontSize = 12.sp
            )
        }
        if (!isNew) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("🔢 Barkod üret", false) {
                    scope.launch {
                        try {
                            val (c, d) = api.generateBarcode(prefs)
                            f.changeBarcode(c); onMsg("Barkod üretildi: $c ($d)")
                        } catch (e: Exception) { onMsg(errMsg(e)) }
                    }
                }
                if (granted) Pill(if (scanning) "Kamerayı kapat" else "📷 Barkod oku", scanning) { scanning = !scanning }
            }
            if (scanning && granted) BarcodeScanner(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(20.dp))) { code ->
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                f.changeBarcode(code); scanning = false
            }
        }
        Field(f.ref, { f.ref = it; f.refTouched = true }, "Ürün kodu (boşsa barkod kullanılır)")
        Field(f.label, { f.label = it }, "Ürün adı (örn. adidas spor ayakkabı Beyaz)", singleLine = false)
        Field(f.desc, { f.desc = it }, "Açıklama", singleLine = false)
    }

    SectionCard {
        Text("Satış", fontWeight = FontWeight.Bold)
        Field(f.price, { f.price = it }, "Satış fiyatı (KDV dahil)", numeric = true)
        if (isNew) {
            Row(Modifier.fillMaxWidth().clickable { f.useDefVat = !f.useDefVat }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(f.useDefVat, { f.useDefVat = it })
                Text("Varsayılan KDV oranı (%${prefs.get("vat")})")
            }
            if (!f.useDefVat) Field(f.vat, { f.vat = it }, "KDV oranı %", numeric = true)
        } else {
            Field(f.vat, { f.vat = it }, "KDV oranı %", numeric = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Satışta", Modifier.weight(1f)); Switch(f.tosell, { f.tosell = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Alışta", Modifier.weight(1f)); Switch(f.tobuy, { f.tobuy = it })
        }
    }

    SectionCard {
        Text("Alış fiyatı (tedarikçi)", fontWeight = FontWeight.Bold)
        PickerField("Tedarikçi", supName, lists.sups) { f.supplierId = it }
        Field(f.buyPrice, { f.buyPrice = it }, "En düşük miktar için alış fiyatı (KDV hariç)", numeric = true)
        Field(f.minQty, { f.minQty = it }, "En düşük miktar", numeric = true)
        Text("Alış fiyatı Dolibarr'da tedarikçiye bağlı kaydedilir; fiyat girerseniz tedarikçi seçmelisiniz.", fontSize = 12.sp, color = Color.Gray)
    }

    SectionCard {
        Text("Depo, kategori ve stok uyarıları", fontWeight = FontWeight.Bold)
        if (isNew) {
            Row(Modifier.fillMaxWidth().clickable { f.useDefWh = !f.useDefWh }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(f.useDefWh, { f.useDefWh = it })
                Text("Varsayılan depo ($defWhName)")
            }
            if (!f.useDefWh) PickerField("Depo", lists.whs.firstOrNull { it.first == f.wh }?.second ?: "Depo ${f.wh}", lists.whs) { f.wh = it }
        } else {
            PickerField("Varsayılan depo", lists.whs.firstOrNull { it.first == f.wh }?.second ?: "Depo ${f.wh}", lists.whs) { f.wh = it }
        }
        PickerField("Kategori (TakePOS'ta görünmesi için gerekli)", catName, lists.cats) { f.catId = it }
        Field(f.alertStock, { f.alertStock = it }, "Stok uyarı sınırı", numeric = true)
        Field(f.desiredStock, { f.desiredStock = it }, "İstenen (optimum) stok", numeric = true)
    }
}

@Composable
fun NewProductScreen(
    api: DoliApi, prefs: Prefs, granted: Boolean, startBarcode: String,
    onCreated: (Product) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val f = remember { ProductForm(prefs, startBarcode, true) }
    val lists = rememberFormLists(api)
    var qty by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var photoBytes by remember { mutableStateOf<ByteArray?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }

    val photoFile = remember { File(File(ctx.cacheDir, "photos").also { it.mkdirs() }, "new.jpg") }
    val photoUri = remember { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", photoFile) }
    val takePic = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) scope.launch {
            val b = withContext(Dispatchers.Default) { compress(photoFile) }
            photoBytes = b
            preview = withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = 4 })
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ProductFormFields(f, api, prefs, granted, true, lists) { msg = it }
        SectionCard {
            Text("Başlangıç stoku", fontWeight = FontWeight.Bold)
            Field(qty, { qty = it.filter { c -> c.isDigit() } }, "Stok miktarı (çift)", numeric = true)
        }
        SectionCard {
            Text("Fotoğraf", fontWeight = FontWeight.Bold)
            preview?.let { bmp ->
                Image(
                    bmp.asImageBitmap(), null,
                    Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton(if (photoBytes == null) "📷 Fotoğraf çek" else "📷 Yeniden çek", Modifier.weight(1f), Cyan) {
                    takePic.launch(photoUri)
                }
                if (photoBytes != null) BigButton("Kaldır", Modifier.weight(1f), Color(0xFF8A99AD)) {
                    photoBytes = null; preview = null
                }
            }
            Text("Fotoğraf, ürün oluşturulunca Dolibarr'a yüklenir.", fontSize = 12.sp, color = Color.Gray)
        }
        StatusCard(lists.err)
        StatusCard(msg)
        BigButton("✔ Ürünü oluştur", Modifier.fillMaxWidth(), Green, enabled = !busy) {
            scope.launch {
                busy = true; msg = "Oluşturuluyor..."
                try {
                    val l = f.label.trim()
                    if (l.isEmpty()) throw Exception("Ürün adı gerekli")
                    if (f.catId.isEmpty()) throw Exception("Kategori seçin (kategorisiz ürün TakePOS'ta görünmez)")
                    val buy = f.buyPrice.replace(',', '.').toDoubleOrNull()
                    if (f.buyPrice.isNotBlank() && buy == null) throw Exception("Alış fiyatı geçersiz")
                    if (buy != null && f.supplierId.isEmpty()) throw Exception("Alış fiyatı için tedarikçi seçin")
                    var bc = f.barcode.trim()
                    // Barkod boşsa Dolibarr kendi barkod modülüyle (tür + maske) üretir
                    var autoGenerate = bc.isEmpty()
                    if (autoGenerate) bc = AUTO_BARCODE
                    val r = f.ref.trim().ifEmpty {
                        if (autoGenerate) "P${System.currentTimeMillis()}" else bc
                    }
                    if (r.isEmpty()) throw Exception("Ürün kodu veya barkod gerekli")
                    if (bc.isNotEmpty() && !autoGenerate) api.byBarcode(bc)?.let {
                        throw Exception("Bu barkod zaten kayıtlı: ${it.label} (${it.ref})")
                    }
                    val whUsed = if (f.useDefWh) prefs.get("wh") else f.wh
                    val vatUsed = if (f.useDefVat) prefs.get("vat") else f.vat
                    val id = try {
                        api.createProduct(r, l, bc, f.price, vatUsed, f.desc.trim(), whUsed, f.tosell, f.tobuy, f.alertStock, f.desiredStock)
                    } catch (e: BarcodeFormatException) {
                        if (autoGenerate) throw e
                        // Taranan barkod Dolibarr'ın barkod ayarına uymadı: barkodu Dolibarr versin, düzeltme ürün kartında yapılır
                        autoGenerate = true; bc = AUTO_BARCODE
                        Toast.makeText(ctx, "Taranan barkod Dolibarr'a uymadı; barkod otomatik verildi. Gerekirse ürün kartından düzeltin.", Toast.LENGTH_LONG).show()
                        api.createProduct(r, l, bc, f.price, vatUsed, f.desc.trim(), whUsed, f.tosell, f.tobuy, f.alertStock, f.desiredStock)
                    }
                    prefs.set("cat", f.catId)
                    try { api.linkCategory(f.catId, id) } catch (e: Exception) {
                        Toast.makeText(ctx, "Ürün oluşturuldu ama kategoriye eklenemedi: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    if (buy != null) try {
                        api.setPurchasePrice(id, f.supplierId, f.buyPrice, f.minQty, r, vatUsed)
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Alış fiyatı kaydedilemedi: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    val q = qty.toIntOrNull() ?: 0
                    if (q != 0) try { api.adjustStock(id, whUsed, q, "İlk stok (Doliparr Android)") } catch (e: Exception) {
                        Toast.makeText(ctx, "Stok girilemedi: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    photoBytes?.let { b ->
                        try {
                            api.uploadPhoto(r, r.replace(Regex("[^A-Za-z0-9_-]"), "_") + "_" + System.currentTimeMillis() + ".jpg", b)
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "Fotoğraf yüklenemedi: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                    val created = api.get(id)
                    if (autoGenerate && created.barcode.isNotBlank()) f.changeBarcode(created.barcode)
                    onCreated(created)
                } catch (e: Exception) {
                    msg = errMsg(e)
                }
                busy = false
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun DetailScreen(api: DoliApi, prefs: Prefs, granted: Boolean, start: Product) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val f = remember { ProductForm(prefs, "", false).also { it.load(start) } }
    val lists = rememberFormLists(api)
    var p by remember { mutableStateOf(start) }
    var origCat by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(start.stock) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var photoVer by remember { mutableStateOf(0) }

    fun fill(x: Product) { p = x; f.load(x); target = x.stock }

    LaunchedEffect(start.id) {
        runCatching { api.get(start.id) }.onSuccess { fill(it) }
        origCat = runCatching { api.productCategories(start.id).firstOrNull() ?: "" }.getOrDefault("")
        f.catId = origCat
        runCatching { api.purchasePrice(start.id) }.getOrNull()?.let {
            f.supplierId = it.supplierId; f.buyPrice = it.price; f.minQty = it.qty
        }
    }

    fun act(block: suspend () -> String) {
        scope.launch {
            busy = true; msg = "Çalışıyor..."
            msg = try { block() } catch (e: Exception) { errMsg(e) }
            busy = false
        }
    }

    val photoFile = remember { File(File(ctx.cacheDir, "photos").also { it.mkdirs() }, "tmp.jpg") }
    val photoUri = remember { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", photoFile) }
    val takePic = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) act {
            val bytes = withContext(Dispatchers.Default) { compress(photoFile) }
            val name = p.ref.replace(Regex("[^A-Za-z0-9_-]"), "_") + "_" + System.currentTimeMillis() + ".jpg"
            api.uploadPhoto(p.ref, name, bytes)
            ThumbCache.invalidate(p.id); photoVer++
            "✔ Fotoğraf yüklendi: $name"
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProductThumb(api, p, 110.dp, photoVer)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Kod: ${p.ref}", fontWeight = FontWeight.Bold)
                    Text("Mevcut stok: ${p.stock}")
                }
            }
            if (ThumbCache.lastError.isNotEmpty())
                Text("Fotoğraf hatası: ${ThumbCache.lastError}", color = Red, fontSize = 12.sp)
            BigButton("📷 Fotoğraf çek ve yükle", Modifier.fillMaxWidth(), Cyan, enabled = !busy) { takePic.launch(photoUri) }
        }

        ProductFormFields(f, api, prefs, granted, false, lists) { msg = it }
        StatusCard(lists.err)

        BigButton("Bilgileri kaydet", Modifier.fillMaxWidth(), Green, enabled = !busy) {
            act {
                val buy = f.buyPrice.replace(',', '.').toDoubleOrNull()
                if (f.buyPrice.isNotBlank() && buy == null) throw Exception("Alış fiyatı geçersiz")
                if (buy != null && f.supplierId.isEmpty()) throw Exception("Alış fiyatı için tedarikçi seçin")
                if (f.label.isBlank()) throw Exception("Ürün adı gerekli")
                f.barcodeIssue()?.let { throw BarcodeFormatException(it) }
                api.updateProduct(
                    p, f.ref.trim(), f.label.trim(), f.barcode.trim(), f.desc.trim(), f.price, f.vat, f.wh,
                    f.tosell, f.tobuy, f.alertStock, f.desiredStock
                )
                if (f.catId.isNotEmpty() && f.catId != origCat) {
                    if (origCat.isNotEmpty()) try { api.unlinkCategory(origCat, p.id) } catch (_: Exception) { }
                    api.linkCategory(f.catId, p.id)
                    origCat = f.catId
                }
                if (buy != null) api.setPurchasePrice(p.id, f.supplierId, f.buyPrice, f.minQty, f.ref.trim().ifEmpty { p.ref }, f.vat)
                fill(api.get(p.id))
                "✔ Kaydedildi"
            }
        }

        SectionCard {
            Text("Stok", fontWeight = FontWeight.Bold)
            Field(target, { target = it.filter { c -> c.isDigit() || c == '-' } }, "Yeni stok miktarı", numeric = true)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(-8, -1, 1, 8).forEach { d ->
                    OutlinedButton({ target = ((target.toIntOrNull() ?: 0) + d).toString() }, Modifier.weight(1f)) {
                        Text(if (d > 0) "+$d" else "$d")
                    }
                }
            }
            BigButton("Stoğu güncelle", Modifier.fillMaxWidth(), Blue, enabled = !busy) {
                act {
                    val cur = p.stock.toDoubleOrNull()?.toInt() ?: 0
                    val d = (target.toIntOrNull() ?: cur) - cur
                    if (d == 0) "Stok zaten $cur" else {
                        api.adjustStock(p.id, prefs.get("wh"), d, "Doliparr Android telefon düzeltmesi")
                        fill(api.get(p.id)); "✔ Stok güncellendi: ${p.stock}"
                    }
                }
            }
        }
        StatusCard(msg)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun SettingsScreen(api: DoliApi, prefs: Prefs, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val keys = listOf("baseUrl", "apiKey", "wh", "whName", "pc", "token", "vat", "bcType", "bcPrefix", "bcLen")
    val bcOptions = listOf(
        "auto" to "Otomatik (Dolibarr varsayılanı)",
        "EAN13" to "EAN-13 (13 hane)",
        "EAN8" to "EAN-8 (8 hane)",
        "UPC" to "UPC-A (12 hane)",
        "C128" to "Code 128 (10 hane, sayısal)"
    )
    val vals = remember { mutableStateMapOf<String, String>().apply { keys.forEach { put(it, prefs.get(it)) } } }
    var whs by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var msg by remember { mutableStateOf("") }

    LaunchedEffect(api) { try { whs = api.warehouses() } catch (_: Exception) { } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionCard {
            Text("Dolibarr bağlantısı", fontWeight = FontWeight.Bold)
            Field(vals["baseUrl"] ?: "", { vals["baseUrl"] = it }, "Dolibarr adresi (http://192.168.1.20/dolibarr)")
            Field(vals["apiKey"] ?: "", { vals["apiKey"] = it }, "Dolibarr API anahtarı")
        }
        SectionCard {
            Text("Yeni ürün varsayılanları", fontWeight = FontWeight.Bold)
            PickerField(
                "Varsayılan depo",
                (vals["whName"] ?: "").ifEmpty { "Depo ${vals["wh"]}" },
                whs
            ) { id ->
                vals["wh"] = id
                vals["whName"] = whs.firstOrNull { it.first == id }?.second ?: ""
            }
            Field(vals["vat"] ?: "", { vals["vat"] = it }, "Varsayılan KDV oranı (%)", numeric = true)
        }
        SectionCard {
            Text("Otomatik barkod", fontWeight = FontWeight.Bold)
            Field(vals["bcPrefix"] ?: "", { vals["bcPrefix"] = it }, "Barkod öneki (Dolibarr maskesindeki sabit kısım, örn. 04)")
            Field(vals["bcLen"] ?: "", { vals["bcLen"] = it }, "Barkodun toplam hane sayısı (örn. 12)", numeric = true)
            PickerField(
                "Önek girilmezse barkod türü",
                bcOptions.firstOrNull { it.first == vals["bcType"] }?.second ?: bcOptions[0].second,
                bcOptions
            ) { vals["bcType"] = it }
            Text(
                "Önek ve hane sayısı doluysa mevcut en büyük barkodun devamı üretilir (örn. 040000000006).",
                fontSize = 12.sp, color = Color.Gray
            )
        }
        SectionCard {
            Text("Kasa bilgisayarı (barkodu TakePOS'a yazar)", fontWeight = FontWeight.Bold)
            Field(vals["pc"] ?: "", { vals["pc"] = it }, "Kasa bilgisayarının IP adresi")
            Field(vals["token"] ?: "", { vals["token"] = it }, "Gizli kelime (token.txt ile aynı)")
        }
        StatusCard(msg)
        BigButton("Kaydet", Modifier.fillMaxWidth(), Green) {
            keys.forEach { prefs.set(it, vals[it]?.trim() ?: "") }
            onSaved(); msg = "Kaydedildi. Şimdi bağlantıyı test edebilirsiniz."
        }
        BigButton("Dolibarr bağlantısını test et", Modifier.fillMaxWidth(), Blue) {
            scope.launch {
                msg = "Test ediliyor..."
                msg = try { "✔ Dolibarr ${api.status()} bağlantısı tamam" } catch (e: Exception) { "✖ ${e.message}" }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Fotoğrafı küçültür (en uzun kenar 1280px), EXIF yönünü düzeltir, JPEG'e çevirir. */
fun compress(f: File): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, bounds)
    var s = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / s > 1280) s *= 2
    val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    val deg = when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    val out = if (deg != 0f)
        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
    else bmp
    return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
}
