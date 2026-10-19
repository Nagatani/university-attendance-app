package jp.ac.university.attendance.data.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MIFARE Classic セクター認証に使用する鍵種別
 */
enum class MifareKeyType(val displayName: String) {
    KEY_A("Key A"),
    KEY_B("Key B")
}

/**
 * 16バイトブロック内の学籍番号データの格納エンコーディング形式
 */
enum class StudentIdEncoding(val displayName: String, val description: String) {
    ASCII_UTF8(
        displayName = "ASCII / UTF-8 文字列",
        description = "16バイト内の指定範囲を文字コード（例: '24B10234'）として解釈します（最も一般的）"
    ),
    HEX_STRING(
        displayName = "HEX（16進数表記）",
        description = "指定バイト範囲をそのまま16進数文字列（例: '0123456789'）として抽出します"
    ),
    BCD_DECIMAL(
        displayName = "BCD（二進化十進数）",
        description = "上位/下位4ビットずつを10進数桁として解釈し、パディング(F等)を除去します"
    )
}

/**
 * MIFARE Classic 学生証読み取り設定
 */
data class MifareConfig(
    /** 読み取り対象セクター番号 (1K: 0〜15, 4K: 0〜39) */
    val sectorIndex: Int = 1,
    /** セクター内のデータブロック番号 (通常 0〜2 ※ブロック3はセクタートレーラ) */
    val blockIndexInSector: Int = 0,
    /** 認証鍵種別 (Key A または Key B) */
    val keyType: MifareKeyType = MifareKeyType.KEY_A,
    /** 6バイト鍵の16進数文字列 (12文字, 例: "FFFFFFFFFFFF") */
    val keyHex: String = "FFFFFFFFFFFF",
    /** 学籍番号データの格納形式 */
    val encoding: StudentIdEncoding = StudentIdEncoding.ASCII_UTF8,
    /** 16バイトブロック内の開始バイト位置 (0〜15) */
    val byteOffset: Int = 0,
    /** 読み取るバイト長 (1〜16) */
    val byteLength: Int = 10,
    /** NULL文字(0x00)や前後の空白・制御文字を自動トリムするか */
    val trimNullAndSpaces: Boolean = true,
    /** 抽出用の正規表現（空欄の場合はそのまま使用。グループ1があればグループ1、なければマッチ全体を使用） */
    val validationRegex: String = "",
    /** テスト用フォールバック: MIFARE Classic非対応タグや未フォーマットカードの場合にカードUIDを学籍番号代わりに使うか */
    val allowUidFallback: Boolean = false,
    /** 出席受付モード解除・鍵設定保護用の暗証番号（空欄の場合はPINロックなし） */
    val kioskPin: String = ""
) {
    /**
     * 12桁の16進数キー文字列を6バイトのByteArrayに変換する。不正な場合はnullを返す。
     */
    fun parseKeyBytes(): ByteArray? {
        val cleaned = keyHex.trim().uppercase()
        if (cleaned.length != 12 || !cleaned.all { it in '0'..'9' || it in 'A'..'F' }) {
            return null
        }
        return ByteArray(6) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}

/**
 * CSVエクスポート・音声フィードバック設定
 */
data class ExportConfig(
    /** 所定のエクスポート先フォルダURI（SAF OpenDocumentTree で選択された永続化URI） */
    val defaultDirectoryUri: String = "",
    /** Excelで文字化けしないようUTF-8 BOM (\uFEFF) を付与するか */
    val includeUtf8Bom: Boolean = true,
    /** カード読み取り時に効果音（ピピッ等）を鳴らすか */
    val soundEnabled: Boolean = true
)

/**
 * MIFARE Classic鍵情報およびエクスポート設定を永続化するリポジトリ
 */
class MifareSettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _mifareConfig = MutableStateFlow(loadMifareConfig())
    val mifareConfig: StateFlow<MifareConfig> = _mifareConfig.asStateFlow()

    private val _exportConfig = MutableStateFlow(loadExportConfig())
    val exportConfig: StateFlow<ExportConfig> = _exportConfig.asStateFlow()

    private fun loadMifareConfig(): MifareConfig {
        val keyTypeName = prefs.getString(KEY_MIFARE_KEY_TYPE, MifareKeyType.KEY_A.name)
            ?: MifareKeyType.KEY_A.name
        val encodingName = prefs.getString(KEY_MIFARE_ENCODING, StudentIdEncoding.ASCII_UTF8.name)
            ?: StudentIdEncoding.ASCII_UTF8.name

        return MifareConfig(
            sectorIndex = prefs.getInt(KEY_SECTOR_INDEX, 1).coerceIn(0, 39),
            blockIndexInSector = prefs.getInt(KEY_BLOCK_INDEX, 0).coerceIn(0, 14),
            keyType = runCatching { MifareKeyType.valueOf(keyTypeName) }.getOrDefault(MifareKeyType.KEY_A),
            keyHex = prefs.getString(KEY_HEX_VALUE, "FFFFFFFFFFFF") ?: "FFFFFFFFFFFF",
            encoding = runCatching { StudentIdEncoding.valueOf(encodingName) }.getOrDefault(StudentIdEncoding.ASCII_UTF8),
            byteOffset = prefs.getInt(KEY_BYTE_OFFSET, 0).coerceIn(0, 15),
            byteLength = prefs.getInt(KEY_BYTE_LENGTH, 10).coerceIn(1, 16),
            trimNullAndSpaces = prefs.getBoolean(KEY_TRIM_PADDING, true),
            validationRegex = prefs.getString(KEY_VALIDATION_REGEX, "") ?: "",
            allowUidFallback = prefs.getBoolean(KEY_ALLOW_UID_FALLBACK, false),
            kioskPin = prefs.getString(KEY_KIOSK_PIN, "") ?: ""
        )
    }

    private fun loadExportConfig(): ExportConfig {
        return ExportConfig(
            defaultDirectoryUri = prefs.getString(KEY_DEFAULT_DIR_URI, "") ?: "",
            includeUtf8Bom = prefs.getBoolean(KEY_INCLUDE_BOM, true),
            soundEnabled = prefs.getBoolean(KEY_SOUND_ENABLED, true)
        )
    }

    fun updateMifareConfig(newConfig: MifareConfig) {
        val safeOffset = newConfig.byteOffset.coerceIn(0, 15)
        val safeLength = newConfig.byteLength.coerceIn(1, 16 - safeOffset)
        val normalized = newConfig.copy(
            sectorIndex = newConfig.sectorIndex.coerceIn(0, 39),
            blockIndexInSector = newConfig.blockIndexInSector.coerceIn(0, 14),
            keyHex = newConfig.keyHex.trim().uppercase(),
            byteOffset = safeOffset,
            byteLength = safeLength
        )
        prefs.edit()
            .putInt(KEY_SECTOR_INDEX, normalized.sectorIndex)
            .putInt(KEY_BLOCK_INDEX, normalized.blockIndexInSector)
            .putString(KEY_MIFARE_KEY_TYPE, normalized.keyType.name)
            .putString(KEY_HEX_VALUE, normalized.keyHex)
            .putString(KEY_MIFARE_ENCODING, normalized.encoding.name)
            .putInt(KEY_BYTE_OFFSET, normalized.byteOffset)
            .putInt(KEY_BYTE_LENGTH, normalized.byteLength)
            .putBoolean(KEY_TRIM_PADDING, normalized.trimNullAndSpaces)
            .putString(KEY_VALIDATION_REGEX, normalized.validationRegex)
            .putBoolean(KEY_ALLOW_UID_FALLBACK, normalized.allowUidFallback)
            .putString(KEY_KIOSK_PIN, normalized.kioskPin)
            .apply()
        _mifareConfig.value = normalized
    }

    fun updateExportConfig(newConfig: ExportConfig) {
        prefs.edit()
            .putString(KEY_DEFAULT_DIR_URI, newConfig.defaultDirectoryUri)
            .putBoolean(KEY_INCLUDE_BOM, newConfig.includeUtf8Bom)
            .putBoolean(KEY_SOUND_ENABLED, newConfig.soundEnabled)
            .apply()
        _exportConfig.value = newConfig
    }

    companion object {
        private const val PREFS_NAME = "university_attendance_mifare_prefs"

        private const val KEY_SECTOR_INDEX = "sector_index"
        private const val KEY_BLOCK_INDEX = "block_index"
        private const val KEY_MIFARE_KEY_TYPE = "mifare_key_type"
        private const val KEY_HEX_VALUE = "key_hex_value"
        private const val KEY_MIFARE_ENCODING = "mifare_encoding"
        private const val KEY_BYTE_OFFSET = "byte_offset"
        private const val KEY_BYTE_LENGTH = "byte_length"
        private const val KEY_TRIM_PADDING = "trim_padding"
        private const val KEY_VALIDATION_REGEX = "validation_regex"
        private const val KEY_ALLOW_UID_FALLBACK = "allow_uid_fallback"
        private const val KEY_KIOSK_PIN = "kiosk_pin"

        private const val KEY_DEFAULT_DIR_URI = "default_dir_uri"
        private const val KEY_INCLUDE_BOM = "include_utf8_bom"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
    }
}
