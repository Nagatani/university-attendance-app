package app.nagatani.dev.attendance.nfc

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import app.nagatani.dev.attendance.data.preferences.MifareConfig
import app.nagatani.dev.attendance.data.preferences.MifareKeyCandidate
import app.nagatani.dev.attendance.data.preferences.MifareKeyType
import app.nagatani.dev.attendance.data.preferences.StudentIdEncoding
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * MIFARE Classic 学生証の読み取り結果を表すシールドクラス
 */
sealed class MifareReadResult {
    abstract val cardUid: String
    abstract val timestampMillis: Long

    /**
     * セクター認証・ブロック読み取り・学籍番号抽出に成功
     */
    data class Success(
        override val cardUid: String,
        val studentId: String,
        val rawBlockBytes: ByteArray,
        val sectorIndex: Int,
        val blockIndexInSector: Int,
        val absoluteBlockIndex: Int,
        val cardTypeDescription: String,
        val isUidFallback: Boolean = false,
        override val timestampMillis: Long = System.currentTimeMillis()
    ) : MifareReadResult()

    /**
     * MIFARE Classic セクター認証失敗（鍵不一致またはアクセス権限エラー）
     */
    data class AuthFailed(
        override val cardUid: String,
        val sectorIndex: Int,
        val keyType: MifareKeyType,
        val message: String,
        override val timestampMillis: Long = System.currentTimeMillis()
    ) : MifareReadResult()

    /**
     * ブロック読み取りには成功したが、学籍番号文字列の抽出・バリデーションに失敗
     */
    data class ParseFailed(
        override val cardUid: String,
        val rawBlockBytes: ByteArray,
        val sectorIndex: Int,
        val blockIndexInSector: Int,
        val absoluteBlockIndex: Int,
        val reason: String,
        override val timestampMillis: Long = System.currentTimeMillis()
    ) : MifareReadResult()

    /**
     * MIFARE Classic 非対応カード、または設定値・通信エラー
     */
    data class Error(
        override val cardUid: String,
        val title: String,
        val message: String,
        val techList: List<String> = emptyList(),
        override val timestampMillis: Long = System.currentTimeMillis()
    ) : MifareReadResult()
}

/**
 * MIFARE Classic 学生証のセクター認証およびブロック読み取り・デコードを行うクラス
 */
object MifareClassicReader {

