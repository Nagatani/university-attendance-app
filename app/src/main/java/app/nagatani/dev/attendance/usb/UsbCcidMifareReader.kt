package app.nagatani.dev.attendance.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.nagatani.dev.attendance.data.preferences.MifareConfig
import app.nagatani.dev.attendance.data.preferences.MifareKeyCandidate
import app.nagatani.dev.attendance.data.preferences.MifareKeyType
import app.nagatani.dev.attendance.nfc.MifareClassicReader
import app.nagatani.dev.attendance.nfc.MifareReadResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * USB接続されたNFCリーダーの状態を表すシールドクラス
 */
sealed class UsbReaderState {
    data object Disconnected : UsbReaderState()

    data class PermissionRequired(
        val deviceName: String,
        val vendorId: Int,
        val productId: Int
    ) : UsbReaderState()

    data class Connected(
        val deviceName: String,
        val vendorId: Int,
        val productId: Int,
        val cardPresent: Boolean = false
    ) : UsbReaderState()

    data class Error(
        val message: String
    ) : UsbReaderState()
}

/**
 * 検出されたUSBデバイスの概要情報（UI一覧表示用）
 */
data class UsbDeviceSummary(
    val usbDeviceNameKey: String,
    val displayName: String,
    val vendorId: Int,
    val productId: Int,
    val isCcidClass: Boolean,
    val isKnownNfcVendor: Boolean,
    val hasPermission: Boolean
) {
    val vidPidHex: String
        get() = String.format("VID:%04X / PID:%04X", vendorId, productId)
}

/**
 * USB CCID (Chip/Smart Card Interface Devices) および PC/SC Part 3 Contactless APDU プロトコル実装
 */
object UsbCcidProtocol {

    // CCID Bulk-OUT メッセージタイプ
    const val MSG_PC_TO_RDR_ICC_POWER_ON: Byte = 0x62
    const val MSG_PC_TO_RDR_ICC_POWER_OFF: Byte = 0x63
    const val MSG_PC_TO_RDR_GET_SLOT_STATUS: Byte = 0x65
    const val MSG_PC_TO_RDR_ESCAPE: Byte = 0x6B
    const val MSG_PC_TO_RDR_XFR_BLOCK: Byte = 0x6F

    // CCID Bulk-IN メッセージタイプ
    const val MSG_RDR_TO_PC_DATA_BLOCK: Byte = 0x80.toByte()
    const val MSG_RDR_TO_PC_SLOT_STATUS: Byte = 0x81.toByte()
    const val MSG_RDR_TO_PC_ESCAPE: Byte = 0x83.toByte()

    // Sony Vendor ID および PaSoRi RC-S300 シリーズ Product ID
    const val SONY_VENDOR_ID: Int = 0x054C
    val SONY_RCS300_PRODUCT_IDS = setOf(
        0x0DC8, // Sony RC-S300/S (業務用・法人向けモデル)
        0x0DC9  // Sony RC-S300 / RC-S300/P (個人向けモデル)
    )

    // 主要な外付けNFCリーダーのUSB Vendor ID一覧
    val KNOWN_NFC_VENDOR_IDS = setOf(
        0x072F, // ACS (ACR122U, ACR1252U, ACR1281U 等)
        SONY_VENDOR_ID, // Sony (PaSoRi RC-S300, RC-S380 等)
        0x04E6, // Identiv / SCM Microsystems (uTrust 3700F 等)
        0x076B, // HID Global (OMNIKEY 5022, 5427 等)
        0x1FC9, // NXP Semiconductors
        0x096E, // Feitian Technologies
        0x08E6, // Thales / Gemalto
        0x31AA  // AB Circle
    )

    // Sony RC-S300 ダイレクトUSB制御用 Escape / Transparent Session コマンド群
    // (PC/SCドライバを介さないAndroid USBホスト接続時にType A / MIFAREモードを確実化するために使用)
    val SONY_CMD_END_TRANSPARENT = byteArrayOf(
        0xFF.toByte(), 0x50, 0x00, 0x00, 0x02, 0x82.toByte(), 0x00, 0x00
    )
    val SONY_CMD_START_TRANSPARENT = byteArrayOf(
        0xFF.toByte(), 0x50, 0x00, 0x00, 0x02, 0x81.toByte(), 0x00, 0x00
    )
    val SONY_CMD_SWITCH_PROTOCOL_TYPE_A = byteArrayOf(
        0xFF.toByte(), 0x50, 0x00, 0x02, 0x04, 0x8F.toByte(), 0x02, 0x00, 0x03, 0x00
    )
    val SONY_CMD_TURN_ON_RF = byteArrayOf(
        0xFF.toByte(), 0x50, 0x00, 0x00, 0x02, 0x84.toByte(), 0x00, 0x00
    )
    val SONY_CMD_TURN_OFF_RF = byteArrayOf(
        0xFF.toByte(), 0x50, 0x00, 0x00, 0x02, 0x83.toByte(), 0x00, 0x00
    )

    /**
     * MIFARE Classic のセクター番号とセクター内ブロック番号から絶対ブロック番号を計算する
     * - MIFARE Classic 1K (Sector 0〜15) および 4Kの前半 (Sector 0〜31): 1セクター = 4ブロック
     * - MIFARE Classic 4K の後半 (Sector 32〜39): 1セクター = 16ブロック
     */
    fun sectorToAbsoluteBlock(sectorIndex: Int, blockIndexInSector: Int): Int {
        return if (sectorIndex < 32) {
            sectorIndex * 4 + blockIndexInSector
        } else {
            32 * 4 + (sectorIndex - 32) * 16 + blockIndexInSector
        }
    }

    /**
     * 10バイトのCCIDヘッダー＋ペイロードを構築する
     */
    fun buildCcidFrame(
        messageType: Byte,
        slot: Byte = 0x00,
        seq: Byte,
        param1: Byte = 0x00,
        param2: Byte = 0x00,
        param3: Byte = 0x00,
        payload: ByteArray = ByteArray(0)
    ): ByteArray {
        val len = payload.size
        val frame = ByteArray(10 + len)
        frame[0] = messageType
        frame[1] = (len and 0xFF).toByte()
        frame[2] = ((len ushr 8) and 0xFF).toByte()
        frame[3] = ((len ushr 16) and 0xFF).toByte()
        frame[4] = ((len ushr 24) and 0xFF).toByte()
        frame[5] = slot
        frame[6] = seq
        frame[7] = param1
        frame[8] = param2
        frame[9] = param3
        if (len > 0) {
            System.arraycopy(payload, 0, frame, 10, len)
        }
        return frame
    }

