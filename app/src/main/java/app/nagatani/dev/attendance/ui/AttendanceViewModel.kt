package app.nagatani.dev.attendance.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.nfc.Tag
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.nagatani.dev.attendance.data.local.AppDatabase
import app.nagatani.dev.attendance.data.local.AttendanceDao
import app.nagatani.dev.attendance.data.local.AttendanceExportRow
import app.nagatani.dev.attendance.data.local.AttendanceRecordEntity
import app.nagatani.dev.attendance.data.local.CourseEntity
import app.nagatani.dev.attendance.data.local.LectureSessionEntity
import app.nagatani.dev.attendance.data.local.SessionWithCount
import app.nagatani.dev.attendance.data.preferences.ExportConfig
import app.nagatani.dev.attendance.data.preferences.MifareConfig
import app.nagatani.dev.attendance.data.preferences.MifareSettingsRepository
import app.nagatani.dev.attendance.export.CsvExportManager
import app.nagatani.dev.attendance.nfc.MifareClassicReader
import app.nagatani.dev.attendance.nfc.MifareReadResult
import app.nagatani.dev.attendance.usb.UsbDeviceSummary
import app.nagatani.dev.attendance.usb.UsbNfcReaderManager
import app.nagatani.dev.attendance.usb.UsbReaderState
import app.nagatani.dev.attendance.util.SoundFeedbackManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * アプリ内のメイン画面タブ
 */
enum class AppTab(val title: String) {
    COURSE_MANAGEMENT("講義・講義回管理"),
    ATTENDANCE_KIOSK("出席受付モード"),
    EXPORT_HISTORY("データ出力・履歴"),
    MIFARE_SETTINGS("MIFARE鍵・読取設定")
}

/**
 * 出席受付モード画面に表示するタッチ結果バナー状態
 */
sealed class ScanBannerState {
    data object Standby : ScanBannerState()

    data class Success(
        val studentId: String,
        val cardUid: String,
        val registeredTimeText: String,
        val method: String
    ) : ScanBannerState()

    data class Duplicate(
        val studentId: String,
        val firstRegisteredTimeText: String
    ) : ScanBannerState()

    data class Error(
        val title: String,
        val message: String,
        val cardUid: String
    ) : ScanBannerState()
}

/**
 * CSVエクスポート対象範囲の指定
 */
sealed class ExportScope {
    data class SingleSession(val sessionId: Long, val courseCode: String, val sessionNumber: Int) : ExportScope()
    data class SingleCourse(val courseId: Long, val courseCode: String) : ExportScope()
    data object AllRecords : ExportScope()
}

