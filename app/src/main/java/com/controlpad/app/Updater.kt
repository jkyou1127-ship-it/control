package com.controlpad.app

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 앱 안에서 새 버전을 확인하고 설치한다.
 * 채널이 둘이다.
 *   알파: release/alpha/ — 가장 최신 빌드가 바로 올라온다.
 *   정식: release/ — 한 번 전의 빌드. 새 빌드는 다음 빌드가 나올 때 정식으로 넘어온다.
 * 각 폴더의 version.json 과 ControlPad.apk 를 내려받는다.
 */
object Updater {
    const val BASE =
        "https://raw.githubusercontent.com/jkyou1127-ship-it/control/claude/vibrant-ramanujan-pdzsly/release/"
    const val RECEIVER_URL = BASE + "ControlPadReceiver.exe"
    private const val ACTION = "com.controlpad.app.INSTALL_STATUS"
    private const val RECHECK_MS = 10 * 60 * 1000L
    private var lastCheck = 0L

    var alpha: Boolean
        get() = Link.prefs.getBoolean("alpha", false)
        set(on) = Link.prefs.edit().putBoolean("alpha", on).apply()

    /** 켜져 있으면 앱을 켤 때 새 버전을 묻지 않고 바로 내려받아 설치한다. */
    var auto: Boolean
        get() = Link.prefs.getBoolean("autoUpdate", true)
        set(on) = Link.prefs.edit().putBoolean("autoUpdate", on).apply()

    private fun base(alpha: Boolean) = if (alpha) BASE + "alpha/" else BASE
    private var receiverRegistered = false

    /**
     * name 은 코드명(versionName, 예: hd12): 버전을 확인할 때(업데이트 비교, 평가단 빌드 요청) 쓴다.
     * build 는 빌드 번호(예: NWE 01): 화면에 보여 줄 때 쓴다. 옛 버전 정보에는 없을 수 있다.
     */
    class Info(val code: Int, val name: String, val notes: String, val build: String = "") {
        /** 화면에 보여 줄 이름: 빌드 번호가 있으면 '빌드 번호 (코드명)'. */
        val label: String get() = if (build.isEmpty()) name else "$build ($name)"
    }

    /** 이 앱의 빌드 번호(화면 표시용). 코드명은 versionName 이다. publish.sh 가 이 값을 version.json 에 적는다. */
    const val BUILD = "PIV"

    fun parse(json: String): Info {
        val o = JSONObject(json)
        return Info(o.getInt("versionCode"), o.optString("versionName"), o.optString("notes"), o.optString("build"))
    }

    /** 지금 설치된 앱을 화면에 보여 줄 이름: 'NWE 01 (hd12)'. */
    fun installedLabel(ctx: Context): String = "$BUILD (${installedName(ctx)})"

    fun installedCode(ctx: Context): Int =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()

    fun installedName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""

    /**
     * 목록 화면이 보일 때마다 조용히 확인한다(10분에 한 번까지). 새 버전이 없거나 실패하면 아무것도 띄우지 않는다.
     * 앱을 닫아도 프로세스는 오래 살아 있으므로, 프로세스당 한 번만 확인하면 새 버전을 한참 놓친다.
     */
    fun autoCheck(a: Activity) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastCheck != 0L && now - lastCheck < RECHECK_MS) return
        lastCheck = now
        check(a, silent = true)
    }

    /** 그 채널의 최신 버전 정보(받지 못하면 null). 화면 밖 스레드에서 부른다. */
    fun latest(alpha: Boolean): Info? = runCatching { parse(String(fetch(base(alpha) + "version.json"))) }.getOrNull()

    fun check(a: Activity, silent: Boolean = false) {
        val alpha = alpha
        Thread {
            val info = runCatching { parse(String(fetch(base(alpha) + "version.json"))) }.getOrNull()
            a.runOnUiThread {
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                when {
                    info == null -> if (!silent) toast(a, "업데이트를 확인하지 못했습니다. 인터넷 연결을 확인하세요")
                    info.code <= installedCode(a) -> if (!silent) toast(a, "최신 버전입니다 (${installedLabel(a)})")
                    silent && auto -> {
                        toast(a, "새 버전 ${info.label}을(를) 내려받아 설치합니다")
                        download(a, alpha)
                    }
                    else -> UpdateDialog.show(a, info, alpha) { onProgress, onDone -> download(a, alpha, onProgress, onDone) }
                }
            }
        }.start()
    }

    private fun fetch(url: String, onProgress: (Int) -> Unit = {}): ByteArray {
        // 캐시된 옛 파일을 받지 않도록 주소에 시각을 붙인다.
        val c = URL(url + "?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 20000
        try {
            if (c.responseCode != 200) error("HTTP " + c.responseCode)
            val total = c.contentLength
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            c.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (total > 0) onProgress(out.size() * 100 / total)
                }
            }
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    /** 새 APK 를 받아 설치한다. 진행률(0~100)과 끝(실패하면 이유)을 화면 스레드로 알려 준다. */
    private fun download(a: Activity, alpha: Boolean, onProgress: (Int) -> Unit = {}, onDone: (String?) -> Unit = {}) {
        Thread {
            val result = runCatching {
                val apk = fetch(base(alpha) + "ControlPad.apk") { p -> a.runOnUiThread { onProgress(p) } }
                install(a.applicationContext, apk)
            }
            a.runOnUiThread {
                val error = result.exceptionOrNull()?.let { it.message ?: "알 수 없는 오류" }
                onDone(error)
                if (error != null) toast(a, "업데이트에 실패했습니다: $error")
            }
        }.start()
    }

    /**
     * 시스템 설치 화면을 띄운다. 처음에는 '이 출처 허용'을 물을 수 있다.
     * 안드로이드 12 이상에서는 이 앱이 스스로 설치한 적이 있으면(두 번째 업데이트부터) 확인 없이 설치된다.
     */
    private fun install(ctx: Context, apk: ByteArray) {
        registerReceiver(ctx)
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("ControlPad.apk", 0, apk.size.toLong()).use { out ->
                out.write(apk)
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, id, Intent(ACTION).setPackage(ctx.packageName), flags)
            session.commit(pi.intentSender)
        }
    }

    private fun registerReceiver(ctx: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                        runCatching { c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    PackageInstaller.STATUS_SUCCESS -> {}
                    PackageInstaller.STATUS_FAILURE_ABORTED -> {}   // 사용자가 취소
                    else -> toast(c, "설치하지 못했습니다 ($status): " +
                        (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""))
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(receiver, IntentFilter(ACTION))
        }
    }

    private fun toast(c: Context, text: String) = Toast.makeText(c, text, Toast.LENGTH_LONG).show()
}
