package com.controlpad.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * 카메라 앱이 찍은 사진을 넣을 자리(앱 캐시의 photo.jpg)를 빌려 주는 작은 제공자.
 * AndroidX 없이 카메라 앱에 '여기에 저장해 주세요' 주소를 넘기려고 쓴다.
 */
class PhotoProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.controlpad.app.photo"
        fun file(ctx: Context) = File(ctx.cacheDir, "photo.jpg")
        fun uri(): Uri = Uri.parse("content://$AUTHORITY/photo.jpg")
    }

    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/jpeg"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = file(context!!)
        if (mode.contains('w')) f.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = file(context!!)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?) = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
}

/**
 * 그림 · 사진을 컨트롤러로 바꾸는 화면(IMCP).
 *  1 사진 찍기 · 고르기 → 2 편집(자르기 · 회전 · 밝기 · 대비) → 3 버튼 확인(도형마다 버튼, 눌러서 빼기/넣기)
 *  → 4 컨트롤러로 만들기('make by IMCP' 워터마크) → 5 편집 화면(모든 편집 기능) → 6 저장 후 사용
 */
class PhotoPadActivity : BaseActivity() {
    private var photo: Bitmap? = null          // 원본(사진 방향 맞춤까지)
    private var edited: Bitmap? = null         // 2단계를 거친 사진
    private var step = 1
    private lateinit var body: FrameLayout
    private lateinit var steps: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(Ui.header(this, "사진으로 컨트롤러 만들기", "그림이나 사진 속 도형이 그대로 버튼이 됩니다"))
        steps = LinearLayout(this)
        root.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(steps) })
        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        setContentView(root)
        showStep(1)
    }

    private fun showSteps() {
        steps.removeAllViews()
        listOf("1 사진", "2 편집", "3 버튼 확인", "4 컨트롤러로").forEachIndexed { i, name ->
            val on = i + 1 == step
            val done = i + 1 < step
            steps.addView(TextView(this).apply {
                text = (if (done) "✓ " else "") + name; textSize = 13f
                typeface = if (on) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                setTextColor(if (on) Ui.onAccent() else if (done) Ui.accent() else Ui.muted())
                background = Ui.rounded(this@PhotoPadActivity, if (on) Ui.accent() else Ui.surface(), 16, if (on) 0 else Ui.line())
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        }
    }

    private fun showStep(n: Int) {
        step = n
        showSteps()
        body.removeAllViews()
        when (n) {
            1 -> body.addView(stepPick())
            2 -> body.addView(stepEdit())
            3 -> body.addView(stepShapes())
        }
        Ui.restyle(body)
    }

    // ── 1. 사진 찍기 · 고르기 ──

    private fun stepPick(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(TextView(this@PhotoPadActivity).apply {
            text = "종이에 버튼 모양을 그려 찍거나, 갖고 있는 그림 · 사진을 고르세요.\n진한 색으로 또렷하게 그릴수록 버튼을 잘 찾습니다."
            textSize = 15f; gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f)
        })
        val row = LinearLayout(this@PhotoPadActivity).apply { setPadding(0, dp(18), 0, 0); gravity = Gravity.CENTER }
        row.addView(Button(this@PhotoPadActivity).apply {
            text = "📷  사진 찍기"; tag = Ui.PRIMARY
            setOnClickListener { takePhoto() }
        })
        row.addView(Button(this@PhotoPadActivity).apply {
            text = "🖼  앨범에서 고르기"
            setOnClickListener {
                runCatching {
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_PICK)
                }.onFailure { toast("사진을 고를 수 있는 앱이 없습니다") }
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        addView(row)
    }

    private fun takePhoto() {
        PhotoProvider.file(this).delete()
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, PhotoProvider.uri())
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = android.content.ClipData.newRawUri("photo", PhotoProvider.uri())
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_CAMERA)
        }.onFailure { toast("카메라 앱을 열 수 없습니다") }
    }

    @Deprecated("startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = when (requestCode) {
            REQ_CAMERA -> Uri.fromFile(PhotoProvider.file(this))
            REQ_PICK -> data?.data
            else -> null
        } ?: return
        val bmp = decode(uri)
        if (bmp == null) { toast("사진을 읽지 못했습니다"); return }
        photo = bmp; edited = null
        showStep(2)
    }

    /** 사진을 1600px 이하로 줄여 읽고, 찍은 방향(EXIF)대로 돌린다. */
    private fun decode(uri: Uri): Bitmap? = runCatching {
        fun open() = if (uri.scheme == "file") File(uri.path!!).inputStream() else contentResolver.openInputStream(uri)!!
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        var bmp = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }!!
        val degrees = runCatching {
            open().use {
                when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }.getOrDefault(0f)
        if (degrees != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
        bmp
    }.getOrNull()

    // ── 2. 편집: 자르기 · 회전 · 밝기 · 대비 ──

    private var rotation = 0
    private var brightness = 0          // -100 ~ 100
    private var contrast = 100          // 50 ~ 200 (%)

    private fun stepEdit(): View {
        val src = photo ?: return TextView(this).apply { text = "사진이 없습니다" }
        val crop = CropView(this, rotated(src))
        crop.filter = colorFilter()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply { text = "모서리를 끌어 자를 부분을 정하세요. 가운데를 끌면 옮겨집니다."; textSize = 13f; setTextColor(Ui.muted()) })
        box.addView(crop, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
        fun slider(label: String, min: Int, max: Int, value: Int, set: (Int) -> Unit) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply { text = label; textSize = 13f }, LinearLayout.LayoutParams(dp(56), -2))
            row.addView(SeekBar(this).apply {
                this.max = max - min; progress = value - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { set(p + min); crop.filter = colorFilter(); crop.invalidate() }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(row)
        }
        slider("밝기", -100, 100, brightness) { brightness = it }
        slider("대비", 50, 200, contrast) { contrast = it }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        row.addView(Button(this).apply { text = "⟵ 다시 고르기"; setOnClickListener { showStep(1) } })
        row.addView(Button(this).apply {
            text = "⟳ 90° 회전"
            setOnClickListener { rotation = (rotation + 90) % 360; crop.setBitmap(rotated(src)) }
        })
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(Button(this).apply {
            text = "다음: 버튼 찾기 →"; tag = Ui.PRIMARY
            setOnClickListener {
                edited = applyEdits(rotated(src), crop.cropRect())
                showStep(3)
            }
        })
        box.addView(row)
        return box
    }

    private fun rotated(src: Bitmap): Bitmap =
        if (rotation == 0) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)

    private fun colorFilter(): ColorMatrixColorFilter {
        val k = contrast / 100f
        val t = (1 - k) * 128 + brightness * 1.28f
        return ColorMatrixColorFilter(ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, t, 0f, k, 0f, 0f, t, 0f, 0f, k, 0f, t, 0f, 0f, 0f, 1f, 0f)))
    }

    /** 자르고, 밝기 · 대비를 입힌 새 사진(1280px 이하). */
    private fun applyEdits(src: Bitmap, r: RectF): Bitmap {
        val l = (r.left * src.width).toInt().coerceIn(0, src.width - 1)
        val t = (r.top * src.height).toInt().coerceIn(0, src.height - 1)
        val w = (r.width() * src.width).toInt().coerceIn(1, src.width - l)
        val h = (r.height() * src.height).toInt().coerceIn(1, src.height - t)
        val scale = minOf(1f, 1280f / maxOf(w, h))
        val out = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, android.graphics.Rect(l, t, l + w, t + h), android.graphics.Rect(0, 0, out.width, out.height),
            Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = colorFilter() })
        return out
    }

    // ── 3. 버튼 확인 ──

    private var sensitivity = 0
    private var minSize = 4                 // 0.1% 단위(4 = 0.4%)
    private var found: ShapeFinder.Result? = null
    private val off = HashSet<Int>()        // 빼기로 한 도형 번호
    private var small: Bitmap? = null

    private fun detect() {
        val img = edited ?: return
        val k = minOf(1f, 360f / maxOf(img.width, img.height))
        val s = Bitmap.createScaledBitmap(img, (img.width * k).toInt().coerceAtLeast(8), (img.height * k).toInt().coerceAtLeast(8), true)
        small = s
        val px = IntArray(s.width * s.height)
        s.getPixels(px, 0, s.width, 0, 0, s.width, s.height)
        val lum = IntArray(px.size) { val c = px[it]; (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000 }
        found = ShapeFinder.find(lum, s.width, s.height, sensitivity, minSize / 1000f)
        off.clear()
    }

    private fun stepShapes(): View {
        detect()
        val img = edited ?: return TextView(this)
        val pick = ShapePickView(this, img)
        val info = TextView(this).apply { textSize = 13f; setTextColor(Ui.muted()) }
        fun refresh() {
            pick.result = found; pick.off = off; pick.invalidate()
            val all = found?.shapes?.size ?: 0
            info.text = "찾은 도형 ${all}개 · 버튼으로 쓸 것 ${all - off.size}개 — 도형을 눌러 빼거나 다시 넣으세요. 번호 순서대로 A S D F J K L … 키가 들어갑니다."
        }
        pick.onTap = { id -> if (!off.remove(id)) off += id; refresh() }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(info)
        box.addView(pick, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
        fun slider(label: String, min: Int, max: Int, value: Int, set: (Int) -> Unit) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply { text = label; textSize = 13f }, LinearLayout.LayoutParams(dp(70), -2))
            row.addView(SeekBar(this).apply {
                this.max = max - min; progress = value - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {}
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) { set(s!!.progress + min); detect(); refresh() }
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(row)
        }
        slider("감도", -60, 60, sensitivity) { sensitivity = it }
        slider("최소 크기", 1, 30, minSize) { minSize = it }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        row.addView(Button(this).apply { text = "⟵ 편집으로"; setOnClickListener { showStep(2) } })
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(Button(this).apply {
            text = "컨트롤러로 만들기 →"; tag = Ui.PRIMARY
            setOnClickListener { askNameAndBuild() }
        })
        box.addView(row)
        refresh()
        return box
    }

    // ── 4. 컨트롤러로 만들기 ──

    private fun askNameAndBuild() {
        val r = found
        if (r == null || r.shapes.size == off.size) { toast("버튼으로 쓸 도형이 없습니다. 감도를 바꿔 보세요"); return }
        val input = android.widget.EditText(this).apply { setText("사진 컨트롤러"); setSingleLine(); selectAll() }
        AlertDialog.Builder(this).setTitle("컨트롤러 이름")
            .setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) })
            .setPositiveButton("만들기") { _, _ -> build(input.text.toString().trim().ifEmpty { "사진 컨트롤러" }) }
            .setNegativeButton("취소", null)
            .show().wide()
    }

    private fun build(name: String) {
        val img = edited ?: return
        val r = found ?: return
        step = 4; showSteps()
        // 사진이 화면 가운데에 비율 그대로 들어가는 자리(레이아웃 좌표 0~1)
        val dm = resources.displayMetrics
        val screen = maxOf(dm.widthPixels, dm.heightPixels).toFloat() / minOf(dm.widthPixels, dm.heightPixels)
        val aspect = img.width.toFloat() / img.height
        val (iw, ih) = if (aspect >= screen) 1f to screen / aspect else aspect / screen to 1f
        val ix = (1f - iw) / 2
        val iy = (1f - ih) / 2

        val buttons = ArrayList<PadButton>()
        // 바탕: 편집한 사진 그대로(누를 수 없음)
        buttons += PadButton(ix, iy, iw, ih, type = PadButton.TYPE_IMAGE, image = ImageStore.save(this, img))
        // 도형마다: 사진에서 그 도형 부분만 잘라(바깥은 투명) 버튼 면으로
        val sx = img.width.toFloat() / r.width
        val sy = img.height.toFloat() / r.height
        var index = 0
        for (s in r.shapes) {
            if (s.id in off) continue
            val l = (s.left * sx).toInt().coerceIn(0, img.width - 1)
            val t = (s.top * sy).toInt().coerceIn(0, img.height - 1)
            val w = ((s.right + 1) * sx).toInt().coerceIn(l + 1, img.width) - l
            val h = ((s.bottom + 1) * sy).toInt().coerceIn(t + 1, img.height) - t
            val face = Bitmap.createBitmap(img, l, t, w, h).copy(Bitmap.Config.ARGB_8888, true)
            val px = IntArray(w * h)
            face.getPixels(px, 0, w, 0, 0, w, h)
            for (y in 0 until h) for (x in 0 until w) {
                val qx = ((l + x) / sx).toInt().coerceIn(0, r.width - 1)
                val qy = ((t + y) / sy).toInt().coerceIn(0, r.height - 1)
                if (r.labels[qy * r.width + qx] != s.id) px[y * w + x] = 0
            }
            face.setPixels(px, 0, w, 0, 0, w, h)
            val key = ShapeFinder.keyFor(index)
            buttons += PadButton(
                ix + l.toFloat() / img.width * iw, iy + t.toFloat() / img.height * ih,
                w.toFloat() / img.width * iw, h.toFloat() / img.height * ih,
                keys = mutableListOf(key), shape = PadButton.SHAPE_FREE, image = ImageStore.save(this, face),
                color = PadButton.PALETTE[index % PadButton.PALETTE.size],
            )
            index++
        }
        val layout = PadLayout(name = name, buttons = buttons,
            watermarkUntil = Updater.installedCode(this) + Watermark.BUILDS)    // 'make by IMCP' 는 3빌드 뒤에 사라진다
        LayoutStore.save(this, layout)
        toast("컨트롤러를 만들었습니다. 편집한 뒤 저장하고 목록에서 실행하세요")
        // 5. 편집 화면: 모든 편집 기능을 쓸 수 있다
        startActivity(Intent(this, EditorActivity::class.java).putExtra("id", layout.id))
        finish()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    companion object {
        const val REQ_CAMERA = 31
        const val REQ_PICK = 32
    }
}

