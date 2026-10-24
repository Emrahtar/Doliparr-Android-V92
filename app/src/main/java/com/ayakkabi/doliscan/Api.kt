package com.ayakkabi.doliscan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

data class Product(
    val id: String, val ref: String, val label: String, val barcode: String,
    val priceTtc: String, val tva: String, val stock: String,
    val description: String = "", val defWarehouse: String = "",
    val alertStock: String = "", val desiredStock: String = "",
    val tosell: Boolean = true, val tobuy: Boolean = true, val priceBase: String = ""
)

data class PurchasePrice(val supplierId: String, val price: String, val qty: String)

class ApiException(val code: Int, msg: String) : Exception(msg)

/** Kullanıcıya olduğu gibi gösterilecek, barkod biçimiyle ilgili açıklayıcı uyarı. */
/** Barkod alanına bu değer gönderilirse numarayı Dolibarr kendi barkod modülüyle (tür + maske) üretir. */
const val AUTO_BARCODE = "auto"

class BarcodeFormatException(msg: String) : Exception(msg)

fun errMsg(e: Exception): String = if (e is BarcodeFormatException) "⚠ ${e.message}" else "Hata: ${e.message}"

/** GS1 kontrol hanesi (EAN-13, EAN-8, UPC-A): sağdan sola 3,1,3,1... ağırlıklar */
fun gtinCheckDigit(body: String): Int {
    var sum = 0
    body.reversed().forEachIndexed { i, c -> sum += (c - '0') * (if (i % 2 == 0) 3 else 1) }
    return (10 - sum % 10) % 10
}

/**
 * Barkodu Dolibarr'a göndermeden önce biçimini denetler.
 * Sorun yoksa null, varsa kullanıcıya gösterilecek düzeltme önerisini döndürür.
 */
fun barcodeProblem(code: String): String? {
    if (code.isEmpty()) return null
    if (code.any { it.isWhitespace() })
        return "Barkodda boşluk olmamalı. Lütfen boşlukları silin."
    if (code.any { it.code !in 33..126 })
        return "Barkod yalnızca harf, rakam ve standart işaretlerden oluşmalı. Lütfen Türkçe/özel karakterleri kaldırın."
    if (code.all { it in '0'..'9' } && code.length in listOf(8, 12, 13)) {
        val body = code.dropLast(1)
        val expected = gtinCheckDigit(body)
        if (code.last() - '0' != expected) {
            val kind = when (code.length) { 13 -> "EAN-13"; 8 -> "EAN-8"; else -> "UPC-A" }
            return "Barkod formatı hatalı: ${code.length} haneli $kind barkodun son hanesi kontrol hanesidir ve bu numara için $expected olmalı ($body$expected). " +
                "Lütfen barkodu düzeltin veya boş bırakıp otomatik oluşturun."
        }
    }
    return null
}

enum class BcType(val label: String) {
    EAN13("EAN-13"), EAN8("EAN-8"), UPC("UPC-A (12 hane)"), C128("Code 128")
}

private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)

