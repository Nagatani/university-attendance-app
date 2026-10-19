package jp.ac.university.attendance.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import jp.ac.university.attendance.data.local.AttendanceExportRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 出席データをCSV形式で所定のフォルダへの保存・ファイル選択保存・他アプリ共有するマネージャ
 */
object CsvExportManager {

    private val timestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private val fileTimestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    /**
     * 出席データリストからCSV文字列を生成する
     */
    fun buildCsvContent(rows: List<AttendanceExportRow>, includeUtf8Bom: Boolean = true): String {
        val sb = StringBuilder()
        if (includeUtf8Bom) {
            sb.append('\uFEFF')
        }

        // ヘッダー行（必須4項目：講義、講義回、学籍番号、出席登録日時 ＋ 補助情報）
        val headers = listOf(
            "講義コード",
            "講義名",
            "講義回",
            "講義回タイトル",
            "講義日",
            "学籍番号",
            "出席登録日時",
            "登録方法",
            "カードUID",
            "備考"
        )
        sb.append(headers.joinToString(",") { escapeCsvField(it) }).append("\r\n")

        for (row in rows) {
            val formattedRegisteredAt = formatEpochMillis(row.registeredAt)
            val fields = listOf(
                row.courseCode,
                row.courseName,
                "第${row.sessionNumber}回",
                row.sessionTitle,
                row.sessionDate,
                row.studentId,
                formattedRegisteredAt,
                row.registrationMethod,
                row.cardUid,
                row.note
            )
            sb.append(fields.joinToString(",") { escapeCsvField(it) }).append("\r\n")
        }

        return sb.toString()
    }

    /**
     * ファイル名を生成するユーティリティ
     */
    fun generateDefaultFileName(
        courseCode: String? = null,
        sessionNumber: Int? = null
    ): String {
        val nowStr = LocalDateTime.now().format(fileTimestampFormatter)
        val safeCourse = courseCode
            ?.replace(Regex("[^A-Za-z0-9_-]"), "_")
            ?.takeIf { it.isNotBlank() }
            ?: "ALL_COURSES"
        val sessionPart = if (sessionNumber != null) "_session${sessionNumber}" else ""
        return "attendance_${safeCourse}${sessionPart}_${nowStr}.csv"
    }

    /**
     * 方法1: SAF (CreateDocument) でユーザーが指定した単一ファイルURIへCSVを書き込む
     */
    suspend fun writeCsvToUri(
        context: Context,
        targetUri: Uri,
        rows: List<AttendanceExportRow>,
        includeUtf8Bom: Boolean = true
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val csvText = buildCsvContent(rows, includeUtf8Bom)
            val outputStream = context.contentResolver.openOutputStream(targetUri, "wt")
                ?: throw IllegalStateException("出力先ストリームを開けませんでした: $targetUri")
            outputStream.use { stream ->
                OutputStreamWriter(stream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(csvText)
                    writer.flush()
                }
            }
            rows.size
        }
    }

    /**
     * 方法2: 事前設定された「所定の保存先フォルダ（Tree URI）」へワンタップでCSVファイルを自動作成・保存する
     */
    suspend fun exportToDesignatedDirectory(
        context: Context,
        directoryTreeUriString: String,
        fileName: String,
        rows: List<AttendanceExportRow>,
        includeUtf8Bom: Boolean = true
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (directoryTreeUriString.isBlank()) {
                throw IllegalArgumentException("所定の保存先フォルダが未設定です。エクスポート画面または設定からフォルダを選択してください。")
            }
            val treeUri = Uri.parse(directoryTreeUriString)
            val pickedDir = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IllegalStateException("指定されたフォルダにアクセスできません。")

            if (!pickedDir.canWrite()) {
                throw IllegalStateException("所定のフォルダへの書き込み権限がありません。再度フォルダを選択してください。")
            }

            // 同名ファイルが既にあれば削除して作り直す
            pickedDir.findFile(fileName)?.delete()

            val newFile = pickedDir.createFile("text/csv", fileName)
                ?: throw IllegalStateException("所定のフォルダ内にCSVファイル ($fileName) を作成できませんでした。")

            val csvText = buildCsvContent(rows, includeUtf8Bom)
            val outputStream = context.contentResolver.openOutputStream(newFile.uri, "wt")
                ?: throw IllegalStateException("ファイルストリームを開けませんでした。")

            outputStream.use { stream ->
                OutputStreamWriter(stream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(csvText)
                    writer.flush()
                }
            }

            val dirName = pickedDir.name ?: "指定フォルダ"
            "$dirName / ${newFile.name ?: fileName} (${rows.size}件)"
        }
    }

    /**
     * 方法3: 一時キャッシュにCSVを生成し、Android共有シート（Googleドライブ、メール、ファイルアプリ等）を起動する
     */
    suspend fun shareCsvFile(
        context: Context,
        fileName: String,
        rows: List<AttendanceExportRow>,
        includeUtf8Bom: Boolean = true
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val exportDir = File(context.cacheDir, "exports").apply {
                if (!exists()) mkdirs()
            }
            val csvFile = File(exportDir, fileName)
            val csvText = buildCsvContent(rows, includeUtf8Bom)
            csvFile.writeText(csvText, StandardCharsets.UTF_8)

            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                csvFile
            )

            withContext(Dispatchers.Main) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    putExtra(Intent.EXTRA_SUBJECT, "出席データ出力: $fileName")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(shareIntent, "出席データCSVをエクスポート・共有").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            }
        }
    }

    fun formatEpochMillis(epochMillis: Long): String {
        return Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(timestampFormatter)
    }

    private fun escapeCsvField(field: String): String {
        val needsQuotes = field.contains(',') ||
            field.contains('"') ||
            field.contains('\n') ||
            field.contains('\r')
        return if (needsQuotes) {
            "\"${field.replace("\"", "\"\"")}\""
        } else {
            field
        }
    }
}
