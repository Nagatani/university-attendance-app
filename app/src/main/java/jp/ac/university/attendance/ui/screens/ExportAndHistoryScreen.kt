package jp.ac.university.attendance.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import jp.ac.university.attendance.data.local.AttendanceExportRow
import jp.ac.university.attendance.data.local.CourseEntity
import jp.ac.university.attendance.data.preferences.ExportConfig
import jp.ac.university.attendance.export.CsvExportManager
import jp.ac.university.attendance.ui.ExportScope

@Composable
fun ExportAndHistoryScreen(
    allRows: List<AttendanceExportRow>,
    courses: List<CourseEntity>,
    exportConfig: ExportConfig,
    onUpdateExportConfig: (ExportConfig) -> Unit,
    onPickDefaultDirectory: () -> Unit,
    onExportToDefaultFolder: (ExportScope) -> Unit,
    onExportViaFilePicker: (ExportScope) -> Unit,
    onShareCsv: (ExportScope) -> Unit,
    onDeleteRecordById: (recordId: Long, studentId: String) -> Unit
) {
    var filterCourseId by remember { mutableStateOf<Long?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredRows = remember(allRows, filterCourseId, searchQuery) {
        allRows.filter { row ->
            val matchesCourse = filterCourseId == null || row.courseId == filterCourseId
            val q = searchQuery.trim()
            val matchesQuery = q.isEmpty() ||
                row.studentId.contains(q, ignoreCase = true) ||
                row.courseName.contains(q, ignoreCase = true) ||
                row.courseCode.contains(q, ignoreCase = true) ||
                "第${row.sessionNumber}回".contains(q)
            matchesCourse && matchesQuery
        }
    }

    val selectedCourseForExport = courses.find { it.id == filterCourseId }
    val activeScope: ExportScope = if (selectedCourseForExport != null) {
        ExportScope.SingleCourse(
            courseId = selectedCourseForExport.id,
            courseCode = selectedCourseForExport.courseCode
        )
    } else {
        ExportScope.AllRecords
    }

    val readableFolderUri = remember(exportConfig.defaultDirectoryUri) {
        if (exportConfig.defaultDirectoryUri.isBlank()) {
            "未設定（「所定のフォルダを選択」から保存先フォルダを指定すると、以後ワンタップでそこへCSV出力されます）"
        } else {
            runCatching { Uri.decode(exportConfig.defaultDirectoryUri) }
                .getOrDefault(exportConfig.defaultDirectoryUri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ============================================================
        // 上段: 所定のエクスポート先設定 ＆ CSV出力操作パネル
        // ============================================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "所定の場所へのCSVエクスポート設定",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "所定の保存先フォルダ: $readableFolderUri",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (exportConfig.defaultDirectoryUri.isBlank()) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.secondary
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    OutlinedButton(onClick = onPickDefaultDirectory) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (exportConfig.defaultDirectoryUri.isBlank()) "所定のフォルダを選択" else "所定のフォルダを変更")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                onUpdateExportConfig(
                                    exportConfig.copy(includeUtf8Bom = !exportConfig.includeUtf8Bom)
                                )
                            }
                        ) {
                            Checkbox(
                                checked = exportConfig.includeUtf8Bom,
                                onCheckedChange = {
                                    onUpdateExportConfig(exportConfig.copy(includeUtf8Bom = it))
                                }
                            )
                            Text(
                                text = "UTF-8 BOM付で出力（Excel文字化け防止）",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                onUpdateExportConfig(
                                    exportConfig.copy(soundEnabled = !exportConfig.soundEnabled)
                                )
                            }
                        ) {
                            Checkbox(
                                checked = exportConfig.soundEnabled,
                                onCheckedChange = {
                                    onUpdateExportConfig(exportConfig.copy(soundEnabled = it))
                                }
                            )
                            Text(
                                text = "カード読取時の効果音を有効化",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    // エクスポート実行ボタン群
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val scopeLabel = if (selectedCourseForExport != null) {
                            "「${selectedCourseForExport.courseName}」"
                        } else {
                            "全講義データ"
                        }

                        Button(
                            onClick = { onExportToDefaultFolder(activeScope) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Icon(Icons.Default.FolderSpecial, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("${scopeLabel}を所定の場所へ出力")
                        }

                        OutlinedButton(
                            onClick = { onExportViaFilePicker(activeScope) }
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("保存先を指定して出力")
                        }

                        OutlinedButton(
                            onClick = { onShareCsv(activeScope) }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("他アプリへ共有")
                        }
                    }
                }
            }
        }

        // ============================================================
        // 下段: 出席データ検索・フィルタ ＆ 全履歴一覧テーブル
        // ============================================================
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "登録済み出席データ一覧（該当: ${filteredRows.size} 件 / 全 ${allRows.size} 件）",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                FilterChip(
                                    selected = filterCourseId == null,
                                    onClick = { filterCourseId = null },
                                    label = { Text("すべての講義") }
                                )
                            }
                            items(courses, key = { it.id }) { course ->
                                FilterChip(
                                    selected = filterCourseId == course.id,
                                    onClick = { filterCourseId = course.id },
                                    label = { Text("${course.courseCode} ${course.courseName}") }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        label = { Text("学籍番号・講義名・講義回で検索") },
                        singleLine = true,
                        modifier = Modifier.width(320.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ) {
                    if (filteredRows.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "該当する出席データがありません。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("講義", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.5f))
                                Text("講義回", fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.9f))
                                Text("学籍番号", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.1f))
                                Text("出席登録日時", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.3f))
                                Text("方法 / UID", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.2f))
                                Spacer(modifier = Modifier.width(44.dp))
                            }
                            HorizontalDivider()

                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(filteredRows, key = { it.recordId }) { row ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1.5f)) {
                                            Text(
                                                text = row.courseName,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = row.courseCode,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Column(modifier = Modifier.weight(0.9f)) {
                                            Text(
                                                text = "第${row.sessionNumber}回",
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = row.sessionDate,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = row.studentId,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1.1f)
                                        )
                                        Text(
                                            text = CsvExportManager.formatEpochMillis(row.registeredAt),
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.weight(1.3f)
                                        )
                                        Column(modifier = Modifier.weight(1.2f)) {
                                            Text(
                                                text = row.registrationMethod,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            if (row.cardUid.isNotBlank()) {
                                                Text(
                                                    text = "UID:${row.cardUid}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = { onDeleteRecordById(row.recordId, row.studentId) }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "削除",
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