class DoliApi(private val baseUrl: String, private val apiKey: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS).build()
    private val quick = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private suspend fun call(
        method: String, path: String,
        query: Map<String, String> = emptyMap(), body: JSONObject? = null
    ): String = withContext(Dispatchers.IO) {
        val url = (baseUrl.trimEnd('/') + "/api/index.php/" + path).toHttpUrl().newBuilder()
            .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val rb = when {
            body != null -> body.toString().toRequestBody(jsonType)
            method == "GET" -> null
            else -> "".toRequestBody(jsonType)
        }
        val req = Request.Builder().url(url)
            .header("DOLAPIKEY", apiKey).header("Accept", "application/json")
            .method(method, rb).build()
        http.newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                if (t.contains("ErrorBadBarCodeSyntax"))
                    throw BarcodeFormatException(
                        "Barkod formatı hatalı, lütfen barkodu düzeltin. " +
                        "EAN-13 için 13 hane (geçerli kontrol hanesiyle), EAN-8 için 8 hane, UPC-A için 12 hane gerekir. " +
                        "Dolibarr'ın barkod modülünde özel bir maske tanımlıysa barkod ona da uymalıdır (Ayarlar'daki barkod önek/hane değerleri). " +
                        "Sorun sürerse barkod alanını boş bırakıp \"Barkod boşsa otomatik oluştur\" kutusunu işaretleyin; numarayı Dolibarr kendi maskesine göre üretir."
                    )
                if (t.contains("ErrorBarCodeRequired"))
                    throw BarcodeFormatException("Barkod boş bırakılamaz: Dolibarr bu ürün için barkod girilmesini zorunlu tutuyor.")
                if (t.contains("ErrorBarCodeAlreadyUsed"))
                    throw BarcodeFormatException("Bu barkod başka bir üründe zaten kayıtlı. Lütfen farklı bir barkod girin.")
                var m = "HTTP ${r.code}: ${t.take(160)}"
                if (r.code == 403) m += "\n(Dolibarr kullanıcısının ilgili yetkisi eksik olabilir)"
                throw ApiException(r.code, m)
            }
            t
        }
    }

    private fun num(s: String, dec: Int): String =
        s.toDoubleOrNull()?.let { String.format(Locale.US, "%.${dec}f", it) } ?: ""

    private fun rate(s: String): String =
        s.toDoubleOrNull()?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "0"

    private fun parse(o: JSONObject) = Product(
        id = o.str("id"), ref = o.str("ref"), label = o.str("label"),
        barcode = o.str("barcode"),
        priceTtc = num(o.str("price_ttc"), 2),
        tva = rate(o.str("tva_tx")),
        stock = num(o.str("stock_reel"), 0).ifEmpty { "0" },
        description = o.str("description"),
        defWarehouse = o.str("fk_default_warehouse").let { if (it == "0") "" else it },
        alertStock = num(o.str("seuil_stock_alerte"), 0),
        desiredStock = num(o.str("desiredstock"), 0),
        tosell = o.str("status") != "0",
        tobuy = o.str("status_buy") != "0",
        priceBase = o.str("price_base_type")
    )

    suspend fun status(): String =
        JSONObject(call("GET", "status")).optJSONObject("success")?.optString("dolibarr_version") ?: "ok"

    suspend fun products(search: String, page: Int): List<Product> {
        val q = mutableMapOf(
            "sortfield" to "t.ref", "sortorder" to "ASC", "limit" to "50",
            "page" to page.toString(), "mode" to "1", "includestockdata" to "1"
        )
        val s = search.replace(Regex("[^\\p{L}\\p{N} .-]"), "").trim()
        if (s.isNotEmpty())
            q["sqlfilters"] = "(t.label:like:'%$s%') or (t.ref:like:'%$s%') or (t.barcode:like:'%$s%')"
        return try {
            val a = JSONArray(call("GET", "products", q))
            (0 until a.length()).map { parse(a.getJSONObject(it)) }
        } catch (e: ApiException) {
            if (e.code == 404) emptyList() else throw e
        }
    }

    suspend fun get(id: String): Product =
        parse(JSONObject(call("GET", "products/$id", mapOf("includestockdata" to "1"))))

    suspend fun byBarcode(code: String): Product? = try {
        parse(JSONObject(call("GET", "products/barcode/" + URLEncoder.encode(code, "UTF-8"))))
    } catch (e: ApiException) {
        if (e.code == 404) null else throw e
    }

    /** Barkodun uzunluğundan Dolibarr barkod türü (id, kod). Dolibarr varsayılan kayıtları: 1=EAN8, 2=EAN13, 3=UPC, 6=C128 */
    private fun barcodeTypeFor(code: String): Pair<Int, String> = when {
        code.all { it.isDigit() } && code.length == 13 -> 2 to "EAN13"
        code.all { it.isDigit() } && code.length == 8 -> 1 to "EAN8"
        code.all { it.isDigit() } && code.length == 12 -> 3 to "UPC"
        else -> 6 to "C128"
    }

    /** Satış fiyatını KDV DAHİL (TTC) olarak yazar. */
    private fun putPrice(b: JSONObject, ttc: Double, vat: Double) {
        b.put("tva_tx", vat.toString())
            .put("price_ttc", String.format(Locale.US, "%.2f", ttc))
            .put("price", String.format(Locale.US, "%.8f", ttc / (1 + vat / 100.0)))
            .put("price_base_type", "TTC")
    }

    suspend fun updateProduct(
        p: Product, ref: String, label: String, barcode: String, description: String,
        priceTtc: String, vat: String, warehouseId: String,
        tosell: Boolean, tobuy: Boolean, alertStock: String, desiredStock: String
    ) {
        val b = JSONObject()
            .put("label", label).put("description", description)
            .put("status", if (tosell) 1 else 0).put("status_buy", if (tobuy) 1 else 0)
        if (ref.isNotBlank() && ref != p.ref) b.put("ref", ref)
        // Barkod yalnızca elle değiştirildiyse gönderilir; böylece Dolibarr'ın verdiği barkod yeniden doğrulanmaz
        if (barcode != p.barcode) {
            b.put("barcode", barcode)
            if (barcode.isNotBlank()) {
                val (tid, tcode) = barcodeTypeFor(barcode)
                b.put("barcode_type", tid).put("barcode_type_code", tcode)
            }
        }
        alertStock.toDoubleOrNull()?.let { b.put("seuil_stock_alerte", it.toInt()) }
        desiredStock.toDoubleOrNull()?.let { b.put("desiredstock", it.toInt()) }
        warehouseId.toIntOrNull()?.let { b.put("fk_default_warehouse", it) }
        val ttc = priceTtc.replace(',', '.').toDoubleOrNull()
        val vatNew = vat.replace(',', '.').toDoubleOrNull() ?: (p.tva.toDoubleOrNull() ?: 0.0)
        if (ttc != null) {
            val changed = String.format(Locale.US, "%.2f", ttc) != p.priceTtc ||
                vatNew != (p.tva.toDoubleOrNull() ?: 0.0) || p.priceBase != "TTC"
            if (changed) putPrice(b, ttc, vatNew)
        }
        call("PUT", "products/${p.id}", body = b)
    }

    /** delta pozitif = stok girişi, negatif = stok çıkışı */
    suspend fun adjustStock(productId: String, warehouseId: String, delta: Int, label: String) {
        val b = JSONObject()
            .put("product_id", productId.toInt())
            .put("warehouse_id", warehouseId.toInt())
            .put("qty", delta)
            .put("movementlabel", label)
        call("POST", "stockmovements", body = b)
    }

    suspend fun uploadPhoto(ref: String, filename: String, bytes: ByteArray) {
        val b = JSONObject()
            .put("filename", filename)
            .put("modulepart", "product")
            .put("ref", ref)
            .put("filecontent", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("fileencoding", "base64")
            .put("overwriteifexists", "0")
            .put("createdirifnotexists", "1")
        call("POST", "documents/upload", body = b)
    }

    /** Yeni ürün kartı oluşturur, yeni ürünün id'sini döndürür. Fiyat KDV dahil girilir ve KDV dahil olarak kaydedilir. */
    suspend fun createProduct(
        ref: String, label: String, barcode: String, priceTtc: String, vat: String,
        description: String, warehouseId: String,
        tosell: Boolean, tobuy: Boolean, alertStock: String, desiredStock: String
    ): String {
        val rate = vat.replace(',', '.').toDoubleOrNull() ?: 0.0
        val ttc = priceTtc.replace(',', '.').toDoubleOrNull() ?: 0.0
        val b = JSONObject()
            .put("ref", ref).put("label", label).put("type", 0)
            .put("status", if (tosell) 1 else 0).put("status_buy", if (tobuy) 1 else 0)
        putPrice(b, ttc, rate)
        if (barcode == AUTO_BARCODE) {
            // Barkodu Dolibarr üretsin: barkod modülündeki varsayılan tür ve maske uygulanır (tür/kod göndermiyoruz)
            b.put("barcode", AUTO_BARCODE)
        } else if (barcode.isNotBlank()) {
            val (tid, tcode) = barcodeTypeFor(barcode)
            b.put("barcode", barcode).put("barcode_type", tid).put("barcode_type_code", tcode)
        }
        if (description.isNotBlank()) b.put("description", description)
        alertStock.toDoubleOrNull()?.let { b.put("seuil_stock_alerte", it.toInt()) }
        desiredStock.toDoubleOrNull()?.let { b.put("desiredstock", it.toInt()) }
        warehouseId.toIntOrNull()?.let { b.put("fk_default_warehouse", it) }
        val id = call("POST", "products", body = b).trim().trim('"')
        // Bazı sürümler oluştururken varsayılan depoyu/barkod türünü yazmaz; ayrıca güncelle (hata olursa yoksay)
        val fix = JSONObject()
        warehouseId.toIntOrNull()?.let { fix.put("fk_default_warehouse", it) }
        if (barcode.isNotBlank() && barcode != AUTO_BARCODE) fix.put("barcode_type", barcodeTypeFor(barcode).first)
        if (fix.length() > 0) try { call("PUT", "products/$id", body = fix) } catch (_: Exception) {}
        return id
    }

    suspend fun categories(): List<Pair<String, String>> {
        val text = try {
            call("GET", "categories", mapOf("type" to "product", "sortfield" to "t.label", "sortorder" to "ASC", "limit" to "1000"))
        } catch (e: ApiException) {
            if (e.code == 404) return emptyList() else throw e
        }
        val items = docItems(text)
        val byId = items.associateBy { it.str("id") }
        fun path(o: JSONObject, depth: Int): String {
            val parent = byId[o.str("fk_parent")]
            return if (parent != null && depth < 8) path(parent, depth + 1) + " › " + o.str("label") else o.str("label")
        }
        return items.map { it.str("id") to path(it, 0) }.sortedBy { it.second }
    }

    suspend fun warehouses(): List<Pair<String, String>> {
        val text = try {
            call("GET", "warehouses", mapOf("limit" to "100"))
        } catch (e: ApiException) {
            if (e.code == 404) return emptyList() else throw e
        }
        return docItems(text).map { it.str("id") to it.str("label").ifEmpty { it.str("ref") } }
    }

    suspend fun suppliers(): List<Pair<String, String>> {
        val text = try {
            call("GET", "thirdparties", mapOf("mode" to "4", "sortfield" to "t.nom", "sortorder" to "ASC", "limit" to "500"))
        } catch (e: ApiException) {
            if (e.code == 404) return emptyList() else throw e
        }
        return docItems(text)
            .map { it.str("id") to it.str("name").ifEmpty { it.str("nom") } }
            .sortedBy { it.second.lowercase() }
    }

    suspend fun linkCategory(categoryId: String, productId: String) {
        call("POST", "categories/$categoryId/objects/product/$productId")
    }

    suspend fun unlinkCategory(categoryId: String, productId: String) {
        call("DELETE", "categories/$categoryId/objects/product/$productId")
    }

    suspend fun productCategories(productId: String): List<String> = try {
        docItems(call("GET", "categories/object/product/$productId")).map { it.str("id") }
    } catch (e: ApiException) {
        if (e.code == 404) emptyList() else throw e
    }

    /** Ürünün ilk tedarikçi alış fiyatı (varsa). */
    suspend fun purchasePrice(productId: String): PurchasePrice? {
        val text = try {
            call("GET", "products/$productId/purchase_prices")
        } catch (e: ApiException) {
            if (e.code == 404) return null else throw e
        }
        val o = docItems(text).firstOrNull() ?: return null
        val sup = o.str("fourn_id").ifEmpty { o.str("fk_soc") }
        if (sup.isEmpty()) return null
        val price = num(o.str("fourn_price").ifEmpty { o.str("price") }, 2)
        val qty = num(o.str("fourn_qty").ifEmpty { o.str("quantity") }, 0).ifEmpty { "1" }
        return PurchasePrice(sup, price, qty)
    }

    /** Tedarikçi alış fiyatı ekler/günceller (en düşük miktar için toplam fiyat, KDV hariç). */
    suspend fun setPurchasePrice(productId: String, supplierId: String, price: String, qty: String, refFourn: String, vat: String) {
        val b = JSONObject()
            .put("qty", qty.replace(',', '.').toDoubleOrNull() ?: 1.0)
            .put("buyprice", price.replace(',', '.').toDouble())
            .put("price_base_type", "HT")
            .put("fourn_id", supplierId.toInt())
            .put("availability", 0)
            .put("ref_fourn", refFourn)
            .put("tva_tx", vat.replace(',', '.').toDoubleOrNull() ?: 0.0)
        call("POST", "products/$productId/purchase_prices", body = b)
    }

    /** JSON dizi veya nesne olarak gelen listeyi nesne listesine çevirir. */
    private fun docItems(text: String): List<JSONObject> {
        return when (val v = org.json.JSONTokener(text).nextValue()) {
            is JSONArray -> (0 until v.length()).mapNotNull { v.optJSONObject(it) }
            is JSONObject -> v.keys().asSequence().mapNotNull { v.optJSONObject(it) }.toList()
            else -> emptyList()
        }
    }

    /** Ürünün ilk fotoğrafını küçük boyutlu indirir. Fotoğraf yoksa null. */
    suspend fun photo(p: Product): Bitmap? {
        val text = try {
            call("GET", "documents", mapOf("modulepart" to "product", "id" to p.id))
        } catch (e: ApiException) {
            if (e.code == 404) return null
            try {
                call("GET", "documents", mapOf("modulepart" to "product", "ref" to p.ref))
            } catch (e2: ApiException) {
                if (e2.code == 404) return null else throw e
            }
        }
        val imgRe = Regex("(?i).+\\.(jpe?g|png|webp)")
        for (o in docItems(text)) {
            val name = o.str("name")
            if (!name.matches(imgRe)) continue
            val rels = listOf(o.str("relativename"), p.ref + "/" + name).filter { it.isNotEmpty() }.distinct()
            var content = ""
            var lastErr: ApiException? = null
            for (rel in rels) {
                try {
                    content = JSONObject(
                        call("GET", "documents/download", mapOf("modulepart" to "product", "original_file" to rel))
                    ).str("content")
                    break
                } catch (e: ApiException) {
                    lastErr = e
                }
            }
            if (content.isEmpty()) {
                if (lastErr != null) throw lastErr
                continue
            }
            val bytes = Base64.decode(content, Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 320) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        return null
    }


    private var detectedType: BcType? = null

    /** Dolibarr'ın varsayılan ürün barkod türünü okur; okunamazsa mevcut ürünlerin barkod uzunluğuna bakar. */
    suspend fun defaultBarcodeType(): BcType? {
        detectedType?.let { return it }
        try {
            val raw = call("GET", "setup/conf/PRODUIT_DEFAULT_BARCODE_TYPE").trim().trim('"').uppercase()
            val t = when (raw) {
                "1", "EAN8" -> BcType.EAN8
                "2", "EAN13" -> BcType.EAN13
                "3", "UPC" -> BcType.UPC
                "5", "6", "C39", "C128" -> BcType.C128
                else -> null
            }
            if (t != null) { detectedType = t; return t }
        } catch (_: Exception) { }
        try {
            val sample = products("", 0).firstOrNull { it.barcode.isNotEmpty() && it.barcode.all { c -> c.isDigit() } }?.barcode
            val t = when (sample?.length) {
                13 -> BcType.EAN13
                8 -> BcType.EAN8
                12 -> BcType.UPC
                else -> null
            }
            if (t != null) { detectedType = t; return t }
        } catch (_: Exception) { }
        return null
    }

    /**
     * Yeni mağaza içi barkod üretir.
     * Ayarlarda önek + hane sayısı girildiyse (Dolibarr barkod maskesine uygun) o önekle devam eden bir sonraki numara üretilir.
     * Girilmediyse seçilen/algılanan barkod türüne göre (EAN-13, EAN-8, UPC-A, Code 128) üretilir.
     */
    suspend fun generateBarcode(prefs: Prefs): Pair<String, String> {
        val prefix = prefs.get("bcPrefix").filter { it.isLetterOrDigit() }
        val len = prefs.get("bcLen").toIntOrNull() ?: 0
        if (prefix.isNotEmpty() && len > prefix.length) {
            val width = len - prefix.length
            val text = try {
                call(
                    "GET", "products",
                    mapOf(
                        "sortfield" to "t.barcode", "sortorder" to "DESC", "limit" to "100", "mode" to "1",
                        "sqlfilters" to "(t.barcode:like:'$prefix%')"
                    )
                )
            } catch (e: ApiException) {
                if (e.code == 404) "[]" else throw e
            }
            val maxN = docItems(text).map { it.str("barcode") }
                .filter { it.length == len && it.startsWith(prefix) && it.drop(prefix.length).all { c -> c.isDigit() } }
                .maxOfOrNull { it.drop(prefix.length).toLong() } ?: 0L
            var n = maxN + 1
            repeat(200) {
                val code = prefix + n.toString().padStart(width, '0')
                if (code.length == len && byBarcode(code) == null) return code to "önek $prefix, $len hane"
                n++
            }
            throw Exception("Boş barkod bulunamadı (önek ve hane sayısı ayarını kontrol edin)")
        }

        val type = when (prefs.get("bcType")) {
            "EAN13" -> BcType.EAN13
            "EAN8" -> BcType.EAN8
            "UPC" -> BcType.UPC
            "C128" -> BcType.C128
            else -> defaultBarcodeType() ?: BcType.EAN13
        }
        var seq = prefs.get("bcSeq").toLongOrNull() ?: 0L
        repeat(300) {
            seq++
            val code = when (type) {
                BcType.EAN13 -> ("200" + seq.toString().padStart(9, '0')).let { it + gtinCheckDigit(it) }
                BcType.EAN8 -> ("2" + seq.toString().padStart(6, '0')).let { it + gtinCheckDigit(it) }
                BcType.UPC -> ("4" + seq.toString().padStart(10, '0')).let { it + gtinCheckDigit(it) }
                BcType.C128 -> "2" + seq.toString().padStart(9, '0')
            }
            if (byBarcode(code) == null) {
                prefs.set("bcSeq", seq.toString())
                return code to type.label
            }
        }
        throw Exception("Boş otomatik barkod bulunamadı")
    }

    /** Kasa PC'deki röle programına barkodu gönderir (klavye gibi yazılır). */
    suspend fun sendToPc(host: String, token: String, code: String) = withContext(Dispatchers.IO) {
        val url = "http://$host:8765/scan".toHttpUrl().newBuilder()
            .addQueryParameter("code", code).addQueryParameter("token", token).build()
        quick.newCall(Request.Builder().url(url).build()).execute().use {
            if (!it.isSuccessful) {
                val t = it.body?.string().orEmpty().take(60)
                throw Exception(
                    if (it.code == 403) "PC reddetti: telefondaki gizli kelime ile relay.ps1 içindeki aynı değil ($t)"
                    else "PC yanıtı: HTTP ${it.code} $t"
                )
            }
        }
    }
}
