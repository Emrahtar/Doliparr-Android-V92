package com.ayakkabi.doliscan

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

sealed class Photo {
    object None : Photo()
    class Img(val bmp: Bitmap) : Photo()
    class Error(val msg: String) : Photo()
}

object ThumbCache {
    private val mem = LruCache<String, Photo>(60)
    private val gate = Semaphore(3)

    /** Son fotoğraf hatası (ekranda tanı amaçlı gösterilir). */
    var lastError by mutableStateOf("")

    fun peek(id: String): Photo? = mem.get(id)
    fun invalidate(id: String) { mem.remove(id) }

    suspend fun load(api: DoliApi, p: Product): Photo {
        mem.get(p.id)?.let { return it }
        val r: Photo = gate.withPermit {
            try {
                val b = api.photo(p)
                if (b == null) Photo.None else Photo.Img(b)
            } catch (e: Exception) {
                Photo.Error(e.message ?: "hata")
            }
        }
        if (r is Photo.Error) lastError = r.msg else { lastError = ""; mem.put(p.id, r) }
        return r
    }
}