    /**
     * PC_to_RDR_IccPowerOn (0x62)
     * @param voltageSelect bPowerSelect: 0x00=Auto, 0x01=5.0V, 0x02=3.0V, 0x03=1.8V
     */
    fun buildIccPowerOn(slot: Byte = 0x00, seq: Byte, voltageSelect: Byte = 0x00): ByteArray =
        buildCcidFrame(MSG_PC_TO_RDR_ICC_POWER_ON, slot = slot, seq = seq, param1 = voltageSelect)

    fun buildIccPowerOff(slot: Byte = 0x00, seq: Byte): ByteArray =
        buildCcidFrame(MSG_PC_TO_RDR_ICC_POWER_OFF, slot = slot, seq = seq)

    fun buildGetSlotStatus(slot: Byte = 0x00, seq: Byte): ByteArray =
        buildCcidFrame(MSG_PC_TO_RDR_GET_SLOT_STATUS, slot = slot, seq = seq)

    fun buildXfrBlock(slot: Byte = 0x00, seq: Byte, apdu: ByteArray): ByteArray =
        buildCcidFrame(MSG_PC_TO_RDR_XFR_BLOCK, slot = slot, seq = seq, param1 = 0x00, payload = apdu)

    /**
     * PC_to_RDR_Escape (0x6B)
     * Sony RC-S300 等のダイレクト制御フレーム送信に使用
     */
    fun buildEscapeBlock(slot: Byte = 0x00, seq: Byte, payload: ByteArray): ByteArray =
        buildCcidFrame(MSG_PC_TO_RDR_ESCAPE, slot = slot, seq = seq, param1 = 0x00, payload = payload)

    // =========================================================
    // PC/SC Part 3 Contactless APDU ビルダー
    // =========================================================

    /**
     * カードUID取得 APDU: FF CA 00 00 00
     */
    fun buildGetUidApdu(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xCA.toByte(), 0x00, 0x00, 0x00)

    /**
     * 認証用6バイトキーをリーダー内メモリへロードする APDU:
     * FF 82 <KeyStructure> <KeySlot> 06 <6-byte Key>
     */
    fun buildLoadKeyApdu(
        keyBytes: ByteArray,
        keyStructure: Byte = 0x00,
        keySlot: Byte = 0x00
    ): ByteArray {
        require(keyBytes.size == 6) { "MIFARE Classic key must be 6 bytes" }
        val apdu = ByteArray(11)
        apdu[0] = 0xFF.toByte()
        apdu[1] = 0x82.toByte()
        apdu[2] = keyStructure
        apdu[3] = keySlot
        apdu[4] = 0x06
        System.arraycopy(keyBytes, 0, apdu, 5, 6)
        return apdu
    }

    /**
     * PC/SC 2.01 General Authenticate APDU (MIFARE Classic セクター認証):
     * FF 86 00 00 05 01 00 <BlockNumber> <KeyType(0x60=A, 0x61=B)> <KeySlot>
     */
    fun buildGeneralAuthenticateApdu(
        absoluteBlock: Int,
        keyType: MifareKeyType,
        keySlot: Byte = 0x00
    ): ByteArray {
        val keyTypeByte: Byte = when (keyType) {
            MifareKeyType.KEY_A -> 0x60
            MifareKeyType.KEY_B -> 0x61
        }
        return byteArrayOf(
            0xFF.toByte(),
            0x86.toByte(),
            0x00,
            0x00,
            0x05,
            0x01,
            0x00,
            (absoluteBlock and 0xFF).toByte(),
            keyTypeByte,
            keySlot
        )
    }

    /**
     * 旧型リーダー互換用 Authenticate APDU:
     * FF 88 00 <BlockNumber> <KeyType(0x60=A, 0x61=B)> <KeySlot>
     */
    fun buildLegacyAuthenticateApdu(
        absoluteBlock: Int,
        keyType: MifareKeyType,
        keySlot: Byte = 0x00
    ): ByteArray {
        val keyTypeByte: Byte = when (keyType) {
            MifareKeyType.KEY_A -> 0x60
            MifareKeyType.KEY_B -> 0x61
        }
        return byteArrayOf(
            0xFF.toByte(),
            0x88.toByte(),
            0x00,
            (absoluteBlock and 0xFF).toByte(),
            keyTypeByte,
            keySlot
        )
    }

    /**
     * 16バイトデータブロック読み取り (Read Binary) APDU:
     * FF B0 00 <BlockNumber> 10
     */
    fun buildReadBinaryBlockApdu(absoluteBlock: Int, length: Int = 16): ByteArray {
        return byteArrayOf(
            0xFF.toByte(),
            0xB0.toByte(),
            0x00,
            (absoluteBlock and 0xFF).toByte(),
            (length and 0xFF).toByte()
        )
    }

    /**
     * CCID Bulk-IN レスポンスフレームの解析結果
     */
    data class CcidResponse(
        val messageType: Byte,
        val slot: Byte,
        val seq: Byte,
        val statusByte: Int,
        val errorByte: Int,
        val payload: ByteArray
    ) {
        /**
         * bmICCStatus (bits 0..1):
         * 0 = ICC present and active
         * 1 = ICC present and inactive
         * 2 = No ICC is present
         */
        val iccStatus: Int
            get() = statusByte and 0x03

        /**
         * bmCommandStatus (bits 6..7):
         * 0 = Processed without error
         * 1 = Failed
         * 2 = Time Extension requested
         */
        val commandStatus: Int
            get() = (statusByte ushr 6) and 0x03

        val isCardPresent: Boolean
            get() = iccStatus == 0 || iccStatus == 1
    }

    /**
     * CCIDレスポンスバイト配列をパースする
     */
    fun parseCcidResponse(raw: ByteArray, length: Int = raw.size): CcidResponse? {
        if (length < 10) return null
        val payloadLen = (raw[1].toInt() and 0xFF) or
            ((raw[2].toInt() and 0xFF) shl 8) or
            ((raw[3].toInt() and 0xFF) shl 16) or
            ((raw[4].toInt() and 0xFF) shl 24)

        val actualPayloadLen = min(payloadLen.coerceAtLeast(0), length - 10)
        val payload = if (actualPayloadLen > 0) {
            raw.copyOfRange(10, 10 + actualPayloadLen)
        } else {
            ByteArray(0)
        }

        return CcidResponse(
            messageType = raw[0],
            slot = raw[5],
            seq = raw[6],
            statusByte = raw[7].toInt() and 0xFF,
            errorByte = raw[8].toInt() and 0xFF,
            payload = payload
        )
    }

    /**
     * APDUレスポンス (Data + SW1 + SW2) の解析クラス
     */
    data class ApduResponse(
        val data: ByteArray,
        val sw1: Int,
        val sw2: Int
    ) {
        val isSuccess: Boolean
            get() = sw1 == 0x90 && sw2 == 0x00

        val statusHex: String
            get() = String.format("%02X%02X", sw1, sw2)
    }

