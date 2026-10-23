package app.nagatani.dev.attendance.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nagatani.dev.attendance.data.local.AttendanceRecordEntity
import app.nagatani.dev.attendance.data.local.CourseEntity
import app.nagatani.dev.attendance.data.local.LectureSessionEntity
import app.nagatani.dev.attendance.data.preferences.MifareConfig
import app.nagatani.dev.attendance.export.CsvExportManager
import app.nagatani.dev.attendance.ui.ScanBannerState
import app.nagatani.dev.attendance.usb.UsbReaderState
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttendanceKioskScreen(
    isCompact: Boolean = false,
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
    // 出席受付中はスリープを防止
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

    val statusSummaryText = remember(mifareConfig, usbReaderState, isNfcAvailable, isNfcEnabled, isCompact) {
        buildString {
            append("Sector ${mifareConfig.sectorIndex}/Block ${mifareConfig.blockIndexInSector} (${mifareConfig.keyType.displayName}) | ")
            when (usbReaderState) {
                is UsbReaderState.Connected -> append("USB接続中: ${usbReaderState.deviceName} ")
                is UsbReaderState.PermissionRequired -> append("USB権限待ち: ${usbReaderState.deviceName} ")
                is UsbReaderState.Error -> append("USBエラー ")
                is UsbReaderState.Disconnected -> append("USB:未接続 ")
            }
            if (isNfcAvailable && isNfcEnabled) {
                append("| 本体NFC:待機中")
            } else if (isNfcAvailable) {
                append("| 本体NFC:OFF")
            } else {
                append("| 本体NFC:非搭載")
            }
        }
    }

    @Composable
    fun ScanBannerPanel(modifier: Modifier) {
        val cardPadding = if (isCompact) 16.dp else 32.dp
        val circleBoxSize = if (isCompact) 72.dp else 128.dp
        val nfcIconSize = if (isCompact) 44.dp else 76.dp
        val statusIconSize = if (isCompact) 54.dp else 90.dp
        val standbyTitleSize = if (isCompact) 21.sp else 32.sp
        val standbyBodySize = if (isCompact) 13.sp else 16.sp
        val resultTitleSize = if (isCompact) 20.sp else 27.sp
        val studentIdFontSize = if (isCompact) 28.sp else 42.sp

        Box(modifier = modifier) {
            AnimatedContent(
                targetState = scanBannerState,
                label = "ScanResultAnimation"
            ) { state ->
                when (state) {
                    is ScanBannerState.Standby -> {
                        Card(
                            modifier = Modifier
                                .fillMaxSize()
                                .border(2.dp, Color(0xFF3B82F6), RoundedCornerShape(20.dp)),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(cardPadding),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(circleBoxSize)
                                        .clip(CircleShape)
                                        .background(Color(0xFF1D4ED8).copy(alpha = 0.25f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Nfc,
                                        contentDescription = null,
                                        tint = Color(0xFF60A5FA),
                                        modifier = Modifier.size(nfcIconSize)
                                    )
                                }
                                Spacer(modifier = Modifier.height(if (isCompact) 12.dp else 24.dp))
                                Text(
                                    text = "学生証をタッチしてください",
                                    color = Color.White,
                                    fontSize = standbyTitleSize,
                                    fontWeight = FontWeight.ExtraBold,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(if (isCompact) 6.dp else 12.dp))
                                Text(
                                    text = if (usbReaderState is UsbReaderState.Connected) {
                                        "USB接続中の外付けNFCリーダー（${usbReaderState.deviceName}）または端末背面のNFCリーダー部に学生証をかざしてください"
                                    } else {
                                        "外付けUSB NFCリーダーまたは端末本体のNFCリーダー部に MIFARE Classic 学生証をかざすと出席が記録されます"
                                    },
                                    color = Color(0xFFCBD5E1),
                                    fontSize = standbyBodySize,
                                    textAlign = TextAlign.Center
                                )
                                if (usbReaderState !is UsbReaderState.Connected) {
                                    Spacer(modifier = Modifier.height(if (isCompact) 10.dp else 16.dp))
                                    OutlinedButton(
                                        onClick = onScanUsbReader,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = "USB NFCリーダーを再検出・接続",
                                            color = Color(0xFF93C5FD),
                                            fontSize = if (isCompact) 12.sp else 14.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    is ScanBannerState.Success -> {
                        Card(
                            modifier = Modifier
                                .fillMaxSize()
                                .border(4.dp, Color(0xFF34D399), RoundedCornerShape(20.dp)),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF064E3B))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(cardPadding),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF34D399),
                                    modifier = Modifier.size(statusIconSize)
                                )
                                Spacer(modifier = Modifier.height(if (isCompact) 8.dp else 16.dp))
                                Text(
                                    text = "出席を受け付けました",
                                    color = Color(0xFFA7F3D0),
                                    fontSize = resultTitleSize,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(if (isCompact) 8.dp else 12.dp))
                                Surface(
                                    color = Color(0xFF022C22),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text(
                                        text = state.studentId,
                                        color = Color.White,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = studentIdFontSize,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.padding(
                                            horizontal = if (isCompact) 20.dp else 32.dp,
                                            vertical = if (isCompact) 8.dp else 14.dp
                                        )
                                    )
                                }
                                Spacer(modifier = Modifier.height(if (isCompact) 8.dp else 16.dp))
                                Text(
                                    text = "${state.registeredTimeText} (${state.method})",
                                    color = Color(0xFFD1FAE5),
                                    fontSize = if (isCompact) 14.sp else 18.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    is ScanBannerState.Duplicate -> {
                        Card(
                            modifier = Modifier
                                .fillMaxSize()
                                .border(4.dp, Color(0xFFFBBF24), RoundedCornerShape(20.dp)),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF78350F))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(cardPadding),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = Color(0xFFFBBF24),
                                    modifier = Modifier.size(statusIconSize)
                                )
                                Spacer(modifier = Modifier.height(if (isCompact) 8.dp else 16.dp))
                                Text(
                                    text = "すでに出席登録済みです",
                                    color = Color(0xFFFDE68A),
                                    fontSize = resultTitleSize,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(if (isCompact) 8.dp else 12.dp))
                                Surface(
                                    color = Color(0xFF451A03),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text(
                                        text = state.studentId,
                                        color = Color.White,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = studentIdFontSize,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.padding(
                                            horizontal = if (isCompact) 20.dp else 28.dp,
                                            vertical = if (isCompact) 8.dp else 12.dp
                                        )
                                    )
                                }
                                Spacer(modifier = Modifier.height(if (isCompact) 8.dp else 14.dp))
                                Text(
                                    text = "初回登録: ${state.firstRegisteredTimeText}",
                                    color = Color(0xFFFEF3C7),
                                    fontSize = if (isCompact) 14.sp else 18.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    is ScanBannerState.Error -> {
                        Card(
                            modifier = Modifier
                                .fillMaxSize()
                                .border(4.dp, Color(0xFFF87171), RoundedCornerShape(20.dp)),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(cardPadding),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Error,
                                    contentDescription = null,
                                    tint = Color(0xFFF87171),
                                    modifier = Modifier.size(statusIconSize)
                                )
                                Spacer(modifier = Modifier.height(if (isCompact) 8.dp else 16.dp))
                                Text(
                                    text = state.title,
                                    color = Color(0xFFFECACA),
                                    fontSize = resultTitleSize,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(if (isCompact) 6.dp else 12.dp))
                                Text(
                                    text = state.message,
                                    color = Color.White,
                                    fontSize = if (isCompact) 13.sp else 18.sp,
                                    textAlign = TextAlign.Center,
                                    maxLines = if (isCompact) 4 else 6,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (state.cardUid.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(if (isCompact) 6.dp else 12.dp))
                                    Text(
                                        text = "UID: ${state.cardUid}",
                                        color = Color(0xFFFCA5A5),
                                        fontSize = if (isCompact) 12.sp else 14.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun RecentHistoryAndControlsPanel(modifier: Modifier) {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (isCompact) 14.dp else 20.dp)
            ) {
                Text(
                    text = "直近の出席登録履歴（最新順）",
                    color = Color.White,
                    style = if (isCompact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
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
                            color = Color(0xFF94A3B8),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
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
                                        .padding(horizontal = 12.dp, vertical = if (isCompact) 8.dp else 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = item.studentId,
                                            color = Color.White,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = if (isCompact) 16.sp else 20.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = CsvExportManager.formatEpochMillis(item.registeredAt),
                                            color = Color(0xFF94A3B8),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = if (isCompact) 11.sp else 13.sp
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
                Spacer(modifier = Modifier.height(10.dp))

                // 下部操作ボタン群
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showManualDialog = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("手動入力", fontSize = if (isCompact) 13.sp else 14.sp, maxLines = 1)
                    }

                    Button(
                        onClick = {
                            val nextStudentId = "24B${simCounter++}"
                            onSimulateCardTap(nextStudentId)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("テスト読取", fontSize = if (isCompact) 13.sp else 14.sp, maxLines = 1)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onExportCurrentSession,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.FolderSpecial, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isCompact) "CSV出力" else "所定の場所へCSV出力",
                            fontSize = if (isCompact) 13.sp else 14.sp,
                            maxLines = 1
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            if (mifareConfig.kioskPin.isNotBlank()) {
                                showPinExitDialog = true
                            } else {
                                onExitKioskMode()
                            }
                        },
                        modifier = Modifier.weight(0.85f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            Icons.Default.ExitToApp,
                            contentDescription = null,
                            tint = Color(0xFFCBD5E1),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("受付終了", color = Color(0xFFCBD5E1), fontSize = if (isCompact) 13.sp else 14.sp, maxLines = 1)
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A)) // 視認性の高いダークネイビー背景
            .padding(if (isCompact) 12.dp else 20.dp)
    ) {
        // ============================================================
        // 上部バー: 現在の講義・講義回情報 ＆ リアルタイム時計 ＆ 出席人数バッジ
        // ============================================================
        Surface(
            color = Color(0xFF1E293B),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isCompact) {
                // スマートフォン向けコンパクトヘッダー
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Surface(
                                    color = Color(0xFF2563EB),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = selectedCourse?.courseCode ?: "未選択",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                                if (selectedSession != null) {
                                    Surface(
                                        color = Color(0xFF0D9488),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = "第${selectedSession.sessionNumber}回 (${selectedSession.sessionDate})",
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = selectedCourse?.courseName ?: "講義を選択してください",
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Surface(
                            color = Color(0xFF059669),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "出席数",
                                    color = Color(0xFFD1FAE5),
                                    fontSize = 10.sp
                                )
                                Text(
                                    text = "${attendanceRecords.size}名",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = statusSummaryText,
                            color = if (usbReaderState is UsbReaderState.Connected) Color(0xFF34D399) else Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = currentTimeText.substringAfter(" "),
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                // タブレット向けワイドヘッダー
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
                            text = statusSummaryText,
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
        }

        Spacer(modifier = Modifier.height(if (isCompact) 10.dp else 18.dp))

        // ============================================================
        // メインエリア（スマホは上下2段、タブレットは左右2ペイン）
        // ============================================================
        if (isCompact) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ScanBannerPanel(
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxWidth()
                )
                RecentHistoryAndControlsPanel(
                    modifier = Modifier
                        .weight(0.95f)
                        .fillMaxWidth()
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                ScanBannerPanel(
                    modifier = Modifier
                        .weight(1.25f)
                        .fillMaxHeight()
                )
                RecentHistoryAndControlsPanel(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
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
