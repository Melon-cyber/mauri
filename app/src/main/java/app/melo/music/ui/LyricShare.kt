package app.melo.music.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import app.melo.music.MeloApplication
import app.melo.music.R
import app.melo.music.data.Track
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Draws a lyric card in the colours of the cover (1080×1350, the size stories and chats show well)
 * and opens the system share sheet with it.
 */
internal suspend fun shareLyricCard(context: Context, track: Track, text: String, palette: CoverPalette) {
    val app = context.applicationContext as MeloApplication
    val cover = if (track.artwork.isBlank()) null else runCatching {
        val request = ImageRequest.Builder(context).data(app.offline.cover(track.artwork)).allowHardware(false).size(320).build()
        (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap
    }.getOrNull()
    val file = withContext(Dispatchers.Default) {
        val bitmap = renderLyricCard(context, track, text, palette, cover)
        val dir = File(context.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        File(dir, "mauri-lyric-${System.currentTimeMillis()}.png").also { out ->
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, "${track.artist} — ${track.title}")
        clipData = ClipData.newRawUri("", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Поделиться строкой").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun renderLyricCard(context: Context, track: Track, text: String, palette: CoverPalette, cover: Bitmap?): Bitmap {
    val w = 1080; val h = 1350; val margin = 96f
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val base = palette.base.toArgb(); val glowA = palette.glowA.toArgb(); val glowB = palette.glowB.toArgb()
    canvas.drawColor(base)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = RadialGradient(w * .2f, h * .18f, w * .95f, glowA, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    paint.shader = RadialGradient(w * .85f, h * .8f, w * 1.0f, glowB, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    paint.shader = LinearGradient(0f, h * .55f, 0f, h.toFloat(), Color.TRANSPARENT, Color.argb(110, 0, 0, 0), Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    paint.shader = null

    val font = ResourcesCompat.getFont(context, R.font.manrope) ?: Typeface.DEFAULT
    val bold = Typeface.create(font, Typeface.BOLD)

    // Header: small cover, title and artist.
    val coverSize = 132f
    val coverRect = RectF(margin, margin, margin + coverSize, margin + coverSize)
    if (cover != null) {
        val save = canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(coverRect, 26f, 26f, Path.Direction.CW) })
        val side = minOf(cover.width, cover.height)
        val src = Rect((cover.width - side) / 2, (cover.height - side) / 2, (cover.width + side) / 2, (cover.height + side) / 2)
        canvas.drawBitmap(cover, src, coverRect, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restoreToCount(save)
    } else {
        paint.color = Color.argb(60, 255, 255, 255); canvas.drawRoundRect(coverRect, 26f, 26f, paint)
    }
    val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 46f; typeface = bold }
    val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(185, 255, 255, 255); textSize = 36f; typeface = font }
    val textLeft = coverRect.right + 34f
    val textWidth = w - margin - textLeft
    canvas.drawText(android.text.TextUtils.ellipsize(track.title, titlePaint, textWidth, android.text.TextUtils.TruncateAt.END).toString(), textLeft, coverRect.top + 60f, titlePaint)
    canvas.drawText(android.text.TextUtils.ellipsize(track.artist, artistPaint, textWidth, android.text.TextUtils.TruncateAt.END).toString(), textLeft, coverRect.top + 112f, artistPaint)

    // The lyric itself, as large as fits.
    val lyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create(font, Typeface.BOLD) }
    val area = w - 2 * margin
    val maxHeight = h - (coverRect.bottom + 120f) - 200f
    var size = 96f
    var layout: StaticLayout
    while (true) {
        lyricPaint.textSize = size
        layout = StaticLayout.Builder.obtain(text, 0, text.length, lyricPaint, area.toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.12f).build()
        if (layout.height <= maxHeight || size <= 44f) break
        size -= 4f
    }
    val top = coverRect.bottom + 120f + (maxHeight - layout.height) / 2f
    canvas.save(); canvas.translate(margin, top); layout.draw(canvas); canvas.restore()

    // Footer mark.
    val footer = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255); textSize = 34f; typeface = bold; letterSpacing = .12f }
    canvas.drawText("MAURI", margin, h - margin + 8f, footer)
    return bitmap
}
