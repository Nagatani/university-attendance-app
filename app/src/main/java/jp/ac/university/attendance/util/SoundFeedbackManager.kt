package jp.ac.university.attendance.util

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * 学生証タッチ時の音声フィードバック（成功・重複・エラー）を再生するヘルパー
 */
object SoundFeedbackManager {

    /**
     * 出席登録成功音（明るいピピッ音）
     */
    fun playSuccessTone(enabled: Boolean) {
        if (!enabled) return
        runCatching {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 180)
        }
    }

    /**
     * 重複タッチ通知音（中音ビープ）
     */
    fun playDuplicateTone(enabled: Boolean) {
        if (!enabled) return
        runCatching {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 220)
        }
    }

    /**
     * 認証失敗・読み取りエラー音（警告音）
     */
    fun playErrorTone(enabled: Boolean) {
        if (!enabled) return
        runCatching {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 95)
            toneGen.startTone(ToneGenerator.TONE_PROP_NACK, 300)
        }
    }
}
