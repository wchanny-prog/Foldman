package dev.foldman.deck

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * One cassette case on the shelf: an album that has been played in the deck. The J-card colours
 * come from the album art, the way the cassette label's do.
 */
data class TapeCase(
    val id: String,
    val source: Source,
    val album: String,
    val artist: String,
    /** ARGB colours of the J-card's two stripes. */
    val stripe: Int,
    val stripe2: Int,
    val hasCover: Boolean,
    val addedAt: Long,
    val lastPlayedAt: Long,
)

/**
 * The shelf of tape cases. Every album played in the deck gets a case, kept on the phone as a
 * small JSON file with a thumbnail of the cover beside it.
 */
class TapeCaseStore(context: Context) {
    private val dir = File(context.filesDir, "cases").apply { mkdirs() }
    private val index = File(dir, "cases.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val covers = android.util.LruCache<String, ImageBitmap>(64)

    // The index is a few kilobytes, so it's read up front: a case noted straight away can't be lost to a late load.
    private val _cases = MutableStateFlow(read())
    val cases: StateFlow<List<TapeCase>> = _cases.asStateFlow()

    private var notedId: String? = null
    private var notedArt: Bitmap? = null
    private var notedStripe = 0

    fun idOf(source: Source, album: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("${source.name}|${album.trim().lowercase()}".toByteArray())
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }

    /**
     * Called while an album plays. Adds its case the first time, and fills in the cover and the
     * J-card colours once the artwork has arrived. Cheap to call repeatedly.
     */
    fun note(source: Source, album: String, artist: String, artwork: Bitmap?, stripe: Int, stripe2: Int) {
        if (album.isBlank()) return
        val id = idOf(source, album)
        if (id == notedId && artwork === notedArt && stripe == notedStripe) return
        val firstNote = id != notedId
        notedId = id
        notedArt = artwork
        notedStripe = stripe
        val now = System.currentTimeMillis()
        val existing = _cases.value.firstOrNull { it.id == id }
        val updated = if (existing == null) {
            TapeCase(id, source, album.trim(), artist.trim(), stripe, stripe2, hasCover = false, addedAt = now, lastPlayedAt = now)
        } else {
            existing.copy(
                artist = existing.artist.ifBlank { artist.trim() },
                stripe = stripe,
                stripe2 = stripe2,
                lastPlayedAt = if (firstNote) now else existing.lastPlayedAt,
            )
        }
        // Newest tape at the front of the shelf.
        _cases.update { list -> if (list.none { it.id == id }) listOf(updated) + list else list.map { if (it.id == id) updated.copy(hasCover = it.hasCover) else it } }
        scope.launch {
            if (artwork != null && saveCover(id, artwork)) {
                covers.remove(id)
                _cases.update { list -> list.map { if (it.id == id) it.copy(hasCover = true) else it } }
            }
            write(_cases.value)
        }
    }

    fun remove(id: String) {
        _cases.update { list -> list.filterNot { it.id == id } }
        if (notedId == id) notedId = null
        covers.remove(id)
        scope.launch {
            runCatching { coverFile(id).delete() }
            write(_cases.value)
        }
    }

    suspend fun cover(case: TapeCase): ImageBitmap? {
        if (!case.hasCover) return null
        covers.get(case.id)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(coverFile(case.id).path)?.asImageBitmap() }.getOrNull()
        }?.also { covers.put(case.id, it) }
    }

    fun release() = scope.cancel()

    private fun coverFile(id: String) = File(dir, "$id.jpg")

    private fun saveCover(id: String, artwork: Bitmap): Boolean = runCatching {
        if (artwork.isRecycled) return false
        val edge = 320
        val scale = edge.toFloat() / maxOf(artwork.width, artwork.height).coerceAtLeast(1)
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(artwork, (artwork.width * scale).toInt().coerceAtLeast(1), (artwork.height * scale).toInt().coerceAtLeast(1), true)
        } else {
            artwork
        }
        coverFile(id).outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }.getOrDefault(false)

    private fun read(): List<TapeCase> = runCatching {
        if (!index.exists()) return emptyList()
        val array = JSONArray(index.readText())
        (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            val source = runCatching { Source.valueOf(o.getString("source")) }.getOrNull() ?: return@mapNotNull null
            TapeCase(
                id = o.getString("id"),
                source = source,
                album = o.getString("album"),
                artist = o.optString("artist"),
                stripe = o.getInt("stripe"),
                stripe2 = o.getInt("stripe2"),
                hasCover = o.optBoolean("hasCover"),
                addedAt = o.optLong("addedAt"),
                lastPlayedAt = o.optLong("lastPlayedAt"),
            )
        }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun write(list: List<TapeCase>) {
        runCatching {
            val array = JSONArray()
            list.forEach { c ->
                array.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("source", c.source.name)
                        .put("album", c.album)
                        .put("artist", c.artist)
                        .put("stripe", c.stripe)
                        .put("stripe2", c.stripe2)
                        .put("hasCover", c.hasCover)
                        .put("addedAt", c.addedAt)
                        .put("lastPlayedAt", c.lastPlayedAt),
                )
            }
            val tmp = File(dir, "cases.json.tmp")
            tmp.writeText(array.toString())
            tmp.renameTo(index)
        }
    }
}
