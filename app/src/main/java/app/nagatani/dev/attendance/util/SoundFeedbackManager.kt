package app.nagatani.dev.attendance.util

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * 学生証タッチ時の音声フィードバック（成功・重複・エラー）を再生するヘルパー。
 * 低スペックタブレットでの発音遅延（AudioTrack生成コスト 80〜180ms）と連続80名以上のタッチによる
 * AudioFlinger リソース枯渇を防ぐため、単一の ToneGenerator インスタンスを事前初期化して再利用する。
 */
object SoundFeedbackManager {

    @Volatile
    private var sharedToneGenerator: ToneGenerator? = null

    @Synchronized
    private fun getOrCreateGenerator(): ToneGenerator? {
        var gen = sharedToneGenerator
        if (gen == null) {
            gen = runCatching {
                ToneGenerator(AudioManager.STREAM_NOTIFICATION, 95)
            }.getOrNull()
            sharedToneGenerator = gen
        }
        return gen
    }

    /**
     * アプリ起動時や受付開始時に呼び出し、AudioTrack を事前初期化して初回タッチ時の遅延をゼロにする
     */
    fun warmUp() {
        runCatching { getOrCreateGenerator() }
    }

    private fun playToneInternal(toneType: Int, durationMs: Int) {
        val gen = getOrCreateGenerator() ?: return
        val started = runCatching {
            gen.startTone(toneType, durationMs)
        }.getOrDefault(false)

        if (!started) {
            // AudioServer再起動等で無効化されていた場合は再生成してリトライ
            synchronized(this) {
                runCatching { sharedToneGenerator?.release() }
                sharedToneGenerator = null
                getOrCreateGenerator()?.startTone(toneType, durationMs)
            }
        }
    }

    /**
     * 出席登録成功音（歯切れの良い高速ピピッ音）
     */
    fun playSuccessTone(enabled: Boolean) {
        if (!enabled) return
        playToneInternal(ToneGenerator.TONE_PROP_ACK, 140)
    }

    /**
     * 重複タッチ通知音（中音ビープ）
     */
    fun playDuplicateTone(enabled: Boolean) {
        if (!enabled) return
        playToneInternal(ToneGenerator.TONE_PROP_BEEP2, 180)
    }

    /**
     * 認証失敗・読み取りエラー音（警告音）
     */
    fun playErrorTone(enabled: Boolean) {
        if (!enabled) return
        playToneInternal(ToneGenerator.TONE_PROP_NACK, 240)
    }
}
