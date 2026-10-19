package jp.ac.university.attendance.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.ac.university.attendance.data.local.AttendanceRecordEntity
import jp.ac.university.attendance.data.local.CourseEntity
import jp.ac.university.attendance.data.local.LectureSessionEntity
import jp.ac.university.attendance.data.preferences.MifareConfig
import jp.ac.university.attendance.export.CsvExportManager
import jp.ac.university.attendance.ui.ScanBannerState
import jp.ac.university.attendance.usb.UsbReaderState
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun AttendanceKioskScreen(
    selectedCourse: CourseEntity?,
    selectedSession: LectureSessionEntity?,
    attendanceRecords: List<AttendanceRecordEntity>,
    scanBannerState: ScanBannerState,
    mifareConfig: MifareConfig,
    isNfcAvailable: Boolean,
    isNfcEnabled: Boolean,
    usbReaderState: UsbReaderState,
    onScanUsbReader: () -> Unit,
    onRegisterManualAttendance: (studentId: String, note: String) -> Unit,
    onSimulateCardTap: (studentId: String) -> Unit,
    onExportCurrentSession: () -> Unit,
    onExitKioskMode: () -> Unit
) {
    // 出席受付中はタブレットのスリープを防止
    val view = LocalView.current
    DisposableEffect(Unit) {
        val prevKeepScreenOn = view.keepScreenOn
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = prevKeepScreenOn
        }
    }

    // リアルタイム時計表示
    val clockFormatter = remember { DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss") }
    var currentTimeText by remember { mutableStateOf(LocalDateTime.now().format(clockFormatter)) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTimeText = LocalDateTime.now().format(clockFormatter)
            delay(1000L)
        }
    }

    var showManualDialog by remember { mutableStateOf(false) }
    var showPinExitDialog by remember { mutableStateOf(false) }
    var simCounter by remember { mutableIntStateOf(10234) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A)) // 視認性の高いダークネイビー背景
            .padding(20.dp)
    ) {
        // ============================================================
        // 上部バー: 現在の講義・講義回情報 ＆ リアルタイム時計 ＆ 出席人数バッジ
        // ============================================================
        Surface(
            color = Color(0xFF1E293B),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = Color(0xFF2563EB),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = selectedCourse?.courseCode ?: "未選択",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = selectedCourse?.courseName ?: "講義を選択してください",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        if (selectedSession != null) {
                            Spacer(modifier = Modifier.width(16.dp))
                            Surface(
                                color = Color(0xFF0D9488),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "第${selectedSession.sessionNumber}回 (${selectedSession.sessionDate})",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = buildString {
                            append("MIFARE設定: Sector ${mifareConfig.sectorIndex} / Block ${mifareConfig.blockIndexInSector} (${mifareConfig.keyType.displayName})   |   ")
                            when (usbReaderState) {
                                is UsbReaderState.Connected -> {
                                    append("【USBリーダー接続中: ${usbReaderState.deviceName}】 ")
                                }
                                is UsbReaderState.PermissionRequired -> {
                                    append("【USBリーダー権限許可待ち: ${usbReaderState.deviceName}】 ")
                                }
                                is UsbReaderState.Error -> {
                                    append("【USBエラー: ${usbReaderState.message}】 ")
                                }
                                is UsbReaderState.Disconnected -> {
                                    append("【USBリーダー: 未接続】 ")
                                }
                            }
                            if (isNfcAvailable && isNfcEnabled) {
                                append("【本体NFC: 待機中】")
                            } else if (isNfcAvailable) {
                                append("【本体NFC: OFF】")
                            } else {
                                append("【本体NFC: 非搭載】")
                            }
                        },
                        color = if (usbReaderState is UsbReaderState.Connected) Color(0xFF34D399) else Color(0xFF94A3B8),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // 時計 ＆ 出席者カウント
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "現在日時",
                            color = Color(0xFF94A3B8),
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = currentTimeText,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Surface(
                        color = Color(0xFF059669),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "出席登録数",
                                color = Color(0xFFD1FAE5),
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                text = "${attendanceRecords.size} 名",
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // ============================================================
        // メインエリア（左：大型学生証タッチ＆結果パネル、右：リアルタイム出席履歴＆操作）
        // ============================================================
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // 左パネル: 大型タッチ待機 / 判定結果表示
            Box(
                modifier = Modifier
                    .weight(1.25f)
                    .fillMaxHeight()
            ) {
                AnimatedContent(
                    targetState = scanBannerState,
                    label = "ScanResultAnimation"
                ) { state ->
                    when (state) {
                        is ScanBannerState.Standby -> {
                            Card(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .border(2.dp, Color(0xFF3B82F6), RoundedCornerShape(24.dp)),
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(128.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF1D4ED8).copy(alpha = 0.25f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Nfc,
                                            contentDescription = null,
                                            tint = Color(0xFF60A5FA),
                                            modifier = Modifier.size(76.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(24.dp))
                                    Text(
                                        text = "学生証をタッチしてください",
                                        color = Color.White,
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = if (usbReaderState is UsbReaderState.Connected) {
                                            "USB接続中の外付けNFCリーダー（${usbReaderState.deviceName}）\nまたはタブレット本体のNFCリーダー部に学生証をかざしてください"
                                        } else {
                                            "外付けUSB NFCリーダー（接続時自動認識）またはタブレット本体のNFCリーダー部に\nMIFARE Classic 学生証をかざすと学籍番号と出席日時が記録されます"
                                        },
                                        color = Color(0xFFCBD5E1),
                                        fontSize = 16.sp,
                                        textAlign = TextAlign.Center
                                    )
                                    if (usbReaderState !is UsbReaderState.Connected) {
                                        Spacer(modifier = Modifier.height(16.dp))
                                        OutlinedButton(onClick = onScanUsbReader) {
                                            Text("USB NFCリーダーを再検出・接続", color = Color(0xFF93C5FD))
                                        }
                                    }
                                }
                            }
                        }

                        is ScanBannerState.Success -> {
                            Card(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .border(4.dp, Color(0xFF34D399), RoundedCornerShape(24.dp)),
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF064E3B))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = Color(0xFF34D399),
                                        modifier = Modifier.size(92.dp)
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "出席を受け付けました",
                                        color = Color(0xFFA7F3D0),
                                        fontSize = 26.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Surface(
                                        color = Color(0xFF022C22),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Text(
                                            text = state.studentId,
                                            color = Color.White,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 44.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 14.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "登録日時: ${state.registeredTimeText}  (${state.method})",
                                        color = Color(0xFFD1FAE5),
                                        fontSize = 18.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }

                        is ScanBannerState.Duplicate -> {
                            Card(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .border(4.dp, Color(0xFFFBBF24), RoundedCornerShape(24.dp)),
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF78350F))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = Color(0xFFFBBF24),
                                        modifier = Modifier.size(88.dp)
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "すでに出席登録済みです",
                                        color = Color(0xFFFDE68A),
                                        fontSize = 28.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Surface(
                                        color = Color(0xFF451A03),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Text(
                                            text = state.studentId,
                                            color = Color.White,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 40.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Text(
                                        text = "初回登録日時: ${state.firstRegisteredTimeText}",
                                        color = Color(0xFFFEF3C7),
                                        fontSize = 18.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }

                        is ScanBannerState.Error -> {
                            Card(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .border(4.dp, Color(0xFFF87171), RoundedCornerShape(24.dp)),
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Error,
                                        contentDescription = null,
                                        tint = Color(0xFFF87171),
                                        modifier = Modifier.size(88.dp)
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = state.title,
                                        color = Color(0xFFFECACA),
                                        fontSize = 28.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = state.message,
                                        color = Color.White,
                                        fontSize = 18.sp,
                                        textAlign = TextAlign.Center
                                    )
                                    if (state.cardUid.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = "検出カードUID: ${state.cardUid}",
                                            color = Color(0xFFFCA5A5),
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 右パネル: 直近の出席登録ログ ＆ 補助コントロール
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {
                    Text(
                        text = "直近の出席登録履歴（最新順）",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = Color(0xFF334155))

                    if (attendanceRecords.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "まだ出席登録はありません",
                                color = Color(0xFF94A3B8)
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(attendanceRecords, key = { it.id }) { item ->
                                Surface(
                                    color = Color(0xFF0F172A),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = item.studentId,
                                                color = Color.White,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = CsvExportManager.formatEpochMillis(item.registeredAt),
                                                color = Color(0xFF94A3B8),
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 13.sp
                                            )
                                        }
                                        Surface(
                                            color = if (item.registrationMethod.startsWith("NFC")) {
                                                Color(0xFF1D4ED8)
                                            } else {
                                                Color(0xFFB45309)
                                            },
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = item.registrationMethod,
                                                color = Color.White,
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = Color(0xFF334155))
                    Spacer(modifier = Modifier.height(12.dp))

                    // 下部操作ボタン群
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showManualDialog = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))
                        ) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("手動入力")
                        }

                        Button(
                            onClick = {
                                val nextStudentId = "24B${simCounter++}"
                                onSimulateCardTap(nextStudentId)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E))
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("テスト読取")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onExportCurrentSession,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                        ) {
                            Icon(Icons.Default.FolderSpecial, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("所定の場所へCSV出力")
                        }

                        OutlinedButton(
                            onClick = {
                                if (mifareConfig.kioskPin.isNotBlank()) {
                                    showPinExitDialog = true
                                } else {
                                    onExitKioskMode()
                                }
                            },
                            modifier = Modifier.weight(0.85f)
                        ) {
                            Icon(
                                Icons.Default.ExitToApp,
                                contentDescription = null,
                                tint = Color(0xFFCBD5E1),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("受付終了", color = Color(0xFFCBD5E1))
                        }
                    }
                }
            }
        }
    }

    if (showManualDialog) {
        var studentId by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("学生証忘れ・手動受付") }

        AlertDialog(
            onDismissRequest = { showManualDialog = false },
            title = { Text("学籍番号の手動入力") },
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
                        label = { Text("理由・備考") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRegisterManualAttendance(studentId, note)
                        showManualDialog = false
                    }
                ) {
                    Text("登録する")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }

    if (showPinExitDialog) {
        var pinInput by remember { mutableStateOf("") }
        var pinError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showPinExitDialog = false },
            title = { Text("管理者PINコード入力") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("出席受付モードを終了するには管理者PINを入力してください。")
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = {
                            pinInput = it
                            pinError = false
                        },
                        label = { Text("PINコード") },
                        isError = pinError,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (pinError) {
                        Text(
                            text = "PINコードが一致しません",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pinInput == mifareConfig.kioskPin) {
                            showPinExitDialog = false
                            onExitKioskMode()
                        } else {
                            pinError = true
                        }
                    }
                ) {
                    Text("解除して戻る")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinExitDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }
}