@OptIn(ExperimentalCoroutinesApi::class)
class AttendanceViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val dao: AttendanceDao = database.attendanceDao()
    private val settingsRepo = MifareSettingsRepository(application)

    // 現在選択中のタブ
    private val _currentTab = MutableStateFlow(AppTab.COURSE_MANAGEMENT)
    val currentTab: StateFlow<AppTab> = _currentTab.asStateFlow()

    // MIFARE設定・エクスポート設定
    val mifareConfig: StateFlow<MifareConfig> = settingsRepo.mifareConfig
    val exportConfig: StateFlow<ExportConfig> = settingsRepo.exportConfig

    // MIFARE設定画面で編集中（未保存）のドラフト設定（設定画面でのリアルタイム診断・USB読取に即時反映）
    private val _liveDraftMifareConfig = MutableStateFlow<MifareConfig?>(null)

    fun updateLiveDraftMifareConfig(draft: MifareConfig?) {
        _liveDraftMifareConfig.value = draft
    }

    private fun getEffectiveMifareConfig(): MifareConfig {
        return if (_currentTab.value == AppTab.MIFARE_SETTINGS) {
            _liveDraftMifareConfig.value ?: mifareConfig.value
        } else {
            mifareConfig.value
        }
    }

    // 講義一覧
    val courses: StateFlow<List<CourseEntity>> = dao.observeAllCourses()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 選択中の講義ID
    private val _selectedCourseId = MutableStateFlow<Long?>(null)
    val selectedCourseId: StateFlow<Long?> = _selectedCourseId.asStateFlow()

    // 選択中の講義に紐づく講義回一覧（出席者数つき）
    val sessionsForSelectedCourse: StateFlow<List<SessionWithCount>> = _selectedCourseId
        .flatMapLatest { courseId ->
            if (courseId == null) flowOf(emptyList())
            else dao.observeSessionsWithCountByCourse(courseId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 選択中の講義回ID
    private val _selectedSessionId = MutableStateFlow<Long?>(null)
    val selectedSessionId: StateFlow<Long?> = _selectedSessionId.asStateFlow()

    // 選択中の講義回に紐づく出席レコード一覧
    val attendanceForSelectedSession: StateFlow<List<AttendanceRecordEntity>> = _selectedSessionId
        .flatMapLatest { sessionId ->
            if (sessionId == null) flowOf(emptyList())
            else dao.observeAttendanceBySession(sessionId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 全出席データ（履歴・エクスポート画面用）
    val allAttendanceRows: StateFlow<List<AttendanceExportRow>> = dao.observeAllAttendanceExportRows()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 出席受付モードのバナー表示状態
    private val _scanBannerState = MutableStateFlow<ScanBannerState>(ScanBannerState.Standby)
    val scanBannerState: StateFlow<ScanBannerState> = _scanBannerState.asStateFlow()

    // MIFARE設定画面でのリアルタイム診断結果（直近にかざされたカードの16バイト生データ等）
    private val _lastDiagnosticResult = MutableStateFlow<MifareReadResult?>(null)
    val lastDiagnosticResult: StateFlow<MifareReadResult?> = _lastDiagnosticResult.asStateFlow()

    // 画面下部に通知するステータスメッセージ
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    // 連続タッチチャタリング防止用（同一UIDの1.8秒以内の再検知を無視。ただし前回失敗→今回成功の場合は無視しない）
    private var lastScannedUid: String = ""
    private var lastScanEpochMillis: Long = 0L
    private var lastScanWasSuccess: Boolean = false
    private var bannerResetJob: Job? = null

    // SAFファイル保存ダイアログ待機中の一時データ保持
    private var pendingExportRows: List<AttendanceExportRow> = emptyList()

    // 外付けUSB NFCリーダー (CCID / PC/SC) 管理マネージャ
    private val usbReaderManager = UsbNfcReaderManager(
        context = application,
        configProvider = { getEffectiveMifareConfig() },
        onCardReadResult = { result ->
            viewModelScope.launch(Dispatchers.IO) {
                handleMifareReadResult(result, sourceLabel = "NFC(USB)")
            }
        },
        onStatusLog = { msg -> postMessage(msg) }
    )

    val usbReaderState: StateFlow<UsbReaderState> = usbReaderManager.readerState
    val detectedUsbDevices: StateFlow<List<UsbDeviceSummary>> = usbReaderManager.detectedDevices

    init {
        usbReaderManager.start()

        viewModelScope.launch(Dispatchers.IO) {
            AppDatabase.ensureSampleDataIfEmpty(dao)
        }
        // 初回に講義が読み込まれた際、未選択なら先頭の講義を自動選択
        viewModelScope.launch {
            courses.collect { list ->
                if (_selectedCourseId.value == null && list.isNotEmpty()) {
                    _selectedCourseId.value = list.first().id
                } else if (_selectedCourseId.value != null && list.none { it.id == _selectedCourseId.value }) {
                    _selectedCourseId.value = list.firstOrNull()?.id
                }
            }
        }
        // 講義回が切り替わった際、未選択なら先頭の講義回を自動選択
        viewModelScope.launch {
            sessionsForSelectedCourse.collect { list ->
                if (_selectedSessionId.value == null && list.isNotEmpty()) {
                    _selectedSessionId.value = list.first().session.id
                } else if (_selectedSessionId.value != null && list.none { it.session.id == _selectedSessionId.value }) {
                    _selectedSessionId.value = list.firstOrNull()?.session?.id
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        usbReaderManager.stop()
    }

    fun scanUsbReaders() {
        usbReaderManager.scanAndAutoConnect()
    }

    fun connectUsbDevice(usbDeviceNameKey: String) {
        usbReaderManager.connectToDeviceByName(usbDeviceNameKey)
    }

    fun disconnectUsbReader() {
        usbReaderManager.disconnectCurrentReader()
        postMessage("USB NFCリーダーを切断しました")
    }

    fun selectTab(tab: AppTab) {
        _currentTab.value = tab
    }

    fun selectCourse(courseId: Long) {
        _selectedCourseId.value = courseId
    }

    fun selectSession(sessionId: Long) {
        _selectedSessionId.value = sessionId
    }

    /**
     * 指定した講義・講義回で「出席受付モード（キオスク画面）」を開始する
     */
    fun startAttendanceKiosk(courseId: Long, sessionId: Long) {
        _selectedCourseId.value = courseId
        _selectedSessionId.value = sessionId
        _scanBannerState.value = ScanBannerState.Standby
        _currentTab.value = AppTab.ATTENDANCE_KIOSK
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun postMessage(msg: String) {
        _statusMessage.value = msg
    }

    // =========================================================
    // 講義・講義回の追加・編集・削除
    // =========================================================

    fun createCourse(
        courseCode: String,
        courseName: String,
        instructorName: String,
        scheduleAndRoom: String,
        autoCreate15Sessions: Boolean
    ) {
        if (courseName.isBlank()) {
            postMessage("講義名を入力してください")
            return
        }
        val code = courseCode.trim().ifEmpty { "COURSE-${System.currentTimeMillis() % 10000}" }
        viewModelScope.launch(Dispatchers.IO) {
            val newCourseId = dao.insertCourse(
                CourseEntity(
                    courseCode = code,
                    courseName = courseName.trim(),
                    instructorName = instructorName.trim(),
                    scheduleAndRoom = scheduleAndRoom.trim()
                )
            )
            if (autoCreate15Sessions) {
                val today = LocalDate.now()
                val sessions = (1..15).map { num ->
                    LectureSessionEntity(
                        courseId = newCourseId,
                        sessionNumber = num,
                        sessionTitle = "第${num}回 講義",
                        sessionDate = today.plusWeeks((num - 1).toLong()).toString()
                    )
                }
                dao.insertSessions(sessions)
            }
            _selectedCourseId.value = newCourseId
            postMessage("講義「${courseName.trim()}」を作成しました")
        }
    }

    fun deleteCourse(course: CourseEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteCourse(course)
            postMessage("講義「${course.courseName}」を削除しました")
        }
    }

    fun createSession(
        courseId: Long,
        sessionNumber: Int,
        sessionTitle: String,
        sessionDate: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val newId = dao.insertSession(
                LectureSessionEntity(
                    courseId = courseId,
                    sessionNumber = sessionNumber.coerceAtLeast(1),
                    sessionTitle = sessionTitle.trim().ifEmpty { "第${sessionNumber}回 講義" },
                    sessionDate = sessionDate.trim().ifEmpty { LocalDate.now().toString() }
                )
            )
            if (newId == -1L) {
                postMessage("第${sessionNumber}回は既に登録されています")
            } else {
                _selectedSessionId.value = newId
                postMessage("第${sessionNumber}回を追加しました")
            }
        }
    }

    fun generateRemaining15Sessions(courseId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val today = LocalDate.now()
            val sessions = (1..15).map { num ->
                LectureSessionEntity(
                    courseId = courseId,
                    sessionNumber = num,
                    sessionTitle = "第${num}回 講義",
                    sessionDate = today.plusWeeks((num - 1).toLong()).toString()
                )
            }
            dao.insertSessions(sessions)
            postMessage("第1回〜第15回の講義回を一括整備しました")
        }
    }

    fun deleteSession(session: LectureSessionEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteSession(session)
            postMessage("第${session.sessionNumber}回を削除しました")
        }
    }

    // =========================================================
    // NFC / MIFARE Classic 読み取り & 出席登録処理
    // =========================================================

    /**
     * 実機のNFCリーダーがTagを検知したときにMainActivityから呼ばれるエントリポイント
     */
    fun onNfcTagDiscovered(tag: Tag) {
        viewModelScope.launch(Dispatchers.IO) {
            val config = getEffectiveMifareConfig()
            val result = MifareClassicReader.readStudentCard(tag, config)
            handleMifareReadResult(result, sourceLabel = "NFC(本体)")
        }
    }

    /**
     * テスト・動作確認用に模擬MIFARE Classicカード読み取りを実行する
     */
    fun simulateCardTap(simulatedStudentId: String = "24B10234") {
        viewModelScope.launch(Dispatchers.IO) {
            val config = getEffectiveMifareConfig()
            val rawBlock = MifareClassicReader.createSimulatedBlock(
                studentIdText = simulatedStudentId,
                offset = config.byteOffset
            )
            val uidHex = String.format("04%010X", simulatedStudentId.hashCode().toLong() and 0xFFFFFFFFFFL)
            val result = when (val outcome = MifareClassicReader.extractStudentIdFromBlock(rawBlock, config)) {
                is MifareClassicReader.BlockParseOutcome.Valid -> MifareReadResult.Success(
                    cardUid = uidHex,
                    studentId = outcome.studentId,
                    rawBlockBytes = rawBlock,
                    sectorIndex = config.sectorIndex,
                    blockIndexInSector = config.blockIndexInSector,
                    absoluteBlockIndex = config.sectorIndex * 4 + config.blockIndexInSector,
                    cardTypeDescription = "MIFARE Classic 1K (シミュレーション)"
                )
                is MifareClassicReader.BlockParseOutcome.Invalid -> MifareReadResult.ParseFailed(
                    cardUid = uidHex,
                    rawBlockBytes = rawBlock,
                    sectorIndex = config.sectorIndex,
                    blockIndexInSector = config.blockIndexInSector,
                    absoluteBlockIndex = config.sectorIndex * 4 + config.blockIndexInSector,
                    reason = outcome.reason
                )
            }
            handleMifareReadResult(result, sourceLabel = "NFC(テスト)")
        }
    }

    private suspend fun handleMifareReadResult(
        result: MifareReadResult,
        sourceLabel: String = "NFC"
    ) {
        // 常に最新のカード診断結果として保持（MIFARE設定タブでの確認用）
        _lastDiagnosticResult.value = result

        val now = System.currentTimeMillis()
        val isSuccess = result is MifareReadResult.Success
        // 同一カードがかざされっぱなしの場合のチャタリング防止（1.8秒以内）
        // ※ただし前回が失敗で今回リトライ成功した場合や、MIFARE設定画面での診断時はスキップしない
        if (_currentTab.value != AppTab.MIFARE_SETTINGS &&
            result.cardUid.isNotEmpty() &&
            result.cardUid == lastScannedUid &&
            (now - lastScanEpochMillis) < 1800L &&
            !(isSuccess && !lastScanWasSuccess)
        ) {
            return
        }
        lastScannedUid = result.cardUid
        lastScanEpochMillis = now
        lastScanWasSuccess = isSuccess

        val soundEnabled = exportConfig.value.soundEnabled

        // MIFARE設定画面を開いているときは出席登録せず診断結果のみ更新して通知
        if (_currentTab.value == AppTab.MIFARE_SETTINGS) {
            when (result) {
                is MifareReadResult.Success -> {
                    SoundFeedbackManager.playSuccessTone(soundEnabled)
                    postMessage("[$sourceLabel] カード診断成功: 学籍番号「${result.studentId}」を抽出しました")
                }
                is MifareReadResult.AuthFailed -> {
                    SoundFeedbackManager.playErrorTone(soundEnabled)
                    postMessage("[$sourceLabel] セクター認証失敗: ${result.message}")
                }
                is MifareReadResult.ParseFailed -> {
                    SoundFeedbackManager.playErrorTone(soundEnabled)
                    postMessage("[$sourceLabel] ブロック読取成功・抽出エラー: ${result.reason}")
                }
                is MifareReadResult.Error -> {
                    SoundFeedbackManager.playErrorTone(soundEnabled)
                    postMessage("[$sourceLabel] ${result.title}: ${result.message}")
                }
            }
            return
        }

        // 講義・講義回が選択されているか確認
        val courseId = _selectedCourseId.value
        val sessionId = _selectedSessionId.value
        if (courseId == null || sessionId == null) {
            SoundFeedbackManager.playErrorTone(soundEnabled)
            updateScanBanner(
                ScanBannerState.Error(
                    title = "講義回が未選択です",
                    message = "先に出席を登録する「講義」と「講義回」を選択してください。",
                    cardUid = result.cardUid
                )
            )
            postMessage("出席登録を行うには講義と講義回を選択してください")
            return
        }

        when (result) {
            is MifareReadResult.Success -> {
                val methodStr = if (result.isUidFallback) "${sourceLabel}-UID" else sourceLabel
                registerAttendanceInternal(
                    courseId = courseId,
                    sessionId = sessionId,
                    studentId = result.studentId,
                    cardUid = result.cardUid,
                    method = methodStr,
                    note = ""
                )
            }

            is MifareReadResult.AuthFailed -> {
                SoundFeedbackManager.playErrorTone(soundEnabled)
                updateScanBanner(
                    ScanBannerState.Error(
                        title = "MIFARE Classic 鍵認証エラー",
                        message = result.message,
                        cardUid = result.cardUid
                    )
                )
            }

            is MifareReadResult.ParseFailed -> {
                SoundFeedbackManager.playErrorTone(soundEnabled)
                updateScanBanner(
                    ScanBannerState.Error(
                        title = "学籍番号フォーマットエラー",
                        message = result.reason,
                        cardUid = result.cardUid
                    )
                )
            }

            is MifareReadResult.Error -> {
                SoundFeedbackManager.playErrorTone(soundEnabled)
                updateScanBanner(
                    ScanBannerState.Error(
                        title = result.title,
                        message = result.message,
                        cardUid = result.cardUid
                    )
                )
            }
        }
    }

    /**
     * 手動で学籍番号を入力して出席登録する
     */
    fun registerManualAttendance(studentIdInput: String, noteInput: String) {
        val cleanedId = studentIdInput.trim()
        if (cleanedId.isEmpty()) {
            postMessage("学籍番号を入力してください")
            return
        }
        val courseId = _selectedCourseId.value
        val sessionId = _selectedSessionId.value
        if (courseId == null || sessionId == null) {
            postMessage("講義と講義回を選択してください")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            registerAttendanceInternal(
                courseId = courseId,
                sessionId = sessionId,
                studentId = cleanedId,
                cardUid = "",
                method = "MANUAL",
                note = noteInput.trim()
            )
        }
    }

    private suspend fun registerAttendanceInternal(
        courseId: Long,
        sessionId: Long,
        studentId: String,
        cardUid: String,
        method: String,
        note: String
    ) {
        val soundEnabled = exportConfig.value.soundEnabled
        val existing = dao.findAttendanceRecord(sessionId = sessionId, studentId = studentId)

        if (existing != null) {
            val firstTimeStr = CsvExportManager.formatEpochMillis(existing.registeredAt)
            SoundFeedbackManager.playDuplicateTone(soundEnabled)
            updateScanBanner(
                ScanBannerState.Duplicate(
                    studentId = studentId,
                    firstRegisteredTimeText = firstTimeStr
                )
            )
            if (_currentTab.value != AppTab.ATTENDANCE_KIOSK) {
                postMessage("学籍番号 $studentId は既に出席登録済みです ($firstTimeStr)")
            }
            return
        }

        val now = System.currentTimeMillis()
        val record = AttendanceRecordEntity(
            courseId = courseId,
            sessionId = sessionId,
            studentId = studentId,
            cardUid = cardUid,
            registeredAt = now,
            registrationMethod = method,
            note = note
        )
        dao.insertAttendance(record)

        val formattedNow = CsvExportManager.formatEpochMillis(now)
        SoundFeedbackManager.playSuccessTone(soundEnabled)
        updateScanBanner(
            ScanBannerState.Success(
                studentId = studentId,
                cardUid = cardUid,
                registeredTimeText = formattedNow,
                method = method
            )
        )
        if (_currentTab.value != AppTab.ATTENDANCE_KIOSK) {
            postMessage("出席登録完了: $studentId ($formattedNow)")
        }
    }

    private fun updateScanBanner(newState: ScanBannerState) {
        _scanBannerState.value = newState
        bannerResetJob?.cancel()
        if (newState !is ScanBannerState.Standby) {
            bannerResetJob = viewModelScope.launch {
                delay(3800L)
                _scanBannerState.value = ScanBannerState.Standby
            }
        }
    }

    fun deleteAttendanceRecord(record: AttendanceRecordEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteAttendance(record)
            postMessage("学籍番号 ${record.studentId} の出席記録を削除しました")
        }
    }

    fun deleteAttendanceRecordById(recordId: Long, studentId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteAttendanceById(recordId)
            postMessage("学籍番号 $studentId の出席記録を削除しました")
        }
    }

    // =========================================================
    // MIFARE設定およびエクスポート設定の更新
    // =========================================================

    fun saveMifareConfig(config: MifareConfig) {
        settingsRepo.updateMifareConfig(config)
        postMessage("MIFARE Classic 読み取り設定を保存しました")
    }

    fun saveExportConfig(config: ExportConfig) {
        settingsRepo.updateExportConfig(config)
    }

    // =========================================================
    // CSVエクスポート処理（所定の場所へワンタップ保存 / ファイル選択保存 / 共有）
    // =========================================================

    private suspend fun fetchRowsForScope(scope: ExportScope): List<AttendanceExportRow> {
        return when (scope) {
            is ExportScope.SingleSession -> dao.getExportRowsBySession(scope.sessionId)
            is ExportScope.SingleCourse -> dao.getExportRowsByCourse(scope.courseId)
            is ExportScope.AllRecords -> dao.getAllExportRows()
        }
    }

    private fun buildFileNameForScope(scope: ExportScope): String {
        return when (scope) {
            is ExportScope.SingleSession -> CsvExportManager.generateDefaultFileName(
                courseCode = scope.courseCode,
                sessionNumber = scope.sessionNumber
            )
            is ExportScope.SingleCourse -> CsvExportManager.generateDefaultFileName(
                courseCode = scope.courseCode,
                sessionNumber = null
            )
            is ExportScope.AllRecords -> CsvExportManager.generateDefaultFileName()
        }
    }

    /**
     * 方法1: 設定済みの「所定の保存先フォルダ」へワンタップでCSV出力する
     */
    fun exportToDesignatedFolder(
        context: Context,
        scope: ExportScope,
        onNeedDirectorySelection: () -> Unit
    ) {
        val dirUri = exportConfig.value.defaultDirectoryUri
        if (dirUri.isBlank()) {
            onNeedDirectorySelection()
            return
        }

        viewModelScope.launch {
            val rows = fetchRowsForScope(scope)
            if (rows.isEmpty()) {
                postMessage("エクスポート対象の出席データが0件です")
                return@launch
            }
            val fileName = buildFileNameForScope(scope)
            val result = CsvExportManager.exportToDesignatedDirectory(
                context = context,
                directoryTreeUriString = dirUri,
                fileName = fileName,
                rows = rows,
                includeUtf8Bom = exportConfig.value.includeUtf8Bom
            )
            result.fold(
                onSuccess = { pathInfo ->
                    postMessage("所定の場所へCSV出力完了: $pathInfo")
                },
                onFailure = { err ->
                    postMessage("CSV出力エラー: ${err.localizedMessage ?: "保存に失敗しました"}")
                }
            )
        }
    }

    /**
     * 方法2: SAF ファイル選択ダイアログ (CreateDocument) で任意の場所へ保存する準備
     */
    fun prepareFilePickerExport(
        scope: ExportScope,
        launchCreateDocument: (suggestedFileName: String) -> Unit
    ) {
        viewModelScope.launch {
            val rows = fetchRowsForScope(scope)
            if (rows.isEmpty()) {
                postMessage("エクスポート対象の出席データが0件です")
                return@launch
            }
            pendingExportRows = rows
            val suggestedName = buildFileNameForScope(scope)
            launchCreateDocument(suggestedName)
        }
    }

    /**
     * SAF ファイル選択ダイアログで保存先URIが決定した後に呼ばれるコールバック
     */
    fun onExportDestinationUriPicked(context: Context, targetUri: Uri?) {
        if (targetUri == null) return
        val rowsToWrite = pendingExportRows
        if (rowsToWrite.isEmpty()) {
            postMessage("出力対象データがありません")
            return
        }
        viewModelScope.launch {
            val result = CsvExportManager.writeCsvToUri(
                context = context,
                targetUri = targetUri,
                rows = rowsToWrite,
                includeUtf8Bom = exportConfig.value.includeUtf8Bom
            )
            result.fold(
                onSuccess = { count ->
                    postMessage("CSVファイルを保存しました（全${count}件）")
                },
                onFailure = { err ->
                    postMessage("保存エラー: ${err.localizedMessage}")
                }
            )
        }
    }

    /**
     * 方法3: Googleドライブ・メール・ファイルアプリ等の共有シートでCSVを送信する
     */
    fun shareCsv(context: Context, scope: ExportScope) {
        viewModelScope.launch {
            val rows = fetchRowsForScope(scope)
            if (rows.isEmpty()) {
                postMessage("共有対象の出席データが0件です")
                return@launch
            }
            val fileName = buildFileNameForScope(scope)
            val result = CsvExportManager.shareCsvFile(
                context = context,
                fileName = fileName,
                rows = rows,
                includeUtf8Bom = exportConfig.value.includeUtf8Bom
            )
            result.onFailure { err ->
                postMessage("共有起動エラー: ${err.localizedMessage}")
            }
        }
    }
}
