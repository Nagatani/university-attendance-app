package app.nagatani.dev.attendance

import android.content.Intent
import android.hardware.usb.UsbManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nagatani.dev.attendance.ui.AppTab
import app.nagatani.dev.attendance.ui.AttendanceViewModel
import app.nagatani.dev.attendance.ui.ExportScope
import app.nagatani.dev.attendance.ui.screens.AttendanceKioskScreen
import app.nagatani.dev.attendance.ui.screens.CourseManagementScreen
import app.nagatani.dev.attendance.ui.screens.ExportAndHistoryScreen
import app.nagatani.dev.attendance.ui.screens.MifareSettingsScreen
import app.nagatani.dev.attendance.ui.theme.UniversityAttendanceTheme
import app.nagatani.dev.attendance.usb.UsbReaderState

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private val viewModel: AttendanceViewModel by viewModels()
    private var nfcAdapter: NfcAdapter? = null
    private var isNfcEnabledState by mutableStateOf(false)

    // 所定の保存先フォルダ未設定時にエクスポートボタンを押した場合、フォルダ選択後に続けて出力するための保持変数
    private var pendingScopeAfterFolderPick: ExportScope? = null

    // SAF: 所定の保存先フォルダ選択 (OpenDocumentTree)
    private val pickDefaultDirectoryLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                contentResolver.takePersistableUriPermission(treeUri, takeFlags)
            }
            val currentExportConfig = viewModel.exportConfig.value
            viewModel.saveExportConfig(
                currentExportConfig.copy(defaultDirectoryUri = treeUri.toString())
            )
            viewModel.postMessage("所定の保存先フォルダを設定しました")

            // フォルダ選択待ちだったエクスポートがあれば続けて実行
            val pending = pendingScopeAfterFolderPick
            pendingScopeAfterFolderPick = null
            if (pending != null) {
                viewModel.exportToDesignatedFolder(
                    context = this,
                    scope = pending,
                    onNeedDirectorySelection = {}
                )
            }
        } else {
            pendingScopeAfterFolderPick = null
        }
    }

    // SAF: 個別のファイル保存ダイアログ (CreateDocument)
    private val createCsvDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { targetUri ->
        viewModel.onExportDestinationUriPicked(this, targetUri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        isNfcEnabledState = nfcAdapter?.isEnabled == true

        // 起動時のIntentにNFC Tagが含まれていた場合の処理
        extractTagFromIntent(intent)?.let { tag ->
            viewModel.onNfcTagDiscovered(tag)
        }

        setContent {
            UniversityAttendanceTheme {
                UniversityAttendanceTabletApp(
                    viewModel = viewModel,
                    isNfcAvailable = nfcAdapter != null,
                    isNfcEnabled = isNfcEnabledState,
                    onPickDefaultFolder = {
                        pendingScopeAfterFolderPick = null
                        runCatching {
                            pickDefaultDirectoryLauncher.launch(null)
                        }.onFailure {
                            viewModel.postMessage(
                                "この端末(Android Go等)ではフォルダ選択UIが省略されているため、標準のDownloadフォルダを所定の場所として使用します"
                            )
                        }
                    },
                    onRequestDefaultFolderThenExport = { scope ->
                        pendingScopeAfterFolderPick = scope
                        runCatching {
                            pickDefaultDirectoryLauncher.launch(null)
                        }.onFailure {
                            pendingScopeAfterFolderPick = null
                            viewModel.exportToDesignatedFolder(
                                context = this@MainActivity,
                                scope = scope,
                                onNeedDirectorySelection = {}
                            )
                        }
                    },
                    onLaunchCreateDocument = { suggestedFileName ->
                        runCatching {
                            createCsvDocumentLauncher.launch(suggestedFileName)
                        }.onFailure {
                            viewModel.postMessage(
                                "ファイル選択ダイアログを開けない端末のため、「所定の場所へ出力」または「共有」をご利用ください"
                            )
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter
        isNfcEnabledState = adapter?.isEnabled == true
        if (adapter != null && adapter.isEnabled) {
            val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
            adapter.enableReaderMode(this, this, flags, null)
        }
        viewModel.scanUsbReaders()
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            viewModel.scanUsbReaders()
        }
        extractTagFromIntent(intent)?.let { tag ->
            viewModel.onNfcTagDiscovered(tag)
        }
    }

    override fun onTagDiscovered(tag: Tag?) {
        if (tag != null) {
            viewModel.onNfcTagDiscovered(tag)
        }
    }

    private fun extractTagFromIntent(intent: Intent?): Tag? {
        if (intent == null) return null
        val action = intent.action
        if (action == NfcAdapter.ACTION_TECH_DISCOVERED ||
            action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            action == NfcAdapter.ACTION_NDEF_DISCOVERED
        ) {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }
        }
        return null
    }
}

@Composable
fun UniversityAttendanceTabletApp(
    viewModel: AttendanceViewModel,
    isNfcAvailable: Boolean,
    isNfcEnabled: Boolean,
    onPickDefaultFolder: () -> Unit,
    onRequestDefaultFolderThenExport: (ExportScope) -> Unit,
    onLaunchCreateDocument: (String) -> Unit
) {
    val context = LocalContext.current
    val currentTab by viewModel.currentTab.collectAsState()
    val courses by viewModel.courses.collectAsState()
    val selectedCourseId by viewModel.selectedCourseId.collectAsState()
    val sessions by viewModel.sessionsForSelectedCourse.collectAsState()
    val selectedSessionId by viewModel.selectedSessionId.collectAsState()
    val sessionAttendance by viewModel.attendanceForSelectedSession.collectAsState()
    val allExportRows by viewModel.allAttendanceRows.collectAsState()
    val mifareConfig by viewModel.mifareConfig.collectAsState()
    val exportConfig by viewModel.exportConfig.collectAsState()
    val scanBannerState by viewModel.scanBannerState.collectAsState()
    val lastDiagnosticResult by viewModel.lastDiagnosticResult.collectAsState()
    val usbReaderState by viewModel.usbReaderState.collectAsState()
    val detectedUsbDevices by viewModel.detectedUsbDevices.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusMessage) {
        val msg = statusMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    val selectedCourse = courses.find { it.id == selectedCourseId }
    val selectedSession = sessions.find { it.session.id == selectedSessionId }?.session

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        // 出席受付キオスク画面のときは全画面表示（サイドナビゲーションを隠して学生の誤操作を防止）
        if (currentTab == AppTab.ATTENDANCE_KIOSK) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                AttendanceKioskScreen(
                    selectedCourse = selectedCourse,
                    selectedSession = selectedSession,
                    attendanceRecords = sessionAttendance,
                    scanBannerState = scanBannerState,
                    mifareConfig = mifareConfig,
                    isNfcAvailable = isNfcAvailable,
                    isNfcEnabled = isNfcEnabled,
                    usbReaderState = usbReaderState,
                    onScanUsbReader = { viewModel.scanUsbReaders() },
                    onRegisterManualAttendance = { studentId, note ->
                        viewModel.registerManualAttendance(studentId, note)
                    },
                    onSimulateCardTap = { simId ->
                        viewModel.simulateCardTap(simId)
                    },
                    onExportCurrentSession = {
                        if (selectedCourse != null && selectedSession != null) {
                            val scope = ExportScope.SingleSession(
                                sessionId = selectedSession.id,
                                courseCode = selectedCourse.courseCode,
                                sessionNumber = selectedSession.sessionNumber
                            )
                            viewModel.exportToDesignatedFolder(
                                context = context,
                                scope = scope,
                                onNeedDirectorySelection = {
                                    onRequestDefaultFolderThenExport(scope)
                                }
                            )
                        } else {
                            viewModel.postMessage("講義と講義回が選択されていません")
                        }
                    },
                    onExitKioskMode = {
                        viewModel.selectTab(AppTab.COURSE_MANAGEMENT)
                    }
                )
            }
        } else {
            // タブレット向け：左サイド NavigationRail ＋ 上部ステータスバー ＋ メインコンテンツ
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxHeight()
                ) {
                    Spacer(modifier = Modifier.height(16.dp))
                    NavigationRailItem(
                        selected = currentTab == AppTab.COURSE_MANAGEMENT,
                        onClick = { viewModel.selectTab(AppTab.COURSE_MANAGEMENT) },
                        icon = { Icon(Icons.Default.School, contentDescription = null) },
                        label = { Text("講義・回管理") }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    NavigationRailItem(
                        selected = currentTab == AppTab.ATTENDANCE_KIOSK,
                        onClick = { viewModel.selectTab(AppTab.ATTENDANCE_KIOSK) },
                        icon = { Icon(Icons.Default.Nfc, contentDescription = null) },
                        label = { Text("出席受付画面") }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    NavigationRailItem(
                        selected = currentTab == AppTab.EXPORT_HISTORY,
                        onClick = { viewModel.selectTab(AppTab.EXPORT_HISTORY) },
                        icon = { Icon(Icons.Default.FolderSpecial, contentDescription = null) },
                        label = { Text("CSV出力・履歴") }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    NavigationRailItem(
                        selected = currentTab == AppTab.MIFARE_SETTINGS,
                        onClick = { viewModel.selectTab(AppTab.MIFARE_SETTINGS) },
                        icon = { Icon(Icons.Default.Key, contentDescription = null) },
                        label = { Text("MIFARE鍵設定") }
                    )
                }

                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    // 上部ヘッダーバー
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "大学講義 出席管理システム",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                if (selectedCourse != null && selectedSession != null) {
                                    Surface(
                                        color = Color.White.copy(alpha = 0.18f),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = "選択中: ${selectedCourse.courseName} / 第${selectedSession.sessionNumber}回",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 外付けUSB NFCリーダーステータスピル
                                val (usbBadgeColor, usbBadgeText) = when (val st = usbReaderState) {
                                    is UsbReaderState.Connected -> Color(0xFF059669) to "USBリーダー: ${st.deviceName}"
                                    is UsbReaderState.PermissionRequired -> Color(0xFFD97706) to "USBリーダー: 権限待ち"
                                    is UsbReaderState.Error -> Color(0xFFDC2626) to "USBリーダー: エラー(タップで再検索)"
                                    is UsbReaderState.Disconnected -> Color(0xFF475569) to "USBリーダー: 未接続(タップで再検索)"
                                }
                                Surface(
                                    color = usbBadgeColor,
                                    shape = RoundedCornerShape(20.dp),
                                    modifier = Modifier.clickable { viewModel.scanUsbReaders() }
                                ) {
                                    Text(
                                        text = usbBadgeText,
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }

                                // 本体NFCステータス表示ピル
                                val nfcBadgeColor = when {
                                    !isNfcAvailable -> Color(0xFF64748B)
                                    !isNfcEnabled -> Color(0xFFD97706)
                                    else -> Color(0xFF0284C7)
                                }
                                val nfcBadgeText = when {
                                    !isNfcAvailable -> "本体NFC: 非搭載"
                                    !isNfcEnabled -> "本体NFC: OFF"
                                    else -> "本体NFC: 待機中"
                                }
                                Surface(
                                    color = nfcBadgeColor,
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Text(
                                        text = nfcBadgeText,
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 各タブのメインコンテンツ
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (currentTab) {
                            AppTab.COURSE_MANAGEMENT -> {
                                CourseManagementScreen(
                                    courses = courses,
                                    selectedCourseId = selectedCourseId,
                                    sessions = sessions,
                                    selectedSessionId = selectedSessionId,
                                    attendanceRecords = sessionAttendance,
                                    onSelectCourse = { viewModel.selectCourse(it) },
                                    onSelectSession = { viewModel.selectSession(it) },
                                    onCreateCourse = { code, name, instructor, room, auto15 ->
                                        viewModel.createCourse(code, name, instructor, room, auto15)
                                    },
                                    onDeleteCourse = { viewModel.deleteCourse(it) },
                                    onCreateSession = { cId, sNum, title, date ->
                                        viewModel.createSession(cId, sNum, title, date)
                                    },
                                    onGenerate15Sessions = { viewModel.generateRemaining15Sessions(it) },
                                    onStartAttendanceKiosk = { cId, sId ->
                                        viewModel.startAttendanceKiosk(cId, sId)
                                    },
                                    onRegisterManualAttendance = { studentId, note ->
                                        viewModel.registerManualAttendance(studentId, note)
                                    },
                                    onDeleteAttendance = { viewModel.deleteAttendanceRecord(it) },
                                    onExportSessionToDefaultFolder = { sId, cCode, sNum ->
                                        val scope = ExportScope.SingleSession(sId, cCode, sNum)
                                        viewModel.exportToDesignatedFolder(
                                            context = context,
                                            scope = scope,
                                            onNeedDirectorySelection = {
                                                onRequestDefaultFolderThenExport(scope)
                                            }
                                        )
                                    },
                                    onExportSessionViaFilePicker = { sId, cCode, sNum ->
                                        val scope = ExportScope.SingleSession(sId, cCode, sNum)
                                        viewModel.prepareFilePickerExport(scope, onLaunchCreateDocument)
                                    },
                                    onShareSessionCsv = { sId, cCode, sNum ->
                                        val scope = ExportScope.SingleSession(sId, cCode, sNum)
                                        viewModel.shareCsv(context, scope)
                                    }
                                )
                            }

                            AppTab.EXPORT_HISTORY -> {
                                ExportAndHistoryScreen(
                                    allRows = allExportRows,
                                    courses = courses,
                                    exportConfig = exportConfig,
                                    onUpdateExportConfig = { viewModel.saveExportConfig(it) },
                                    onPickDefaultDirectory = onPickDefaultFolder,
                                    onExportToDefaultFolder = { scope ->
                                        viewModel.exportToDesignatedFolder(
                                            context = context,
                                            scope = scope,
                                            onNeedDirectorySelection = {
                                                onRequestDefaultFolderThenExport(scope)
                                            }
                                        )
                                    },
                                    onExportViaFilePicker = { scope ->
                                        viewModel.prepareFilePickerExport(scope, onLaunchCreateDocument)
                                    },
                                    onShareCsv = { scope ->
                                        viewModel.shareCsv(context, scope)
                                    },
                                    onDeleteRecordById = { recId, stuId ->
                                        viewModel.deleteAttendanceRecordById(recId, stuId)
                                    }
                                )
                            }

                            AppTab.MIFARE_SETTINGS -> {
                                MifareSettingsScreen(
                                    currentConfig = mifareConfig,
                                    lastDiagnosticResult = lastDiagnosticResult,
                                    usbReaderState = usbReaderState,
                                    detectedUsbDevices = detectedUsbDevices,
                                    onScanUsbReaders = { viewModel.scanUsbReaders() },
                                    onConnectUsbDevice = { viewModel.connectUsbDevice(it) },
                                    onDisconnectUsbReader = { viewModel.disconnectUsbReader() },
                                    onSaveConfig = { viewModel.saveMifareConfig(it) },
                                    onSimulateDiagnosticCard = { sampleId ->
                                        viewModel.simulateCardTap(sampleId)
                                    }
                                )
                            }

                            AppTab.ATTENDANCE_KIOSK -> {
                                // 上部if分岐で処理済み
                            }
                        }
                    }
                }
            }
        }
    }
}