/** 2단계: 사진 위에 자를 칸을 그리고, 모서리 · 가운데를 끌어 바꾼다. 칸은 사진 대비 0~1. */
class CropView(ctx: Context, private var bmp: Bitmap) : View(ctx) {
    private val crop = RectF(0.05f, 0.05f, 0.95f, 0.95f)
    var filter: ColorMatrixColorFilter? = null
    private val dst = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint().apply { color = 0x99000000.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt() }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private var grab = -1                // 0~3 모서리, 4 가운데
    private var lastX = 0f
    private var lastY = 0f

    fun setBitmap(b: Bitmap) { bmp = b; crop.set(0.05f, 0.05f, 0.95f, 0.95f); invalidate() }
    fun cropRect() = RectF(crop)

    override fun onDraw(c: Canvas) {
        val k = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val w = bmp.width * k; val h = bmp.height * k
        dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        paint.colorFilter = filter
        c.drawBitmap(bmp, null, dst, paint)
        val l = dst.left + crop.left * w; val t = dst.top + crop.top * h
        val r = dst.left + crop.right * w; val b = dst.top + crop.bottom * h
        c.drawRect(dst.left, dst.top, dst.right, t, shade); c.drawRect(dst.left, b, dst.right, dst.bottom, shade)
        c.drawRect(dst.left, t, l, b, shade); c.drawRect(r, t, dst.right, b, shade)
        edge.strokeWidth = context.dp(2).toFloat()
        c.drawRect(l, t, r, b, edge)
        val hr = context.dp(9).toFloat()
        for ((x, y) in listOf(l to t, r to t, l to b, r to b)) c.drawCircle(x, y, hr, handle)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val w = dst.width(); val h = dst.height()
        if (w <= 0 || h <= 0) return true
        val nx = (e.x - dst.left) / w
        val ny = (e.y - dst.top) / h
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val near = context.dp(32) / w
                val corners = listOf(crop.left to crop.top, crop.right to crop.top, crop.left to crop.bottom, crop.right to crop.bottom)
                grab = corners.indexOfFirst { (x, y) -> kotlin.math.abs(x - nx) < near && kotlin.math.abs(y - ny) < near * w / h }
                if (grab < 0 && crop.contains(nx, ny)) grab = 4
                lastX = nx; lastY = ny
            }
            MotionEvent.ACTION_MOVE -> {
                val minS = 0.08f
                when (grab) {
                    0 -> { crop.left = nx.coerceIn(0f, crop.right - minS); crop.top = ny.coerceIn(0f, crop.bottom - minS) }
                    1 -> { crop.right = nx.coerceIn(crop.left + minS, 1f); crop.top = ny.coerceIn(0f, crop.bottom - minS) }
                    2 -> { crop.left = nx.coerceIn(0f, crop.right - minS); crop.bottom = ny.coerceIn(crop.top + minS, 1f) }
                    3 -> { crop.right = nx.coerceIn(crop.left + minS, 1f); crop.bottom = ny.coerceIn(crop.top + minS, 1f) }
                    4 -> {
                        val dx = (nx - lastX).coerceIn(-crop.left, 1f - crop.right)
                        val dy = (ny - lastY).coerceIn(-crop.top, 1f - crop.bottom)
                        crop.offset(dx, dy)
                    }
                }
                lastX = nx; lastY = ny
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> grab = -1
        }
        return true
    }
}

