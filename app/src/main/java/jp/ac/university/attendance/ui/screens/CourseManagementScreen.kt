package jp.ac.university.attendance.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.ac.university.attendance.data.local.AttendanceRecordEntity
import jp.ac.university.attendance.data.local.CourseEntity
import jp.ac.university.attendance.data.local.SessionWithCount
import jp.ac.university.attendance.export.CsvExportManager
import java.time.LocalDate

@Composable
fun CourseManagementScreen(
    courses: List<CourseEntity>,
    selectedCourseId: Long?,
    sessions: List<SessionWithCount>,
    selectedSessionId: Long?,
    attendanceRecords: List<AttendanceRecordEntity>,
    onSelectCourse: (Long) -> Unit,
    onSelectSession: (Long) -> Unit,
    onCreateCourse: (code: String, name: String, instructor: String, room: String, auto15: Boolean) -> Unit,
    onDeleteCourse: (CourseEntity) -> Unit,
    onCreateSession: (courseId: Long, sessionNumber: Int, title: String, date: String) -> Unit,
    onGenerate15Sessions: (courseId: Long) -> Unit,
    onStartAttendanceKiosk: (courseId: Long, sessionId: Long) -> Unit,
    onRegisterManualAttendance: (studentId: String, note: String) -> Unit,
    onDeleteAttendance: (AttendanceRecordEntity) -> Unit,
    onExportSessionToDefaultFolder: (sessionId: Long, courseCode: String, sessionNumber: Int) -> Unit,
    onExportSessionViaFilePicker: (sessionId: Long, courseCode: String, sessionNumber: Int) -> Unit,
    onShareSessionCsv: (sessionId: Long, courseCode: String, sessionNumber: Int) -> Unit
) {
    var showAddCourseDialog by remember { mutableStateOf(false) }
    var showAddSessionDialog by remember { mutableStateOf(false) }
    var showManualEntryDialog by remember { mutableStateOf(false) }
    var courseToDelete by remember { mutableStateOf<CourseEntity?>(null) }

    val selectedCourse = courses.find { it.id == selectedCourseId }
    val selectedSessionWithCount = sessions.find { it.session.id == selectedSessionId }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ============================================================
        // 左ペイン: 講義（科目）一覧（タブレット向けマスターリスト）
        // ============================================================
        Card(
            modifier = Modifier
                .width(310.dp)
                .fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.School,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "講義一覧 (${courses.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Button(
                        onClick = { showAddCourseDialog = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("新規講義")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                if (courses.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "講義が登録されていません。\n右上の「新規講義」から追加してください。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(courses, key = { it.id }) { course ->
                            val isSelected = course.id == selectedCourseId
                            val bgColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            }
                            val borderColor = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.Transparent
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(bgColor)
                                    .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
                                    .clickable { onSelectCourse(course.id) }
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = course.courseCode,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = course.courseName,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (course.scheduleAndRoom.isNotBlank() || course.instructorName.isNotBlank()) {
                                            Text(
                                                text = listOf(course.scheduleAndRoom, course.instructorName)
                                                    .filter { it.isNotBlank() }
                                                    .joinToString(" / "),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { courseToDelete = course }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "講義を削除",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ============================================================
        // 右ペイン: 選択中の講義の「講義回」選択 ＆ 出席受付開始 ＆ 出席者リスト
        // ============================================================
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            if (selectedCourse == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("左側のリストから講義を選択してください。")
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {
                    // 1. 選択中の講義ヘッダーと講義回操作ボタン
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${selectedCourse.courseCode}  ${selectedCourse.courseName}",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = listOf(
                                    selectedCourse.scheduleAndRoom.takeIf { it.isNotBlank() },
                                    selectedCourse.instructorName.takeIf { it.isNotBlank() }?.let { "担当: $it" }
                                ).filterNotNull().joinToString("  |  ").ifEmpty { "講義回を選択して出席受付またはCSV出力を行えます" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { onGenerate15Sessions(selectedCourse.id) }
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("全15回を一括作成")
                            }
                            OutlinedButton(
                                onClick = { showAddSessionDialog = true }
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("講義回を追加")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 2. 講義回（第1回〜第15回）横スクロールチップセレクタ
                    Text(
                        text = "講義回を選択:",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    if (sessions.isEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "講義回がありません。「全15回を一括作成」または「講義回を追加」を押してください。",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(sessions, key = { it.session.id }) { item ->
                                val isSelected = item.session.id == selectedSessionId
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onSelectSession(item.session.id) },
                                    label = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = "第${item.session.sessionNumber}回",
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Badge(
                                                containerColor = if (item.attendanceCount > 0) {
                                                    MaterialTheme.colorScheme.secondary
                                                } else {
                                                    MaterialTheme.colorScheme.outlineVariant
                                                }
                                            ) {
                                                Text(
                                                    text = "${item.attendanceCount}名",
                                                    color = if (item.attendanceCount > 0) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(14.dp))

                    // 3. 選択中の講義回のアクションバー（出席受付モード開始・手動追加・CSV出力）
                    if (selectedSessionWithCount != null) {
                        val currentSession = selectedSessionWithCount.session
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "第${currentSession.sessionNumber}回",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = currentSession.sessionTitle,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "実施日: ${currentSession.sessionDate}   |   現在の出席登録数: ${selectedSessionWithCount.attendanceCount} 名",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Button(
                                    onClick = {
                                        onStartAttendanceKiosk(selectedCourse.id, currentSession.id)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    ),
                                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)
                                ) {
                                    Icon(Icons.Default.Nfc, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "出席受付モードを開始（全画面）",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // サブアクション行（手動登録 & CSVエクスポート3種）
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "第${currentSession.sessionNumber}回 出席登録一覧 (${attendanceRecords.size}件)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { showManualEntryDialog = true }) {
                                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("手動登録")
                                }
                                Button(
                                    onClick = {
                                        onExportSessionToDefaultFolder(
                                            currentSession.id,
                                            selectedCourse.courseCode,
                                            currentSession.sessionNumber
                                        )
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondary
                                    )
                                ) {
                                    Icon(Icons.Default.FolderSpecial, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("所定の場所へCSV出力")
                                }
                                OutlinedButton(
                                    onClick = {
                                        onExportSessionViaFilePicker(
                                            currentSession.id,
                                            selectedCourse.courseCode,
                                            currentSession.sessionNumber
                                        )
                                    }
                                ) {
                                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("保存先を選択")
                                }
                                OutlinedButton(
                                    onClick = {
                                        onShareSessionCsv(
                                            currentSession.id,
                                            selectedCourse.courseCode,
                                            currentSession.sessionNumber
                                        )
                                    }
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("共有")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // 4. 出席者データテーブル
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ) {
                            if (attendanceRecords.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "まだこの講義回の出席データはありません。\n「出席受付モードを開始」するか、学生証（MIFARE Classic）をタッチしてください。",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    // テーブルヘッダー
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("学籍番号", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.2f))
                                        Text("出席登録日時", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.4f))
                                        Text("登録方法", fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.8f))
                                        Text("カードUID / 備考", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.4f))
                                        Spacer(modifier = Modifier.width(48.dp))
                                    }
                                    HorizontalDivider()

                                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                                        items(attendanceRecords, key = { it.id }) { record ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = record.studentId,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 16.sp,
                                                    modifier = Modifier.weight(1.2f)
                                                )
                                                Text(
                                                    text = CsvExportManager.formatEpochMillis(record.registeredAt),
                                                    fontFamily = FontFamily.Monospace,
                                                    modifier = Modifier.weight(1.4f)
                                                )
                                                Text(
                                                    text = record.registrationMethod,
                                                    color = if (record.registrationMethod.startsWith("NFC")) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.tertiary
                                                    },
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.weight(0.8f)
                                                )
                                                Text(
                                                    text = listOf(
                                                        record.cardUid.takeIf { it.isNotBlank() }?.let { "UID:$it" },
                                                        record.note.takeIf { it.isNotBlank() }
                                                    ).filterNotNull().joinToString(" "),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.weight(1.4f)
                                                )
                                                IconButton(onClick = { onDeleteAttendance(record) }) {
                                                    Icon(
                                                        imageVector = Icons.Default.DeleteOutline,
                                                        contentDescription = "出席記録を削除",
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
    }

    // ============================================================
    // ダイアログ群: 新規講義追加 / 講義回追加 / 手動出席登録 / 削除確認
    // ============================================================

    if (showAddCourseDialog) {
        var code by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        var instructor by remember { mutableStateOf("") }
        var room by remember { mutableStateOf("") }
        var auto15 by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { showAddCourseDialog = false },
            title = { Text("新規講義の登録") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("講義コード（例: CS-2026-A）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("講義名（必須・例: アルゴリズムとデータ構造）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = instructor,
                        onValueChange = { instructor = it },
                        label = { Text("担当教員名（任意）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = room,
                        onValueChange = { room = it },
                        label = { Text("曜日時限・教室（任意・例: 水曜1限 302教室）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { auto15 = !auto15 }
                    ) {
                        Checkbox(checked = auto15, onCheckedChange = { auto15 = it })
                        Text("第1回〜第15回の講義回を同時に自動作成する")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onCreateCourse(code, name, instructor, room, auto15)
                        showAddCourseDialog = false
                    }
                ) {
                    Text("登録する")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCourseDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }

    if (showAddSessionDialog && selectedCourse != null) {
        val nextNum = (sessions.maxOfOrNull { it.session.sessionNumber } ?: 0) + 1
        var numStr by remember { mutableStateOf(nextNum.toString()) }
        var title by remember { mutableStateOf("第${nextNum}回 講義") }
        var date by remember { mutableStateOf(LocalDate.now().toString()) }

        AlertDialog(
            onDismissRequest = { showAddSessionDialog = false },
            title = { Text("講義回の追加 (${selectedCourse.courseName})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = numStr,
                        onValueChange = { numStr = it.filter { ch -> ch.isDigit() } },
                        label = { Text("講義回番号（数字）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("タイトル・テーマ") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = date,
                        onValueChange = { date = it },
                        label = { Text("実施日 (YYYY-MM-DD)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsedNum = numStr.toIntOrNull() ?: nextNum
                        onCreateSession(selectedCourse.id, parsedNum, title, date)
                        showAddSessionDialog = false
                    }
                ) {
                    Text("追加する")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddSessionDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }

    if (showManualEntryDialog) {
        var studentId by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("学生証忘れ・手動入力") }

        AlertDialog(
            onDismissRequest = { showManualEntryDialog = false },
            title = { Text("手動での出席登録") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = studentId,
                        onValueChange = { studentId = it },
                        label = { Text("学籍番号（例: 24B10234）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("備考（任意）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRegisterManualAttendance(studentId, note)
                        showManualEntryDialog = false
                    }
                ) {
                    Text("出席登録")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualEntryDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }

    courseToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { courseToDelete = null },
            title = { Text("講義の削除確認") },
            text = {
                Text("講義「${target.courseName}」を削除しますか？\n紐づく講義回および出席記録もすべて削除されます。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteCourse(target)
                        courseToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("削除する")
                }
            },
            dismissButton = {
                TextButton(onClick = { courseToDelete = null }) {
                    Text("キャンセル")
                }
            }
        )
    }
}
