package com.controlpad.app

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.ProgressDialog
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
 * 저장소의 release/version.json 과 release/ControlPad.apk 를 내려받는다.
 */
object Updater {
    private const val BASE =
        "https://raw.githubusercontent.com/jkyou1127-ship-it/control/claude/vibrant-ramanujan-pdzsly/release/"
    private const val ACTION = "com.controlpad.app.INSTALL_STATUS"
    private var checkedThisRun = false
    private var receiverRegistered = false

    class Info(val code: Int, val name: String, val notes: String)

    fun parse(json: String): Info {
        val o = JSONObject(json)
        return Info(o.getInt("versionCode"), o.optString("versionName"), o.optString("notes"))
    }

    fun installedCode(ctx: Context): Int =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()

    fun installedName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""

    /** 앱을 켤 때 한 번만 조용히 확인한다. 새 버전이 없거나 실패하면 아무것도 띄우지 않는다. */
    fun autoCheck(a: Activity) {
        if (checkedThisRun) return
        checkedThisRun = true
        check(a, silent = true)
    }

    fun check(a: Activity, silent: Boolean = false) {
        Thread {
            val info = runCatching { parse(String(fetch(BASE + "version.json"))) }.getOrNull()
            a.runOnUiThread {
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                when {
                    info == null -> if (!silent) toast(a, "업데이트를 확인하지 못했습니다. 인터넷 연결을 확인하세요")
                    info.code <= installedCode(a) -> if (!silent) toast(a, "최신 버전입니다 (${installedName(a)})")
                    else -> AlertDialog.Builder(a)
                        .setTitle("새 버전 ${info.name}")
                        .setMessage(info.notes.ifEmpty { "새 버전이 있습니다." } + "\n\n지금 업데이트할까요?")
                        .setPositiveButton("업데이트") { _, _ -> download(a) }
                        .setNegativeButton("나중에", null)
                        .show()
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

    @Suppress("DEPRECATION")
    private fun download(a: Activity) {
        val progress = ProgressDialog(a).apply {
            setMessage("내려받는 중…"); setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100; setCancelable(false); show()
        }
        Thread {
            val result = runCatching {
                val apk = fetch(BASE + "ControlPad.apk") { p -> a.runOnUiThread { progress.progress = p } }
                install(a.applicationContext, apk)
            }
            a.runOnUiThread {
                runCatching { progress.dismiss() }
                if (result.isFailure) toast(a, "업데이트에 실패했습니다: " + (result.exceptionOrNull()?.message ?: ""))
            }
        }.start()
    }

    /** 시스템 설치 화면을 띄운다. 처음에는 '이 출처 허용'을 물을 수 있다. */
    private fun install(ctx: Context, apk: ByteArray) {
        registerReceiver(ctx)
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
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
