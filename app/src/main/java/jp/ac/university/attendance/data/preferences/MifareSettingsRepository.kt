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
 * セクター認証に試行する1件分の鍵候補
 */
data class MifareKeyCandidate(
    /** 対象セクター番号（null の場合は全セクター共通で適用） */
    val targetSector: Int? = null,
    /** 認証鍵種別 (Key A または Key B) */
    val keyType: MifareKeyType,
    /** 12桁の16進数キー文字列（大文字正規化済み） */
    val keyHex: String,
    /** 元テキストの行番号（1始まり） */
    val sourceLine: Int = 1
) {
    fun toKeyBytes(): ByteArray = ByteArray(6) { i ->
        keyHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }

    val summaryLabel: String
        get() = buildString {
            if (targetSector != null) append("Sector $targetSector / ")
            append(keyType.displayName)
            append(" (${keyHex.take(4)}...${keyHex.takeLast(2)})")
        }
}

/**
 * 一括鍵テキストのパース結果サマリ
 */
data class BulkKeyParseSummary(
    val candidates: List<MifareKeyCandidate>,
    val errors: List<String>,
    val validLineCount: Int
) {
    val isValid: Boolean
        get() = candidates.isNotEmpty() && errors.isEmpty()
}

/**
 * 複数行のMIFARE Classic鍵指定テキストを解析するパーサ
 *
 * 対応書式（混在可能・# や // 以降はコメントとして無視）:
 * 1. `FFFFFFFFFFFF` （12桁HEXのみ：全セクター対象。オプションによりKey A/B両方または既定鍵種別を試行）
 * 2. `1:A:FFFFFFFFFFFF` または `1,B,A0A1A2A3A4A5` （セクター番号 : 鍵種別A/B/AB : 12桁HEX）
 * 3. `1:A0A1A2A3A4A5:B0B1B2B3B4B5` （セクター番号 : Key Aの12桁HEX : Key Bの12桁HEX）
 * 4. `1:FFFFFFFFFFFF` （セクター番号 : 12桁HEX）
 * 5. `A:FFFFFFFFFFFF` または `B:A0A1A2A3A4A5` （鍵種別 : 12桁HEX）
 */
object MifareKeyTextParser {

    fun isValidHex12(text: String): Boolean {
        return text.length == 12 && text.all { it in '0'..'9' || it in 'A'..'F' }
    }

    private fun cleanHexToken(token: String): String {
        return token.trim()
            .removePrefix("0x")
            .removePrefix("0X")
            .replace(" ", "")
            .replace("-", "")
            .uppercase()
    }

    private fun parseSectorToken(token: String): Int? {
        val cleaned = token.trim().uppercase()
            .removePrefix("SECTOR")
            .removePrefix("SEC")
            .removePrefix("S")
            .trim()
        val num = cleaned.toIntOrNull() ?: return null
        return if (num in 0..39) num else null
    }

    private fun parseKeyTypeToken(token: String): List<MifareKeyType>? {
        return when (token.trim().uppercase().replace(" ", "").replace("_", "")) {
            "A", "KEYA", "60", "0X60" -> listOf(MifareKeyType.KEY_A)
            "B", "KEYB", "61", "0X61" -> listOf(MifareKeyType.KEY_B)
            "AB", "A/B", "BA", "BOTH", "KEYAB", "*" -> listOf(MifareKeyType.KEY_A, MifareKeyType.KEY_B)
            else -> null
        }
    }