/** 3단계: 찾은 도형을 색칠해 보여 주고 번호를 붙인다. 도형을 누르면 빼기/넣기. */
class ShapePickView(ctx: Context, private val bmp: Bitmap) : View(ctx) {
    var result: ShapeFinder.Result? = null
        set(v) { field = v; overlay = null }
    var off: Set<Int> = emptySet()
        set(v) { field = v; overlay = null }
    var onTap: (Int) -> Unit = {}
    private var overlay: Bitmap? = null
    private val dst = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val numBack = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun makeOverlay(r: ShapeFinder.Result): Bitmap {
        val colors = PadButton.PALETTE
        val px = IntArray(r.labels.size) { i ->
            val id = r.labels[i]
            when {
                id == 0 -> 0
                id in off -> 0x55777777
                else -> colors[(id - 1) % colors.size] and 0x00FFFFFF or 0x88000000.toInt()
            }
        }
        return Bitmap.createBitmap(px, r.width, r.height, Bitmap.Config.ARGB_8888)
    }

    override fun onDraw(c: Canvas) {
        val k = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val w = bmp.width * k; val h = bmp.height * k
        dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        c.drawBitmap(bmp, null, dst, paint)
        val r = result ?: return
        val o = overlay ?: makeOverlay(r).also { overlay = it }
        c.drawBitmap(o, null, dst, null)
        num.textSize = context.dp(14).toFloat()
        for (s in r.shapes) {
            val x = dst.left + (s.cx + 0.5f) / r.width * w
            val y = dst.top + (s.cy + 0.5f) / r.height * h
            numBack.color = if (s.id in off) 0xCC555555.toInt() else 0xDD000000.toInt()
            c.drawCircle(x, y, num.textSize * 0.9f, numBack)
            num.color = if (s.id in off) 0xFFAAAAAA.toInt() else 0xFFFFFFFF.toInt()
            c.drawText(if (s.id in off) "✕" else s.id.toString(), x, y - (num.descent() + num.ascent()) / 2, num)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_UP) return true
        val r = result ?: return true
        if (!dst.contains(e.x, e.y)) return true
        val qx = ((e.x - dst.left) / dst.width() * r.width).toInt().coerceIn(0, r.width - 1)
        val qy = ((e.y - dst.top) / dst.height() * r.height).toInt().coerceIn(0, r.height - 1)
        var id = r.labels[qy * r.width + qx]
        if (id == 0) {
            // 도형 바깥을 눌렀으면 가장 가까운 번호
            id = r.shapes.minByOrNull { (it.cx - qx) * (it.cx - qx) + (it.cy - qy) * (it.cy - qy) }?.id ?: 0
        }
        if (id != 0) onTap(id)
        return true
    }
}
