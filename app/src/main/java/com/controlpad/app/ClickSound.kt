package com.controlpad.app

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File

/**
 * '누를 때 소리'의 클릭 소리. 앱이 직접 만든 짧은 소리를 SoundPool 로 낸다.
 * (시스템의 키 클릭 효과음은 기기 설정에서 '터치 소리'가 꺼져 있으면 나지 않아서 쓰지 않는다.)
 */
object ClickSound {
    private var pool: SoundPool? = null
    private var sound = 0
    @Volatile private var ready = false

    /** 실행 화면을 열 때 한 번 준비한다. 소리 파일을 만들고 불러오는 데 잠깐 걸린다. */
    fun prepare(ctx: Context) {
        if (pool != null) return
        val file = File(ctx.cacheDir, "click.wav")
        if (!file.exists() || file.length() == 0L) file.writeBytes(Wav.click())
        pool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build().also { p ->
                p.setOnLoadCompleteListener { _, _, status -> ready = status == 0 }
                sound = p.load(file.path, 1)
            }
    }

    fun play() {
        if (ready) pool?.play(sound, 0.8f, 0.8f, 1, 0, 1f)
    }
}

/** 소리 파일 만들기(16비트 모노 WAV). */
object Wav {
    /** 짧게 사라지는 클릭 소리. */
    fun click(sampleRate: Int = 44100, millis: Int = 25, hz: Double = 2200.0): ByteArray {
        val n = sampleRate * millis / 1000
        val pcm = ShortArray(n) { i ->
            val t = i.toDouble() / sampleRate
            val fade = Math.exp(-i * 6.0 / n)               // 빠르게 줄어드는 소리
            (Math.sin(2 * Math.PI * hz * t) * fade * 0.7 * Short.MAX_VALUE).toInt().toShort()
        }
        return encode(pcm, sampleRate)
    }

    fun encode(pcm: ShortArray, sampleRate: Int): ByteArray {
        val data = pcm.size * 2
        val b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)          // PCM, 모노
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        for (s in pcm) b.putShort(s)
        return b.array()
    }
}