    fun parse(
        bulkText: String,
        defaultKeyType: MifareKeyType = MifareKeyType.KEY_A,
        tryBothKeyTypesForPlainHex: Boolean = true
    ): BulkKeyParseSummary {
        val candidates = mutableListOf<MifareKeyCandidate>()
        val errors = mutableListOf<String>()
        var validLines = 0
        var currentSectionSector: Int? = null

        val defaultKeyOrder = if (tryBothKeyTypesForPlainHex) {
            if (defaultKeyType == MifareKeyType.KEY_A) {
                listOf(MifareKeyType.KEY_A, MifareKeyType.KEY_B)
            } else {
                listOf(MifareKeyType.KEY_B, MifareKeyType.KEY_A)
            }
        } else {
            listOf(defaultKeyType)
        }

        bulkText.lines().forEachIndexed { idx, rawLine ->
            val lineNum = idx + 1
            // コメント除去 (#, //, ;)
            val withoutComment = rawLine
                .substringBefore("#")
                .substringBefore("//")
                .substringBefore(";")
                .trim()

            if (withoutComment.isEmpty()) return@forEachIndexed

            // MIFARE Classic Tool 等の "[Sector: 1]" ヘッダー記法にも対応
            if (withoutComment.startsWith("[") && withoutComment.endsWith("]")) {
                val inside = withoutComment.substring(1, withoutComment.length - 1)
                val sec = parseSectorToken(inside.substringAfterLast(":").trim())
                if (sec != null) {
                    currentSectionSector = sec
                    return@forEachIndexed
                }
            }

            // 区切り文字 (:, カンマ) があるかどうか判定
            if (!withoutComment.contains(':') && !withoutComment.contains(',')) {
                val hex = cleanHexToken(withoutComment)
                if (isValidHex12(hex)) {
                    validLines++
                    for (kt in defaultKeyOrder) {
                        candidates.add(
                            MifareKeyCandidate(
                                targetSector = currentSectionSector,
                                keyType = kt,
                                keyHex = hex,
                                sourceLine = lineNum
                            )
                        )
                    }
                } else {
                    errors.add("${lineNum}行目: 「$withoutComment」は有効な12桁の16進数キーではありません")
                }
                return@forEachIndexed
            }

            val parts = withoutComment.split(Regex("[:,]")).map { it.trim() }.filter { it.isNotEmpty() }
            when (parts.size) {
                2 -> {
                    val p0 = parts[0]
                    val p1 = parts[1]
                    val keyTypes = parseKeyTypeToken(p0)
                    val sector = parseSectorToken(p0)
                    val hex0 = cleanHexToken(p0)
                    val hex1 = cleanHexToken(p1)

                    when {
                        // A:FFFFFFFFFFFF または B:FFFFFFFFFFFF
                        keyTypes != null && isValidHex12(hex1) -> {
                            validLines++
                            for (kt in keyTypes) {
                                candidates.add(
                                    MifareKeyCandidate(
                                        targetSector = currentSectionSector,
                                        keyType = kt,
                                        keyHex = hex1,
                                        sourceLine = lineNum
                                    )
                                )
                            }
                        }
                        // 1:FFFFFFFFFFFF (セクター番号:12桁HEX)
                        sector != null && isValidHex12(hex1) -> {
                            validLines++
                            for (kt in defaultKeyOrder) {
                                candidates.add(
                                    MifareKeyCandidate(
                                        targetSector = sector,
                                        keyType = kt,
                                        keyHex = hex1,
                                        sourceLine = lineNum
                                    )
                                )
                            }
                        }
                        // KeyA(12桁HEX):KeyB(12桁HEX)
                        isValidHex12(hex0) && isValidHex12(hex1) -> {
                            validLines++
                            candidates.add(
                                MifareKeyCandidate(
                                    targetSector = currentSectionSector,
                                    keyType = MifareKeyType.KEY_A,
                                    keyHex = hex0,
                                    sourceLine = lineNum
                                )
                            )
                            candidates.add(
                                MifareKeyCandidate(
                                    targetSector = currentSectionSector,
                                    keyType = MifareKeyType.KEY_B,
                                    keyHex = hex1,
                                    sourceLine = lineNum
                                )
                            )
                        }
                        else -> {
                            errors.add("${lineNum}行目: 書式エラー「$withoutComment」（例: 1:A:FFFFFFFFFFFF または FFFFFFFFFFFF）")
                        }
                    }
                }

                3 -> {
                    val sector = parseSectorToken(parts[0])
                    val keyTypes = parseKeyTypeToken(parts[1])
                    val hex1 = cleanHexToken(parts[1])
                    val hex2 = cleanHexToken(parts[2])

                    when {
                        // 1:A:FFFFFFFFFFFF (セクター番号 : KeyA/B/AB : 12桁HEX)
                        sector != null && keyTypes != null && isValidHex12(hex2) -> {
                            validLines++
                            for (kt in keyTypes) {
                                candidates.add(
                                    MifareKeyCandidate(
                                        targetSector = sector,
                                        keyType = kt,
                                        keyHex = hex2,
                                        sourceLine = lineNum
                                    )
                                )
                            }
                        }
                        // 1:A0A1A2A3A4A5:B0B1B2B3B4B5 (セクター番号 : KeyA : KeyB)
                        sector != null && (isValidHex12(hex1) || isPlaceholderToken(parts[1])) &&
                            (isValidHex12(hex2) || isPlaceholderToken(parts[2])) &&
                            (isValidHex12(hex1) || isValidHex12(hex2)) -> {
                            validLines++
                            if (isValidHex12(hex1)) {
                                candidates.add(
                                    MifareKeyCandidate(
                                        targetSector = sector,
                                        keyType = MifareKeyType.KEY_A,
                                        keyHex = hex1,
                                        sourceLine = lineNum
                                    )
                                )
                            }
                            if (isValidHex12(hex2)) {
                                candidates.add(
                                    MifareKeyCandidate(
                                        targetSector = sector,
                                        keyType = MifareKeyType.KEY_B,
                                        keyHex = hex2,
                                        sourceLine = lineNum
                                    )
                                )
                            }
                        }
                        else -> {
                            errors.add("${lineNum}行目: 書式エラー「$withoutComment」（セクター:A/B:12桁HEX または セクター:KeyA:KeyB で指定してください）")
                        }
                    }
                }

                else -> {
                    errors.add("${lineNum}行目: 書式エラー「$withoutComment」")
                }
            }
        }

        return BulkKeyParseSummary(
            candidates = candidates,
            errors = errors,
            validLineCount = validLines
        )
    }