    /**
     * Android NFC [Tag] から [MifareConfig] の設定に従って学籍番号を読み取る
     * （バックグラウンドスレッド/ReaderModeコールバックから呼び出し可能）
     */
    fun readStudentCard(tag: Tag, config: MifareConfig): MifareReadResult {
        val cardUid = bytesToHex(tag.id, separator = "")
        val techList = tag.techList?.map { it.substringAfterLast('.') } ?: emptyList()

        // 1. 一括鍵設定テキストから当該セクター向けの鍵候補リストを取得
        val keyCandidates = config.resolveKeyCandidatesForSector(config.sectorIndex)
        if (keyCandidates.isEmpty()) {
            return MifareReadResult.Error(
                cardUid = cardUid,
                title = "MIFARE鍵設定エラー",
                message = "有効な12桁の16進数キー（6バイト）が1件も指定されていません。設定画面の鍵テキストを確認してください。",
                techList = techList
            )
        }

        // 2. MifareClassic インスタンス取得
        val mifare = MifareClassic.get(tag)
        if (mifare == null) {
            if (config.allowUidFallback && cardUid.isNotEmpty()) {
                val dummyBlock = createSimulatedBlock(cardUid)
                return MifareReadResult.Success(
                    cardUid = cardUid,
                    studentId = cardUid,
                    rawBlockBytes = dummyBlock,
                    sectorIndex = config.sectorIndex,
                    blockIndexInSector = config.blockIndexInSector,
                    absoluteBlockIndex = 0,
                    cardTypeDescription = "UIDフォールバック (${techList.joinToString(", ")})",
                    isUidFallback = true
                )
            }
            return MifareReadResult.Error(
                cardUid = cardUid,
                title = "MIFARE Classic 非対応カード",
                message = "かざされたカード（検出技術: ${techList.joinToString(", ")}）はMIFARE Classicとして認識できませんでした。",
                techList = techList
            )
        }

        return try {
            mifare.connect()

            val sectorCount = mifare.sectorCount
            if (config.sectorIndex !in 0 until sectorCount) {
                return MifareReadResult.Error(
                    cardUid = cardUid,
                    title = "セクター番号範囲外",
                    message = "指定されたセクター番号 (${config.sectorIndex}) はこのカードの範囲 (0〜${sectorCount - 1}) を超えています。",
                    techList = techList
                )
            }

            val blocksInSector = mifare.getBlockCountInSector(config.sectorIndex)
            if (config.blockIndexInSector !in 0 until blocksInSector) {
                return MifareReadResult.Error(
                    cardUid = cardUid,
                    title = "ブロック番号範囲外",
                    message = "セクター${config.sectorIndex}内のブロック番号 (${config.blockIndexInSector}) が範囲 (0〜${blocksInSector - 1}) 外です。",
                    techList = techList
                )
            }

            // 3. 候補セクター（指定セクターおよび鍵リストで明示指定されたセクター）に対して候補キーを順番に試行
            val prioritySectors = config.resolvePrioritySectors().filter { it in 0 until sectorCount }
            var matchedCandidate: MifareKeyCandidate? = null
            var matchedSector: Int = config.sectorIndex
            var attemptIndex = 0

            outerLoop@ for (sectorToTry in prioritySectors) {
                val candidatesForSector = config.resolveKeyCandidatesForSector(sectorToTry)
                for (candidate in candidatesForSector) {
                    if (attemptIndex > 0) {
                        // MIFARE Classic仕様上、認証失敗後は一度HALT状態になるため再接続してから次の鍵を試行
                        runCatching {
                            mifare.close()
                            mifare.connect()
                        }
                    }
                    attemptIndex++
                    val keyBytes = candidate.toKeyBytes()
                    val ok = when (candidate.keyType) {
                        MifareKeyType.KEY_A -> mifare.authenticateSectorWithKeyA(sectorToTry, keyBytes)
                        MifareKeyType.KEY_B -> mifare.authenticateSectorWithKeyB(sectorToTry, keyBytes)
                    }
                    if (ok) {
                        matchedCandidate = candidate
                        matchedSector = sectorToTry
                        break@outerLoop
                    }
                }
            }

            if (matchedCandidate == null) {
                return MifareReadResult.AuthFailed(
                    cardUid = cardUid,
                    sectorIndex = config.sectorIndex,
                    keyType = keyCandidates.first().keyType,
                    message = "セクター ${config.sectorIndex} に対して指定された計 ${keyCandidates.size} 通りの鍵候補すべてで認証に失敗しました。"
                )
            }

            // 4. 対象ブロックの絶対インデックス計算と16バイト読み取り
            val safeBlockInSector = config.blockIndexInSector.coerceIn(
                0,
                (mifare.getBlockCountInSector(matchedSector) - 1).coerceAtLeast(0)
            )
            val absoluteBlockIndex = mifare.sectorToBlock(matchedSector) + safeBlockInSector
            val rawBlock = mifare.readBlock(absoluteBlockIndex)

            val cardTypeDesc = when (mifare.type) {
                MifareClassic.TYPE_CLASSIC -> "MIFARE Classic (${mifare.size}B / Sector $matchedSector / ${matchedCandidate.summaryLabel} で認証成功)"
                MifareClassic.TYPE_PLUS -> "MIFARE Plus (${mifare.size}B / Sector $matchedSector / ${matchedCandidate.summaryLabel})"
                MifareClassic.TYPE_PRO -> "MIFARE Pro (${mifare.size}B / Sector $matchedSector / ${matchedCandidate.summaryLabel})"
                else -> "MIFARE (${mifare.size}B / Sector $matchedSector / ${matchedCandidate.summaryLabel})"
            }

            // 5. 16バイトブロックデータから学籍番号を抽出
            when (val parseOutcome = extractStudentIdFromBlock(rawBlock, config)) {
                is BlockParseOutcome.Valid -> MifareReadResult.Success(
                    cardUid = cardUid,
                    studentId = parseOutcome.studentId,
                    rawBlockBytes = rawBlock,
                    sectorIndex = matchedSector,
                    blockIndexInSector = safeBlockInSector,
                    absoluteBlockIndex = absoluteBlockIndex,
                    cardTypeDescription = cardTypeDesc
                )

                is BlockParseOutcome.Invalid -> MifareReadResult.ParseFailed(
                    cardUid = cardUid,
                    rawBlockBytes = rawBlock,
                    sectorIndex = matchedSector,
                    blockIndexInSector = safeBlockInSector,
                    absoluteBlockIndex = absoluteBlockIndex,
                    reason = parseOutcome.reason
                )
            }
        } catch (e: IOException) {
            MifareReadResult.Error(
                cardUid = cardUid,
                title = "カード通信エラー",
                message = "読み取り中にカードが離れたか通信エラーが発生しました。もう一度ゆっくりタッチしてください。(${e.localizedMessage ?: "IOException"})",
                techList = techList
            )
        } catch (e: Exception) {
            MifareReadResult.Error(
                cardUid = cardUid,
                title = "カード読み取り例外",
                message = "予期しないエラーが発生しました: ${e.localizedMessage ?: e.javaClass.simpleName}",
                techList = techList
            )
        } finally {
            try {
                mifare.close()
            } catch (_: Exception) {
            }
        }
    }

