package com.controlpad.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.UUID

/** 레이아웃에 넣은 사진을 앱 저장소(files/images)에 보관하고 그릴 때 꺼내 준다. */
object ImageStore {
    private const val MAX_SIDE = 1280        // 이보다 큰 사진은 줄여서 저장한다(메모리와 그리기 속도)
    private val cache = HashMap<String, Bitmap?>()

    private fun dir(ctx: Context) = File(ctx.filesDir, "images").apply { mkdirs() }

    fun get(ctx: Context, name: String): Bitmap? {
        if (!LayoutFile.validImageName(name)) return null
        return cache.getOrPut(name) { runCatching { BitmapFactory.decodeFile(File(dir(ctx), name).path) }.getOrNull() }
    }

    fun read(ctx: Context, name: String): ByteArray? =
        if (LayoutFile.validImageName(name)) runCatching { File(dir(ctx), name).readBytes() }.getOrNull() else null

    fun write(ctx: Context, name: String, bytes: ByteArray) {
        if (!LayoutFile.validImageName(name)) return
        File(dir(ctx), name).writeBytes(bytes)
        cache.remove(name)
    }

    /** 앱에서 만든 그림(투명한 부분이 있는 PNG)을 저장하고 그 이름을 돌려준다. */
    fun save(ctx: Context, bmp: Bitmap): String {
        val name = UUID.randomUUID().toString() + ".png"
        File(dir(ctx), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        cache[name] = bmp
        return name
    }

    /** 사용자가 고른 사진을 줄여서 저장하고 그 이름을 돌려준다. 읽을 수 없으면 null. */
    fun importFrom(ctx: Context, uri: Uri): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }!!
        val big = maxOf(bmp.width, bmp.height)
        if (big > MAX_SIDE) {
            val k = MAX_SIDE.toFloat() / big
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
        }
        // 투명한 부분이 있는 사진은 PNG 로, 아니면 용량이 작은 JPEG 로 저장한다.
        val png = bmp.hasAlpha()
        val name = UUID.randomUUID().toString() + if (png) ".png" else ".jpg"
        File(dir(ctx), name).outputStream().use {
            bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, it)
        }
        cache[name] = bmp
        name
    }.getOrNull()
}