    private fun isPlaceholderToken(token: String): Boolean {
        val t = token.trim()
        return t == "-" || t == "*" || t.all { it == '-' }
    }
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
    /** 既定の認証鍵種別 (行にA/B指定がない場合の優先種別) */
    val keyType: MifareKeyType = MifareKeyType.KEY_A,
    /** 単一指定互換用の先頭6バイト鍵 (12文字, 例: "FFFFFFFFFFFF") */
    val keyHex: String = "FFFFFFFFFFFF",
    /**
     * 複数鍵・セクター別鍵の一括指定テキスト
     * （1行に1つの12桁HEXキー、または `セクター:A/B:12桁HEX` / `セクター:KeyA:KeyB` 形式）
     */
    val bulkKeysText: String = DEFAULT_BULK_KEYS_TEXT,
    /** 12桁HEXのみの行について、優先鍵種別で認証失敗した場合にもう一方の鍵種別(Key A↔B)も自動試行するか */
    val tryBothKeyTypesForPlainHex: Boolean = true,
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
     * 一括指定テキスト全体をパースした結果を返す
     */
    fun parseBulkKeys(): BulkKeyParseSummary {
        val textToParse = bulkKeysText.ifBlank { keyHex }
        return MifareKeyTextParser.parse(
            bulkText = textToParse,
            defaultKeyType = keyType,
            tryBothKeyTypesForPlainHex = tryBothKeyTypesForPlainHex
        )
    }

    /**
     * 指定したセクター番号 [targetSector] の認証に試行すべき鍵候補リストを優先順で返す。
     * 1. セクター番号が明示的に一致するルール (例: `1:A:xxxxxxxxxxxx`) を最優先
     * 2. セクター指定なしの共通ルール (例: `FFFFFFFFFFFF`) を記述順に試行
     */
    fun resolveKeyCandidatesForSector(targetSector: Int = sectorIndex): List<MifareKeyCandidate> {
        val summary = parseBulkKeys()
        val sectorSpecific = summary.candidates.filter { it.targetSector == targetSector }
        val wildcard = summary.candidates.filter { it.targetSector == null }
        val combined = sectorSpecific + wildcard

        // 同一 (keyType, keyHex) の重複を除去して順序を維持
        val seen = HashSet<String>()
        val deduplicated = mutableListOf<MifareKeyCandidate>()
        for (c in combined) {
            val sig = "${c.keyType.name}:${c.keyHex}"
            if (seen.add(sig)) {
                deduplicated.add(c)
            }
        }
        return deduplicated
    }