    sealed class BlockParseOutcome {
        data class Valid(val studentId: String) : BlockParseOutcome()
        data class Invalid(val reason: String) : BlockParseOutcome()
    }

    /**
     * 16バイトのブロックデータから設定（開始位置・長さ・文字コード・正規表現）に基づき学籍番号を抽出する
     */
    fun extractStudentIdFromBlock(rawBlock: ByteArray, config: MifareConfig): BlockParseOutcome {
        if (rawBlock.isEmpty()) {
            return BlockParseOutcome.Invalid("ブロックデータが空です")
        }

        val start = config.byteOffset.coerceIn(0, rawBlock.size - 1)
        val endExclusive = (start + config.byteLength).coerceIn(start + 1, rawBlock.size)
        val slice = rawBlock.copyOfRange(start, endExclusive)

        val decodedRaw = when (config.encoding) {
            StudentIdEncoding.ASCII_UTF8 -> {
                val filteredBytes = if (config.trimNullAndSpaces) {
                    // NULL(0x00)や制御文字を除去
                    slice.filter { b ->
                        val unsigned = b.toInt() and 0xFF
                        unsigned in 0x20..0x7E || unsigned >= 0x80
                    }.toByteArray()
                } else {
                    slice
                }
                String(filteredBytes, StandardCharsets.UTF_8).let {
                    if (config.trimNullAndSpaces) it.trim() else it
                }
            }

            StudentIdEncoding.HEX_STRING -> {
                val hex = bytesToHex(slice, separator = "")
                if (config.trimNullAndSpaces) {
                    hex.trimEnd('0', ' ')
                } else {
                    hex
                }
            }

            StudentIdEncoding.BCD_DECIMAL -> {
                val sb = StringBuilder()
                for (b in slice) {
                    val high = (b.toInt() ushr 4) and 0x0F
                    val low = b.toInt() and 0x0F
                    if (high in 0..9) sb.append(high) else if (!config.trimNullAndSpaces) sb.append(high.toString(16).uppercase())
                    if (low in 0..9) sb.append(low) else if (!config.trimNullAndSpaces) sb.append(low.toString(16).uppercase())
                }
                val result = sb.toString()
                if (config.trimNullAndSpaces) result.trim() else result
            }
        }

        if (decodedRaw.isBlank()) {
            return BlockParseOutcome.Invalid(
                "指定範囲（Offset=${start}, Length=${endExclusive - start}）から有効な学籍番号文字列を抽出できませんでした（全バイトが空または0x00です）"
            )
        }

        val regexPattern = config.validationRegex.trim()
        if (regexPattern.isNotEmpty()) {
            val regex = try {
                Regex(regexPattern)
            } catch (e: Exception) {
                return BlockParseOutcome.Invalid("設定された正規表現が無効です: $regexPattern")
            }

            val match = regex.find(decodedRaw)
                ?: return BlockParseOutcome.Invalid(
                    "抽出文字列「$decodedRaw」が正規表現パターン「$regexPattern」に一致しません"
                )

            val extracted = if (match.groupValues.size > 1 && match.groupValues[1].isNotEmpty()) {
                match.groupValues[1]
            } else {
                match.value
            }
            return if (extracted.isNotBlank()) {
                BlockParseOutcome.Valid(extracted)
            } else {
                BlockParseOutcome.Invalid("正規表現による抽出結果が空文字になりました")
            }
        }

        return BlockParseOutcome.Valid(decodedRaw)
    }

    /**
     * バイト配列を16進数文字列に変換するユーティリティ
     */
    fun bytesToHex(bytes: ByteArray?, separator: String = " "): String {
        if (bytes == null || bytes.isEmpty()) return ""
        return bytes.joinToString(separator) { b ->
            String.format("%02X", b.toInt() and 0xFF)
        }
    }

    /**
     * 16バイトブロックを印字可能ASCII文字（非印字文字は '.'）としてプレビュー表示する
     */
    fun bytesToAsciiPreview(bytes: ByteArray?): String {
        if (bytes == null || bytes.isEmpty()) return ""
        return buildString(bytes.size) {
            for (b in bytes) {
                val c = b.toInt() and 0xFF
                if (c in 0x20..0x7E) {
                    append(c.toChar())
                } else {
                    append('·')
                }
            }
        }
    }

    /**
     * テスト・シミュレーション用に、指定した学籍番号文字列を含む16バイトMIFAREブロックを生成する
     */
    fun createSimulatedBlock(studentIdText: String, offset: Int = 0): ByteArray {
        val block = ByteArray(16) { 0x00 }
        val asciiBytes = studentIdText.toByteArray(StandardCharsets.US_ASCII)
        val safeOffset = offset.coerceIn(0, 15)
        val copyLen = asciiBytes.size.coerceAtMost(16 - safeOffset)
        System.arraycopy(asciiBytes, 0, block, safeOffset, copyLen)
        return block
    }
}