    fun parseApduResponse(payload: ByteArray): ApduResponse? {
        if (payload.size < 2) return null
        val sw1 = payload[payload.size - 2].toInt() and 0xFF
        val sw2 = payload[payload.size - 1].toInt() and 0xFF
        val data = if (payload.size > 2) {
            payload.copyOfRange(0, payload.size - 2)
        } else {
            ByteArray(0)
        }
        return ApduResponse(data = data, sw1 = sw1, sw2 = sw2)
    }
}

/**
 * Android UsbManager を使って外付け USB CCID NFCリーダーを監視・通信し、
 * MIFARE Classic 学生証の読み取りを行うマネージャ
 */
class UsbNfcReaderManager(
    context: Context,
    private val configProvider: () -> MifareConfig,
    private val onCardReadResult: (MifareReadResult) -> Unit,
    private val onStatusLog: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _readerState = MutableStateFlow<UsbReaderState>(UsbReaderState.Disconnected)
    val readerState: StateFlow<UsbReaderState> = _readerState.asStateFlow()

    private val _detectedDevices = MutableStateFlow<List<UsbDeviceSummary>>(emptyList())
    val detectedDevices: StateFlow<List<UsbDeviceSummary>> = _detectedDevices.asStateFlow()

    private var activeDevice: UsbDevice? = null
    private var activeConnection: UsbDeviceConnection? = null
    private var activeInterface: UsbInterface? = null
    private var bulkOutEndpoint: UsbEndpoint? = null
    private var bulkInEndpoint: UsbEndpoint? = null
    private var pollingJob: Job? = null
    private var sequenceNumber: Byte = 0
    private var isReceiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = extractUsbDevice(intent)
                    refreshDeviceList()
                    if (device != null && isCandidateNfcReader(device)) {
                        connectToDevice(device)
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = extractUsbDevice(intent)
                    if (device != null && device.deviceName == activeDevice?.deviceName) {
                        disconnectCurrentReader()
                        onStatusLog("USB NFCリーダーが取り外されました")
                    }
                    refreshDeviceList()
                }

                ACTION_USB_PERMISSION -> {
                    val device = extractUsbDevice(intent)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    refreshDeviceList()
                    if (granted && device != null) {
                        openAndStartPolling(device)
                    } else {
                        _readerState.value = UsbReaderState.Error("USBデバイスへのアクセス権限が拒否されました")
                        onStatusLog("USBリーダーのアクセス権限が許可されませんでした")
                    }
                }
            }
        }
    }

    /**
     * USBレシーバーの登録と初期スキャンを開始する
     */
    fun start() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            }
            ContextCompat.registerReceiver(
                appContext,
                usbReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isReceiverRegistered = true
        }
        scanAndAutoConnect()
    }

    /**
     * 終了時のクリーンアップ
     */
    fun stop() {
        disconnectCurrentReader()
        if (isReceiverRegistered) {
            runCatching { appContext.unregisterReceiver(usbReceiver) }
            isReceiverRegistered = false
        }
        scope.cancel()
    }

    /**
     * 現在接続されているUSBデバイス一覧を更新し、NFCリーダーがあれば自動接続を試みる
     */
    fun scanAndAutoConnect() {
        refreshDeviceList()
        if (activeConnection != null) return

        val candidate = usbManager.deviceList.values.firstOrNull { isCandidateNfcReader(it) }
        if (candidate != null) {
            connectToDevice(candidate)
        }
    }

    /**
     * UIから特定のUSBデバイスを指定して接続を要求する
     */
    fun connectToDeviceByName(usbDeviceNameKey: String) {
        val device = usbManager.deviceList[usbDeviceNameKey]
        if (device != null) {
            connectToDevice(device)
        } else {
            onStatusLog("指定されたUSBデバイスが見つかりません")
            refreshDeviceList()
        }
    }

    fun refreshDeviceList() {
        val list = usbManager.deviceList.values.map { dev ->
            val hasCcid = (0 until dev.interfaceCount).any { idx ->
                dev.getInterface(idx).interfaceClass == UsbConstants.USB_CLASS_CSCID
            }
            val isKnownVendor = dev.vendorId in UsbCcidProtocol.KNOWN_NFC_VENDOR_IDS
            val label = buildDeviceDisplayName(dev)
            UsbDeviceSummary(
                usbDeviceNameKey = dev.deviceName,
                displayName = label,
                vendorId = dev.vendorId,
                productId = dev.productId,
                isCcidClass = hasCcid,
                isKnownNfcVendor = isKnownVendor,
                hasPermission = usbManager.hasPermission(dev)
            )
        }.sortedWith(
            compareByDescending<UsbDeviceSummary> { it.isCcidClass }
                .thenByDescending { it.isKnownNfcVendor }
                .thenBy { it.displayName }
        )
        _detectedDevices.value = list
    }

    private fun isCandidateNfcReader(device: UsbDevice): Boolean {
        val hasCcidInterface = (0 until device.interfaceCount).any { i ->
            device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_CSCID
        }
        val isKnownVendor = device.vendorId in UsbCcidProtocol.KNOWN_NFC_VENDOR_IDS
        val hasBulkPair = findBestEndpoints(device) != null
        return hasBulkPair && (hasCcidInterface || isKnownVendor)
    }

    private fun connectToDevice(device: UsbDevice) {
        val displayName = buildDeviceDisplayName(device)
        if (!usbManager.hasPermission(device)) {
            _readerState.value = UsbReaderState.PermissionRequired(
                deviceName = displayName,
                vendorId = device.vendorId,
                productId = device.productId
            )
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(
                appContext,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
                flags
            )
            usbManager.requestPermission(device, permissionIntent)
        } else {
            openAndStartPolling(device)
        }
    }

    private data class EndpointSelection(
        val usbInterface: UsbInterface,
        val bulkIn: UsbEndpoint,
        val bulkOut: UsbEndpoint
    )

    /**
     * デバイス内からCCID (Class 0x0B) または Bulk-IN / Bulk-OUT ペアを持つインターフェースを検索する
     */
    private fun findBestEndpoints(device: UsbDevice): EndpointSelection? {
        var fallbackSelection: EndpointSelection? = null

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null

            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN && epIn == null) {
                        epIn = ep
                    } else if (ep.direction == UsbConstants.USB_DIR_OUT && epOut == null) {
                        epOut = ep
                    }
                }
            }

            if (epIn != null && epOut != null) {
                val selection = EndpointSelection(intf, epIn, epOut)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_CSCID) {
                    return selection
                }
                if (fallbackSelection == null) {
                    fallbackSelection = selection
                }
            }
        }
        return fallbackSelection
    }

    private fun openAndStartPolling(device: UsbDevice) {
        disconnectCurrentReader()

        val displayName = buildDeviceDisplayName(device)
        val selection = findBestEndpoints(device)
        if (selection == null) {
            _readerState.value = UsbReaderState.Error(
                "デバイス ($displayName) にCCID通信用のBulkエンドポイントが見つかりません"
            )
            return
        }

        val conn = usbManager.openDevice(device)
        if (conn == null) {
            _readerState.value = UsbReaderState.Error(
                "USBデバイス ($displayName) をオープンできませんでした"
            )
            return
        }

        if (!conn.claimInterface(selection.usbInterface, true)) {
            conn.close()
            _readerState.value = UsbReaderState.Error(
                "USBインターフェースを占有 (claimInterface) できませんでした"
            )
            return
        }

        activeDevice = device
        activeConnection = conn
        activeInterface = selection.usbInterface
        bulkInEndpoint = selection.bulkIn
        bulkOutEndpoint = selection.bulkOut

        _readerState.value = UsbReaderState.Connected(
            deviceName = displayName,
            vendorId = device.vendorId,
            productId = device.productId,
            cardPresent = false
        )
        onStatusLog("USB NFCリーダー接続完了: $displayName")

        // カード検知ポーリングループを開始
        pollingJob = scope.launch {
            runCcidPollingLoop(displayName, device.vendorId, device.productId)
        }
    }

    fun disconnectCurrentReader() {
        pollingJob?.cancel()
        pollingJob = null
        val conn = activeConnection
        val intf = activeInterface
        activeConnection = null
        activeInterface = null
        activeDevice = null
        bulkInEndpoint = null
        bulkOutEndpoint = null

        if (conn != null) {
            runCatching {
                if (intf != null) conn.releaseInterface(intf)
                conn.close()
            }
        }
        _readerState.value = UsbReaderState.Disconnected
    }

    /**
     * バックグラウンドでUSB CCIDリーダーのカード有無を監視し、学生証がかざされた瞬間にMIFARE認証＆ブロック読み取りを実行する。
     * Sony PaSoRi RC-S300 (VID:054C / PID:0DC8, 0DC9) のCCID自動アクティベーション挙動および
     * MIFARE Classic 認証失敗時のHALT状態からの復帰（IccPowerOff -> IccPowerOn）に完全対応。
     */
    private suspend fun runCcidPollingLoop(
        deviceName: String,
        vendorId: Int,
        productId: Int
    ) {
        val isSonyReader = vendorId == UsbCcidProtocol.SONY_VENDOR_ID
        var lastProcessedCardUid: String? = null
        var lastProcessedConfigSig: String = ""
        var lastFailedEmitMillis: Long = 0L
        var consecutiveIoErrors = 0

        while (scope.isActive && activeConnection != null) {
            try {
                // 1. スロット状態確認 (PC_to_RDR_GetSlotStatus: 0x65)
                val slotStatusResp = sendCcidCommand(
                    UsbCcidProtocol.buildGetSlotStatus(slot = 0x00, seq = nextSeq())
                )

                val cardDetectedBySlot = slotStatusResp == null || slotStatusResp.isCardPresent

                if (!cardDetectedBySlot) {
                    if (lastProcessedCardUid != null || lastFailedEmitMillis != 0L) {
                        lastProcessedCardUid = null
                        lastProcessedConfigSig = ""
                        lastFailedEmitMillis = 0L
                        _readerState.value = UsbReaderState.Connected(
                            deviceName = deviceName,
                            vendorId = vendorId,
                            productId = productId,
                            cardPresent = false
                        )
                    }
                    consecutiveIoErrors = 0
                    delay(200L)
                    continue
                }

                // 2. カードをアクティブ化してUIDを取得
                val currentUid = activateCardAndGetUid(isSonyReader = isSonyReader)

                if (currentUid.isEmpty()) {
                    if (lastProcessedCardUid != null) {
                        lastProcessedCardUid = null
                        lastProcessedConfigSig = ""
                        _readerState.value = UsbReaderState.Connected(
                            deviceName = deviceName,
                            vendorId = vendorId,
                            productId = productId,
                            cardPresent = false
                        )
                    }
                    consecutiveIoErrors = 0
                    delay(240L)
                    continue
                }

                _readerState.value = UsbReaderState.Connected(
                    deviceName = deviceName,
                    vendorId = vendorId,
                    productId = productId,
                    cardPresent = true
                )
                consecutiveIoErrors = 0

                val config = configProvider()
                val currentConfigSig = "${config.sectorIndex}:${config.blockIndexInSector}:${config.keyType}:${config.bulkKeysText}:${config.tryBothKeyTypesForPlainHex}:${config.encoding}:${config.byteOffset}:${config.byteLength}:${config.validationRegex}:${config.allowUidFallback}"

                // 同一カードが置かれっぱなしで、かつ設定も変更されていない場合はスキップ
                if (currentUid == lastProcessedCardUid && currentConfigSig == lastProcessedConfigSig) {
                    delay(280L)
                    continue
                }

                // RC-S300はカードがアンテナ端に近づいた瞬間にUID取得のみ先行成功するため、
                // カードが密着してRF磁界が安定するまで少し待機してからMIFARE Crypto1認証を開始する
                delay(65L)
                reactivateCardOnSlot(isSonyReader = isSonyReader)

                // 3. MIFARE Classic セクター認証＆ブロック読み取りを実行
                var readResult = readMifareClassicOverCcid(
                    cardUid = currentUid,
                    config = config,
                    readerDisplayName = deviceName,
                    isSonyReader = isSonyReader
                )

                // かざす途中の不安定なタイミングで失敗した場合は、一度だけRF再起動して自動リトライ
                if (readResult is MifareReadResult.AuthFailed || readResult is MifareReadResult.Error) {
                    delay(120L)
                    if (reactivateCardOnSlot(isSonyReader = isSonyReader)) {
                        readResult = readMifareClassicOverCcid(
                            cardUid = currentUid,
                            config = config,
                            readerDisplayName = deviceName,
                            isSonyReader = isSonyReader
                        )
                    }
                }

                val now = System.currentTimeMillis()
                when (readResult) {
                    is MifareReadResult.Success,
                    is MifareReadResult.ParseFailed -> {
                        // ブロック読み取りまで成功した場合は、カードを離す（または設定変更）まで再読取をロック
                        lastProcessedCardUid = currentUid
                        lastProcessedConfigSig = currentConfigSig
                        lastFailedEmitMillis = 0L
                        onCardReadResult(readResult)
                    }

                    is MifareReadResult.AuthFailed,
                    is MifareReadResult.Error -> {
                        // 失敗時はカードを置き直したり設定を変更した際に再試行できるよう、クールダウン間隔で通知
                        if (now - lastFailedEmitMillis >= 1800L || currentConfigSig != lastProcessedConfigSig) {
                            lastFailedEmitMillis = now
                            lastProcessedConfigSig = currentConfigSig
                            onCardReadResult(readResult)
                        }
                    }
                }

                delay(350L)
            } catch (e: Exception) {
                consecutiveIoErrors++
                if (consecutiveIoErrors > 15) {
                    _readerState.value = UsbReaderState.Error(
                        "USBリーダーとの通信が切断されました (${e.localizedMessage ?: "Communication Error"})"
                    )
                    break
                }
                delay(400L)
            }
        }
    }

    /**
     * 非接触カードをIccPowerOnで起動し、PC/SC GetData (FF CA 00 00 00) でカードUIDを取得する。
     * Sony RC-S300 で標準IccPowerOnのみではType A応答がない場合はEscapeコマンドによるType A切替も試行する。
     */
    private fun activateCardAndGetUid(isSonyReader: Boolean): String {
        drainStaleBulkIn()

        val voltages = byteArrayOf(0x00, 0x01, 0x02) // Auto, 5.0V, 3.0V
        var powerOnSuccess = false
        for (v in voltages) {
            val resp = sendCcidCommand(
                UsbCcidProtocol.buildIccPowerOn(slot = 0x00, seq = nextSeq(), voltageSelect = v)
            )
            if (resp != null && resp.commandStatus == 0 && resp.payload.isNotEmpty()) {
                powerOnSuccess = true
                break
            }
        }

        if (powerOnSuccess) {
            val uidResp = transmitApdu(UsbCcidProtocol.buildGetUidApdu())
            if (uidResp != null && uidResp.isSuccess && uidResp.data.isNotEmpty()) {
                return MifareClassicReader.bytesToHex(uidResp.data, separator = "")
            }
        }

        // Sony RC-S300 向けフォールバック: Escape (0x6B) でType Aプロトコル有効化＆RF再起動を試行
        if (isSonyReader) {
            sendEscapeCommand(UsbCcidProtocol.SONY_CMD_START_TRANSPARENT)
            sendEscapeCommand(UsbCcidProtocol.SONY_CMD_SWITCH_PROTOCOL_TYPE_A)
            sendEscapeCommand(UsbCcidProtocol.SONY_CMD_TURN_ON_RF)
            sendEscapeCommand(UsbCcidProtocol.SONY_CMD_END_TRANSPARENT)

            val retryPowerOn = sendCcidCommand(
                UsbCcidProtocol.buildIccPowerOn(slot = 0x00, seq = nextSeq(), voltageSelect = 0x00)
            )
            if (retryPowerOn != null && retryPowerOn.commandStatus == 0) {
                val uidResp = transmitApdu(UsbCcidProtocol.buildGetUidApdu())
                if (uidResp != null && uidResp.isSuccess && uidResp.data.isNotEmpty()) {
                    return MifareClassicReader.bytesToHex(uidResp.data, separator = "")
                }
            }
        }

        return ""
    }

    /**
     * MIFARE Classic 認証失敗によってHALT（非選択）状態に落ちたカードを復帰させるため、
     * 確実に IccPowerOff (RF切断) -> 待機 -> IccPowerOn (RF再投入) -> GetUID (選択確認) を行う。
     * ※Sony RC-S300 では IccPowerOff を挟まずに IccPowerOn のみを送ってもRF磁界がリセットされないため必須。
     */
    private fun reactivateCardOnSlot(isSonyReader: Boolean): Boolean {
        drainStaleBulkIn()
        sendCcidCommand(UsbCcidProtocol.buildIccPowerOff(slot = 0x00, seq = nextSeq()))
        try {
            Thread.sleep(50L)
        } catch (_: InterruptedException) {
        }

        val voltages = byteArrayOf(0x00, 0x01)
        var powered = false
        for (v in voltages) {
            val powerResp = sendCcidCommand(
                UsbCcidProtocol.buildIccPowerOn(slot = 0x00, seq = nextSeq(), voltageSelect = v)
            )
            if (powerResp != null && powerResp.commandStatus == 0 && powerResp.payload.isNotEmpty()) {
                powered = true
                break
            }
        }

        if (!powered && isSonyReader) {
            sendEscapeCommand(UsbCcidProtocol.SONY_CMD_TURN_OFF_RF)
            try {
                Thread.sleep(35L)
            } catch (_: InterruptedException) {
            }
            sendEscapeCommand(UsbCcidProtocol.SONY_CMD_TURN_ON_RF)
            val powerResp = sendCcidCommand(
                UsbCcidProtocol.buildIccPowerOn(slot = 0x00, seq = nextSeq(), voltageSelect = 0x00)
            )
            powered = powerResp != null && powerResp.commandStatus == 0
        }

        try {
            Thread.sleep(25L)
        } catch (_: InterruptedException) {
        }

        // カードが ISO14443-3 ACTIVE 状態に戻ったことを GetData(UID) で確認
        val uidResp = transmitApdu(UsbCcidProtocol.buildGetUidApdu())
        return powered || (uidResp != null && uidResp.isSuccess)
    }

    private data class SectorReadAttemptResult(
        val sectorIndex: Int,
        val blockIndexInSector: Int,
        val absoluteBlock: Int,
        val matchedCandidate: MifareKeyCandidate?,
        val rawBlock: ByteArray?,
        val anyKeyLoaded: Boolean,
        val lastLoadSw: String,
        val lastAuthSw: String,
        val lastReadSw: String
    )

    /**
     * PC/SC Part 3 APDU で MIFARE Classic のセクター鍵候補を順にロード → 認証 → 16バイトブロック読取 → 学籍番号デコードを行う
     */
    private fun readMifareClassicOverCcid(
        cardUid: String,
        config: MifareConfig,
        readerDisplayName: String,
        isSonyReader: Boolean
    ): MifareReadResult {
        val primaryCandidates = config.resolveKeyCandidatesForSector(config.sectorIndex)
        if (primaryCandidates.isEmpty()) {
            return MifareReadResult.Error(
                cardUid = cardUid,
                title = "MIFARE鍵設定エラー",
                message = "有効な12桁の16進数キー（6バイト）が1件も指定されていません。設定画面の鍵テキストを確認してください。"
            )
        }

        // 1. 優先セクターリスト（設定されたセクター番号 ＋ 一括鍵テキスト内で明示指定されたセクター番号）を順に試行
        val prioritySectors = config.resolvePrioritySectors()
        var firstAttemptResult: SectorReadAttemptResult? = null
        var cardHalted = false

        for (targetSector in prioritySectors) {
            val candidatesForSector = config.resolveKeyCandidatesForSector(targetSector)
            if (candidatesForSector.isEmpty()) continue

            if (cardHalted) {
                reactivateCardOnSlot(isSonyReader = isSonyReader)
                cardHalted = false
            }

            val attempt = tryAuthenticateAndReadSector(
                sectorIndex = targetSector,
                blockIndexInSector = config.blockIndexInSector,
                candidates = candidatesForSector,
                isSonyReader = isSonyReader
            )
            if (firstAttemptResult == null) {
                firstAttemptResult = attempt
            }

            if (attempt.matchedCandidate != null && attempt.rawBlock != null && attempt.rawBlock.isNotEmpty()) {
                val autoSectorNote = if (targetSector != config.sectorIndex) {
                    " / Sector $targetSector 自動選択"
                } else {
                    ""
                }
                val readerDesc = "USB CCID ($readerDisplayName$autoSectorNote / ${attempt.matchedCandidate.summaryLabel})"
                return buildReadResultFromBlock(
                    cardUid = cardUid,
                    rawBlock = attempt.rawBlock,
                    sectorIndex = targetSector,
                    blockIndexInSector = attempt.blockIndexInSector,
                    absoluteBlock = attempt.absoluteBlock,
                    readerDesc = readerDesc,
                    config = config
                )
            }
            cardHalted = true
        }

        // 2. もし設定セクターで認証失敗し、かつユーザーが工場出荷時(FFFFFFFFFFFF)以外のカスタム鍵を指定している場合、
        //    「鍵は合っているが対象セクター番号(0〜15)の設定が異なる」ケースを救済するため全セクター(0〜15)をカスタム鍵でスキャンする
        val allCustomCandidates = config.resolveKeyCandidatesForSector(config.sectorIndex)
            .filter { it.keyHex != "FFFFFFFFFFFF" }

        if (allCustomCandidates.isNotEmpty()) {
            val remainingSectors = (0..15).filter { it !in prioritySectors }
            for (scanSector in remainingSectors) {
                reactivateCardOnSlot(isSonyReader = isSonyReader)
                val scanAttempt = tryAuthenticateAndReadSector(
                    sectorIndex = scanSector,
                    blockIndexInSector = config.blockIndexInSector.coerceIn(0, 2),
                    candidates = allCustomCandidates,
                    isSonyReader = isSonyReader,
                    fastSingleSlotMode = true
                )
                if (scanAttempt.matchedCandidate != null && scanAttempt.rawBlock != null && scanAttempt.rawBlock.isNotEmpty()) {
                    val readerDesc =
                        "USB CCID ($readerDisplayName / Sector $scanSector 自動検出 / ${scanAttempt.matchedCandidate.summaryLabel})"
                    return buildReadResultFromBlock(
                        cardUid = cardUid,
                        rawBlock = scanAttempt.rawBlock,
                        sectorIndex = scanSector,
                        blockIndexInSector = scanAttempt.blockIndexInSector,
                        absoluteBlock = scanAttempt.absoluteBlock,
                        readerDesc = readerDesc,
                        config = config
                    )
                }
            }
        }

        // 3. すべて失敗した場合のエラー・フォールバック処理
        val refAttempt = firstAttemptResult ?: SectorReadAttemptResult(
            sectorIndex = config.sectorIndex,
            blockIndexInSector = config.blockIndexInSector,
            absoluteBlock = UsbCcidProtocol.sectorToAbsoluteBlock(config.sectorIndex, config.blockIndexInSector),
            matchedCandidate = null,
            rawBlock = null,
            anyKeyLoaded = false,
            lastLoadSw = "",
            lastAuthSw = "",
            lastReadSw = ""
        )

        if (!refAttempt.anyKeyLoaded) {
            if (config.allowUidFallback) {
                val dummyBlock = MifareClassicReader.createSimulatedBlock(cardUid)
                return MifareReadResult.Success(
                    cardUid = cardUid,
                    studentId = cardUid,
                    rawBlockBytes = dummyBlock,
                    sectorIndex = config.sectorIndex,
                    blockIndexInSector = config.blockIndexInSector,
                    absoluteBlockIndex = refAttempt.absoluteBlock,
                    cardTypeDescription = "USBリーダー ($readerDisplayName) UIDフォールバック",
                    isUidFallback = true
                )
            }
            val swText = refAttempt.lastLoadSw.ifEmpty { "応答なし" }
            return MifareReadResult.Error(
                cardUid = cardUid,
                title = "USBリーダー鍵ロードエラー",
                message = "USB NFCリーダー ($readerDisplayName) へのMIFARE鍵ロードコマンド (FF 82) が失敗しました (SW=$swText)。カードをリーダー中央にしっかり置いて再度お試しください。"
            )
        }

        if (refAttempt.matchedCandidate == null) {
            if (config.allowUidFallback) {
                val dummyBlock = MifareClassicReader.createSimulatedBlock(cardUid)
                return MifareReadResult.Success(
                    cardUid = cardUid,
                    studentId = cardUid,
                    rawBlockBytes = dummyBlock,
                    sectorIndex = config.sectorIndex,
                    blockIndexInSector = config.blockIndexInSector,
                    absoluteBlockIndex = refAttempt.absoluteBlock,
                    cardTypeDescription = "USBリーダー ($readerDisplayName) UIDフォールバック",
                    isUidFallback = true
                )
            }
            val swInfo = refAttempt.lastAuthSw.ifEmpty { "認証失敗" }
            val triedSummary = primaryCandidates.joinToString(", ") { "${it.keyType.displayName}:${it.keyHex}" }
            val scannedNote = if (allCustomCandidates.isNotEmpty()) "（全セクター 0〜15 の自動探索でも一致しませんでした）" else ""
            return MifareReadResult.AuthFailed(
                cardUid = cardUid,
                sectorIndex = config.sectorIndex,
                keyType = primaryCandidates.first().keyType,
                message = "USBリーダー ($readerDisplayName) で Sector ${config.sectorIndex} (Block #${refAttempt.absoluteBlock}) に対し、指定された鍵 [$triedSummary] で認証に失敗しました (SW=$swInfo)$scannedNote。鍵設定を編集した場合は「設定を保存」を押しているか、Key A/B・鍵HEX値をご確認ください。"
            )
        }

        val readSw = refAttempt.lastReadSw.ifEmpty { "応答なし" }
        return MifareReadResult.Error(
            cardUid = cardUid,
            title = "USBリーダー ブロック読み取り失敗",
            message = "Sector ${refAttempt.sectorIndex} の認証は成功しましたが、Block #${refAttempt.absoluteBlock} の16バイト読み取り (FF B0) に失敗しました (SW=$readSw)。"
        )
    }

    /**
     * 指定された1つのセクターに対して鍵候補リストを順に試行し、認証と16バイトブロック読み取りを行う。
     * 1回でも認証(FF 86 / FF 88)に失敗するとMIFARE ClassicカードはHALT状態になるため、
     * 次の試行の前に必ず reactivateCardOnSlot() でRF再起動（IccPowerOff -> IccPowerOn）を実行する。
     */
    private fun tryAuthenticateAndReadSector(
        sectorIndex: Int,
        blockIndexInSector: Int,
        candidates: List<MifareKeyCandidate>,
        isSonyReader: Boolean,
        fastSingleSlotMode: Boolean = false
    ): SectorReadAttemptResult {
        val absoluteBlock = UsbCcidProtocol.sectorToAbsoluteBlock(
            sectorIndex = sectorIndex,
            blockIndexInSector = blockIndexInSector
        )

        var anyKeyLoaded = false
        var lastLoadSw = ""
        var lastAuthSw = ""
        var lastReadSw = ""
        var cardNeedsReactivation = false

        for ((candIdx, candidate) in candidates.withIndex()) {
            val keyBytes = candidate.toKeyBytes()
            val keyTypeSlot: Byte = if (candidate.keyType == MifareKeyType.KEY_A) 0x60 else 0x61

            // RC-S300 および PC/SC 2.01 リーダー各種の Load Keys (FF 82 P1 P2 06 <Key>) パラメータ候補
            // 初回候補では P1=0x00(標準揮発スロット0) と P1=0x20(PC/SC Part3 揮発フラグ) 等を網羅的に確認
            val loadConfigs = if (fastSingleSlotMode || candIdx > 0) {
                listOf(
                    Pair(0x00.toByte(), 0x00.toByte()),
                    Pair(0x20.toByte(), 0x00.toByte())
                )
            } else {
                listOf(
                    Pair(0x00.toByte(), 0x00.toByte()),
                    Pair(0x20.toByte(), 0x00.toByte()),
                    Pair(0x00.toByte(), 0x01.toByte()),
                    Pair(0x00.toByte(), keyTypeSlot)
                )
            }

            var authSucceeded = false
            var triedAtLeastOneAuthForThisCandidate = false

            for ((p1Structure, p2Slot) in loadConfigs) {
                if (cardNeedsReactivation) {
                    reactivateCardOnSlot(isSonyReader = isSonyReader)
                    cardNeedsReactivation = false
                }

                // Step A: Load Keys (FF 82 <P1> <P2> 06 <6-byte Key>)
                val loadResp = transmitApdu(
                    UsbCcidProtocol.buildLoadKeyApdu(
                        keyBytes = keyBytes,
                        keyStructure = p1Structure,
                        keySlot = p2Slot
                    )
                )

                if (loadResp == null || !loadResp.isSuccess) {
                    if (loadResp != null) {
                        lastLoadSw = loadResp.statusHex
                    }
                    continue
                }

                anyKeyLoaded = true

                // Step B: General Authenticate (FF 86 00 00 05 01 00 <Block> <KeyType> <KeySlot>)
                val authResp = transmitApdu(
                    UsbCcidProtocol.buildGeneralAuthenticateApdu(
                        absoluteBlock = absoluteBlock,
                        keyType = candidate.keyType,
                        keySlot = p2Slot
                    )
                )

                if (authResp != null && authResp.isSuccess) {
                    authSucceeded = true
                    break
                }

                // 認証失敗 → MIFARE Classic カードは HALT 状態に遷移したため次回試行前にRF再起動が必須
                cardNeedsReactivation = true
                if (authResp != null) {
                    lastAuthSw = authResp.statusHex
                }

                // もし FF 86 自体が未サポート (6A81 / 6D00 等) のリーダーだった場合のみ、RF再起動後に旧式 FF 88 を試行
                val sw1 = authResp?.sw1 ?: 0
                if (sw1 == 0x6A || sw1 == 0x6D || sw1 == 0x6E) {
                    reactivateCardOnSlot(isSonyReader = isSonyReader)
                    cardNeedsReactivation = false
                    transmitApdu(
                        UsbCcidProtocol.buildLoadKeyApdu(
                            keyBytes = keyBytes,
                            keyStructure = p1Structure,
                            keySlot = p2Slot
                        )
                    )
                    val legacyResp = transmitApdu(
                        UsbCcidProtocol.buildLegacyAuthenticateApdu(
                            absoluteBlock = absoluteBlock,
                            keyType = candidate.keyType,
                            keySlot = p2Slot
                        )
                    )
                    if (legacyResp != null && legacyResp.isSuccess) {
                        authSucceeded = true
                        break
                    }
                    cardNeedsReactivation = true
                }

                // (0x00, 0x00) で FF 82 が 9000 かつ FF 86 が 6300/6982 (純粋な鍵不一致) の場合、
                // 初回候補だけ念のため (0x20, 0x00) も確認し、それ以降は同一鍵での別スロット試行を省略して高速化する
                if (triedAtLeastOneAuthForThisCandidate && (authResp?.statusHex == "6300" || authResp?.statusHex == "6982")) {
                    break
                }
                triedAtLeastOneAuthForThisCandidate = true
            }

            if (authSucceeded) {
                // Step C: Read Binary (FF B0 00 <Block> 10)
                var readResp = transmitApdu(
                    UsbCcidProtocol.buildReadBinaryBlockApdu(
                        absoluteBlock = absoluteBlock,
                        length = 16
                    )
                )
                // 6C xx (Le長エラー) が返った場合は要求長に合わせて再取得
                if (readResp != null && readResp.sw1 == 0x6C && readResp.sw2 > 0) {
                    readResp = transmitApdu(
                        UsbCcidProtocol.buildReadBinaryBlockApdu(
                            absoluteBlock = absoluteBlock,
                            length = readResp.sw2
                        )
                    )
                }

                if (readResp != null && readResp.isSuccess && readResp.data.isNotEmpty()) {
                    return SectorReadAttemptResult(
                        sectorIndex = sectorIndex,
                        blockIndexInSector = blockIndexInSector,
                        absoluteBlock = absoluteBlock,
                        matchedCandidate = candidate,
                        rawBlock = readResp.data,
                        anyKeyLoaded = true,
                        lastLoadSw = lastLoadSw,
                        lastAuthSw = "9000",
                        lastReadSw = "9000"
                    )
                } else {
                    lastReadSw = readResp?.statusHex ?: "応答なし"
                    cardNeedsReactivation = true
                }
            }
        }

        return SectorReadAttemptResult(
            sectorIndex = sectorIndex,
            blockIndexInSector = blockIndexInSector,
            absoluteBlock = absoluteBlock,
            matchedCandidate = null,
            rawBlock = null,
            anyKeyLoaded = anyKeyLoaded,
            lastLoadSw = lastLoadSw,
            lastAuthSw = lastAuthSw,
            lastReadSw = lastReadSw
        )
    }

    private fun buildReadResultFromBlock(
        cardUid: String,
        rawBlock: ByteArray,
        sectorIndex: Int,
        blockIndexInSector: Int,
        absoluteBlock: Int,
        readerDesc: String,
        config: MifareConfig
    ): MifareReadResult {
        return when (val outcome = MifareClassicReader.extractStudentIdFromBlock(rawBlock, config)) {
            is MifareClassicReader.BlockParseOutcome.Valid -> MifareReadResult.Success(
                cardUid = cardUid,
                studentId = outcome.studentId,
                rawBlockBytes = rawBlock,
                sectorIndex = sectorIndex,
                blockIndexInSector = blockIndexInSector,
                absoluteBlockIndex = absoluteBlock,
                cardTypeDescription = readerDesc
            )

            is MifareClassicReader.BlockParseOutcome.Invalid -> MifareReadResult.ParseFailed(
                cardUid = cardUid,
                rawBlockBytes = rawBlock,
                sectorIndex = sectorIndex,
                blockIndexInSector = blockIndexInSector,
                absoluteBlockIndex = absoluteBlock,
                reason = outcome.reason
            )
        }
    }

    /**
     * PC_to_RDR_XfrBlock (0x6F) でAPDUを送信し、RDR_to_PC_DataBlock (0x80) からAPDUレスポンスを取得する
     */
    private fun transmitApdu(apdu: ByteArray): UsbCcidProtocol.ApduResponse? {
        val ccidFrame = UsbCcidProtocol.buildXfrBlock(
            slot = 0x00,
            seq = nextSeq(),
            apdu = apdu
        )
        val ccidResp = sendCcidCommand(ccidFrame) ?: return null
        if (ccidResp.commandStatus != 0) return null
        return UsbCcidProtocol.parseApduResponse(ccidResp.payload)
    }

    /**
     * PC_to_RDR_Escape (0x6B) でリーダー制御コマンドを送信する（Sony RC-S300 用）
     */
    private fun sendEscapeCommand(payload: ByteArray): UsbCcidProtocol.CcidResponse? {
        val frame = UsbCcidProtocol.buildEscapeBlock(
            slot = 0x00,
            seq = nextSeq(),
            payload = payload
        )
        return sendCcidCommand(frame)
    }

    /**
     * Bulk-IN エンドポイント内に残っている古い未読パケット（非同期スロット通知や遅延応答）を破棄する
     */
    @Synchronized
    private fun drainStaleBulkIn() {
        val conn = activeConnection ?: return
        val epIn = bulkInEndpoint ?: return
        val flushBuf = ByteArray(1024)
        repeat(4) {
            val r = conn.bulkTransfer(epIn, flushBuf, flushBuf.size, 15)
            if (r <= 0) return
        }
    }

    /**
     * CCIDフレームをBulk-OUTで送信し、Bulk-INから送信時と同一シーケンス番号(bSeq)のCCIDレスポンスフレームを受信する。
     * Time Extension (commandStatus == 2) および古い残留パケットのスキップに対応。
     */
    @Synchronized
    private fun sendCcidCommand(frame: ByteArray): UsbCcidProtocol.CcidResponse? {
        val conn = activeConnection ?: return null
        val epOut = bulkOutEndpoint ?: return null
        val epIn = bulkInEndpoint ?: return null

        val expectedSeq = frame[6]
        val sent = conn.bulkTransfer(epOut, frame, frame.size, USB_TIMEOUT_MS)
        if (sent < 0) return null

        repeat(8) {
            val rawResp = readFullCcidPacket(conn, epIn) ?: return null
            val parsed = UsbCcidProtocol.parseCcidResponse(rawResp) ?: return null
            // Time Extension (commandStatus == 2) の場合はリーダーの処理完了を継続待機
            if (parsed.commandStatus == 2) {
                return@repeat
            }
            // 前回のコマンドの遅延応答やSlotChange通知など、シーケンス番号が異なるフレームは読み飛ばす
            if (parsed.seq != expectedSeq && parsed.messageType != UsbCcidProtocol.MSG_RDR_TO_PC_SLOT_STATUS) {
                return@repeat
            }
            return parsed
        }
        return null
    }

    private fun readFullCcidPacket(
        conn: UsbDeviceConnection,
        epIn: UsbEndpoint
    ): ByteArray? {
        // Android + Sony RC-S300 では分割受信を避けるため十分な大きさ(4096B)のバッファで一括受信する
        val buffer = ByteArray(4096)
        val firstRead = conn.bulkTransfer(epIn, buffer, buffer.size, USB_TIMEOUT_MS)
        if (firstRead < 10) return null

        val expectedPayloadLen = (buffer[1].toInt() and 0xFF) or
            ((buffer[2].toInt() and 0xFF) shl 8) or
            ((buffer[3].toInt() and 0xFF) shl 16) or
            ((buffer[4].toInt() and 0xFF) shl 24)

        val totalExpected = 10 + expectedPayloadLen.coerceIn(0, 4096)
        if (firstRead >= totalExpected) {
            return buffer.copyOfRange(0, totalExpected)
        }

        val bos = ByteArrayOutputStream(totalExpected)
        bos.write(buffer, 0, firstRead)
        while (bos.size() < totalExpected) {
            val n = conn.bulkTransfer(epIn, buffer, buffer.size, USB_TIMEOUT_MS)
            if (n <= 0) break
            bos.write(buffer, 0, n)
        }
        return bos.toByteArray()
    }

    private fun nextSeq(): Byte {
        val current = sequenceNumber
        sequenceNumber = (sequenceNumber + 1).toByte()
        return current
    }

    private fun buildDeviceDisplayName(device: UsbDevice): String {
        val mfg = device.manufacturerName?.trim().orEmpty()
        val prod = device.productName?.trim().orEmpty()
        return when {
            mfg.isNotEmpty() && prod.isNotEmpty() ->
                if (prod.startsWith(mfg, ignoreCase = true)) prod else "$mfg $prod"
            prod.isNotEmpty() -> prod
            mfg.isNotEmpty() -> "$mfg (${String.format("%04X:%04X", device.vendorId, device.productId)})"
            else -> "USB NFC Reader (${String.format("%04X:%04X", device.vendorId, device.productId)})"
        }
    }

    private fun extractUsbDevice(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION =
            "app.nagatani.dev.attendance.ACTION_USB_PERMISSION"
        private const val USB_TIMEOUT_MS = 1200
    }
}