    /**
     * 先頭の第1候補キーを6バイトのByteArrayとして返す（単一キーAPI・後方互換用）
     */
    fun parseKeyBytes(): ByteArray? {
        val firstCandidate = resolveKeyCandidatesForSector(sectorIndex).firstOrNull()
        if (firstCandidate != null) {
            return firstCandidate.toKeyBytes()
        }
        val cleaned = keyHex.trim().uppercase()
        if (!MifareKeyTextParser.isValidHex12(cleaned)) return null
        return ByteArray(6) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    companion object {
        const val DEFAULT_BULK_KEYS_TEXT =
            "# 1行に1件ずつ鍵を指定（#以降はコメント）\n" +
            "# 書式例1: FFFFFFFFFFFF (12桁HEXキー)\n" +
            "# 書式例2: 1:A:FFFFFFFFFFFF (セクター:AまたはB:12桁HEX)\n" +
            "# 書式例3: 1:A0A1A2A3A4A5:B0B1B2B3B4B5 (セクター:KeyA:KeyB)\n" +
            "FFFFFFFFFFFF"
    }
}

/**
 * CSVエクスポート・音声フィードバック設定
 */
data class ExportConfig(
    /**
     * 所定のエクスポート先フォルダURI
     * - "DOWNLOADS" (デフォルト): 端末の Download/UniversityAttendance/ フォルダへ直接保存（Android Goエディションでも権限・SAF不要で確実に動作）
     * - "content://..." : SAF OpenDocumentTree で選択された任意のフォルダURI
     */
    val defaultDirectoryUri: String = DEFAULT_DOWNLOADS_TARGET,
    /** Excelで文字化けしないようUTF-8 BOM (\uFEFF) を付与するか */
    val includeUtf8Bom: Boolean = true,
    /** カード読み取り時に効果音（ピピッ等）を鳴らすか */
    val soundEnabled: Boolean = true
) {
    companion object {
        const val DEFAULT_DOWNLOADS_TARGET = "DOWNLOADS"
    }
}

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
        val legacySingleKey = prefs.getString(KEY_HEX_VALUE, "FFFFFFFFFFFF") ?: "FFFFFFFFFFFF"
        val savedBulkText = prefs.getString(KEY_BULK_KEYS_TEXT, null)
            ?.takeIf { it.isNotBlank() }
            ?: legacySingleKey

        return MifareConfig(
            sectorIndex = prefs.getInt(KEY_SECTOR_INDEX, 1).coerceIn(0, 39),
            blockIndexInSector = prefs.getInt(KEY_BLOCK_INDEX, 0).coerceIn(0, 14),
            keyType = runCatching { MifareKeyType.valueOf(keyTypeName) }.getOrDefault(MifareKeyType.KEY_A),
            keyHex = legacySingleKey,
            bulkKeysText = savedBulkText,
            tryBothKeyTypesForPlainHex = prefs.getBoolean(KEY_TRY_BOTH_KEY_TYPES, true),
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
        val savedUri = prefs.getString(KEY_DEFAULT_DIR_URI, ExportConfig.DEFAULT_DOWNLOADS_TARGET)
            ?.takeIf { it.isNotBlank() }
            ?: ExportConfig.DEFAULT_DOWNLOADS_TARGET
        return ExportConfig(
            defaultDirectoryUri = savedUri,
            includeUtf8Bom = prefs.getBoolean(KEY_INCLUDE_BOM, true),
            soundEnabled = prefs.getBoolean(KEY_SOUND_ENABLED, true)
        )
    }

    fun updateMifareConfig(newConfig: MifareConfig) {
        val safeOffset = newConfig.byteOffset.coerceIn(0, 15)
        val safeLength = newConfig.byteLength.coerceIn(1, 16 - safeOffset)
        val primaryCandidate = newConfig.resolveKeyCandidatesForSector(newConfig.sectorIndex).firstOrNull()
        val normalized = newConfig.copy(
            sectorIndex = newConfig.sectorIndex.coerceIn(0, 39),
            blockIndexInSector = newConfig.blockIndexInSector.coerceIn(0, 14),
            keyType = primaryCandidate?.keyType ?: newConfig.keyType,
            keyHex = primaryCandidate?.keyHex ?: newConfig.keyHex.trim().uppercase(),
            byteOffset = safeOffset,
            byteLength = safeLength
        )
        prefs.edit()
            .putInt(KEY_SECTOR_INDEX, normalized.sectorIndex)
            .putInt(KEY_BLOCK_INDEX, normalized.blockIndexInSector)
            .putString(KEY_MIFARE_KEY_TYPE, normalized.keyType.name)
            .putString(KEY_HEX_VALUE, normalized.keyHex)
            .putString(KEY_BULK_KEYS_TEXT, normalized.bulkKeysText)
            .putBoolean(KEY_TRY_BOTH_KEY_TYPES, normalized.tryBothKeyTypesForPlainHex)
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
        private const val KEY_BULK_KEYS_TEXT = "bulk_keys_text"
        private const val KEY_TRY_BOTH_KEY_TYPES = "try_both_key_types"
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
