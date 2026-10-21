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
    const val MSG_PC_TO_RDR_XFR_BLOCK: Byte = 0x6F

    // CCID Bulk-IN メッセージタイプ
    const val MSG_RDR_TO_PC_DATA_BLOCK: Byte = 0x80.toByte()
    const val MSG_RDR_TO_PC_SLOT_STATUS: Byte = 0x81.toByte()

    // 主要な外付けNFCリーダーのUSB Vendor ID一覧
    val KNOWN_NFC_VENDOR_IDS = setOf(
        0x072F, // ACS (ACR122U, ACR1252U, ACR1281U 等)
        0x054C, // Sony (PaSoRi RC-S300, RC-S380 等)
        0x04E6, // Identiv / SCM Microsystems (uTrust 3700F 等)
        0x076B, // HID Global (OMNIKEY 5022, 5427 等)
        0x1FC9, // NXP Semiconductors
        0x096E, // Feitian Technologies
        0x08E6, // Thales / Gemalto
        0x31AA  // AB Circle
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

    fun buildIccPowerOn(slot: Byte = 0x00, seq: Byte): ByteArray =
        buildCcidFrame(MSG_PC_TO_RDR_ICC_POWER_ON, slot = slot, seq = seq, param1 = 0x00)

    fun buildIccPowerOff(slot: Byte = 0x00, seq: Byte): ByteArray =
        buildCcidFrame(MSG_PC_TO_RDR_ICC_POWER_OFF, slot = slot, seq = seq)

    fun buildGetSlotStatus(slot: Byte = 0x00, seq: Byte): ByteArray =
        buildCcidFrame(MSG_PC_TO_RDR_GET_SLOT_STATUS, slot = slot, seq = seq)

    fun buildXfrBlock(slot: Byte = 0x00, seq: Byte, apdu: ByteArray): ByteArray =
        buildCcidFrame(MSG_PC_TO_RDR_XFR_BLOCK, slot = slot, seq = seq, param1 = 0x00, payload = apdu)

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
     * バックグラウンドでUSB CCIDリーダーのカード有無を監視し、学生証がかざされた瞬間にMIFARE認証＆ブロック読み取りを実行する
     */
    private suspend fun runCcidPollingLoop(
        deviceName: String,
        vendorId: Int,
        productId: Int
    ) {
        var lastProcessedCardUid: String? = null
        var consecutiveIoErrors = 0

        while (scope.isActive && activeConnection != null) {
            try {
                // 1. スロット状態確認またはIccPowerOnでカード存在をチェック
                val slotStatusResp = sendCcidCommand(
                    UsbCcidProtocol.buildGetSlotStatus(slot = 0x00, seq = nextSeq())
                )

                // GetSlotStatusに対応していない一部リーダー、またはカードが存在する場合にIccPowerOnを試行
                val cardDetectedBySlot = slotStatusResp == null || slotStatusResp.isCardPresent

                if (!cardDetectedBySlot) {
                    // カードがリーダーから離れたらリセットし、次のタッチを受け付け可能にする
                    if (lastProcessedCardUid != null) {
                        lastProcessedCardUid = null
                        _readerState.value = UsbReaderState.Connected(
                            deviceName = deviceName,
                            vendorId = vendorId,
                            productId = productId,
                            cardPresent = false
                        )
                    }
                    consecutiveIoErrors = 0
                    delay(220L)
                    continue
                }

                // 2. IccPowerOn (0x62) を送信して非接触カードをアクティブ化
                val powerOnResp = sendCcidCommand(
                    UsbCcidProtocol.buildIccPowerOn(slot = 0x00, seq = nextSeq())
                )

                val atrAvailable = powerOnResp != null &&
                    powerOnResp.commandStatus == 0 &&
                    powerOnResp.payload.isNotEmpty()

                if (!atrAvailable) {
                    // カードがまだRFフィールド内にない
                    if (lastProcessedCardUid != null) {
                        lastProcessedCardUid = null
                        _readerState.value = UsbReaderState.Connected(
                            deviceName = deviceName,
                            vendorId = vendorId,
                            productId = productId,
                            cardPresent = false
                        )
                    }
                    consecutiveIoErrors = 0
                    delay(250L)
                    continue
                }

                // 3. カードUID取得 (FF CA 00 00 00)
                val uidApduResp = transmitApdu(UsbCcidProtocol.buildGetUidApdu())
                val currentUid = if (uidApduResp != null && uidApduResp.isSuccess && uidApduResp.data.isNotEmpty()) {
                    MifareClassicReader.bytesToHex(uidApduResp.data, separator = "")
                } else {
                    ""
                }

                if (currentUid.isEmpty()) {
                    delay(250L)
                    continue
                }

                _readerState.value = UsbReaderState.Connected(
                    deviceName = deviceName,
                    vendorId = vendorId,
                    productId = productId,
                    cardPresent = true
                )
                consecutiveIoErrors = 0

                // 同一カードが置かれっぱなしの場合はスキップ（一度離すまで再読取しない）
                if (currentUid == lastProcessedCardUid) {
                    delay(300L)
                    continue
                }

                // 4. 新しいカードタッチを検知 → MIFARE Classic セクター認証＆ブロック読み取りを実行
                val config = configProvider()
                val readResult = readMifareClassicOverCcid(
                    cardUid = currentUid,
                    config = config,
                    readerDisplayName = deviceName
                )
                lastProcessedCardUid = currentUid
                onCardReadResult(readResult)

                // 読み取り完了後にIccPowerOffを送っておく
                sendCcidCommand(UsbCcidProtocol.buildIccPowerOff(slot = 0x00, seq = nextSeq()))
                delay(400L)
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
     * PC/SC Part 3 APDU で MIFARE Classic のセクター鍵候補を順にロード → 認証 → 16バイトブロック読取 → 学籍番号デコードを行う
     */
    private fun readMifareClassicOverCcid(
        cardUid: String,
        config: MifareConfig,
        readerDisplayName: String
    ): MifareReadResult {
        val keyCandidates = config.resolveKeyCandidatesForSector(config.sectorIndex)
        if (keyCandidates.isEmpty()) {
            return MifareReadResult.Error(
                cardUid = cardUid,
                title = "MIFARE鍵設定エラー",
                message = "有効な12桁の16進数キー（6バイト）が1件も指定されていません。設定画面の鍵テキストを確認してください。"
            )
        }

        val absoluteBlock = UsbCcidProtocol.sectorToAbsoluteBlock(
            sectorIndex = config.sectorIndex,
            blockIndexInSector = config.blockIndexInSector
        )

        val keyLoadStructures = listOf(
            Pair(0x00.toByte(), 0x00.toByte()),
            Pair(0x20.toByte(), 0x00.toByte()),
            Pair(0x00.toByte(), 0x01.toByte())
        )

        var matchedCandidate: MifareKeyCandidate? = null
        var lastAuthSw = ""
        var anyKeyLoaded = false

        // 一括設定された鍵候補を優先順に試行
        for ((index, candidate) in keyCandidates.withIndex()) {
            if (index > 0) {
                // 認証失敗後はMIFAREカードがHALT状態になるため、IccPowerOnで再アクティブ化してから次の鍵を試行
                sendCcidCommand(UsbCcidProtocol.buildIccPowerOn(slot = 0x00, seq = nextSeq()))
            }

            val keyBytes = candidate.toKeyBytes()
            var loadedSlot: Byte? = null
            for ((structure, slot) in keyLoadStructures) {
                val loadResp = transmitApdu(
                    UsbCcidProtocol.buildLoadKeyApdu(
                        keyBytes = keyBytes,
                        keyStructure = structure,
                        keySlot = slot
                    )
                )
                if (loadResp != null && loadResp.isSuccess) {
                    loadedSlot = slot
                    anyKeyLoaded = true
                    break
                }
                if (loadResp != null) {
                    lastAuthSw = loadResp.statusHex
                }
            }

            if (loadedSlot == null) continue

            // セクター認証 (General Authenticate: FF 86 ... または旧式 FF 88 ...)
            val authResp = transmitApdu(
                UsbCcidProtocol.buildGeneralAuthenticateApdu(
                    absoluteBlock = absoluteBlock,
                    keyType = candidate.keyType,
                    keySlot = loadedSlot
                )
            )

            val ok = if (authResp != null && authResp.isSuccess) {
                true
            } else {
                if (authResp != null) lastAuthSw = authResp.statusHex
                val legacyResp = transmitApdu(
                    UsbCcidProtocol.buildLegacyAuthenticateApdu(
                        absoluteBlock = absoluteBlock,
                        keyType = candidate.keyType,
                        keySlot = loadedSlot
                    )
                )
                legacyResp != null && legacyResp.isSuccess
            }

            if (ok) {
                matchedCandidate = candidate
                break
            }
        }

        if (!anyKeyLoaded) {
            if (config.allowUidFallback) {
                val dummyBlock = MifareClassicReader.createSimulatedBlock(cardUid)
                return MifareReadResult.Success(
                    cardUid = cardUid,
                    studentId = cardUid,
                    rawBlockBytes = dummyBlock,
                    sectorIndex = config.sectorIndex,
                    blockIndexInSector = config.blockIndexInSector,
                    absoluteBlockIndex = absoluteBlock,
                    cardTypeDescription = "USBリーダー ($readerDisplayName) UIDフォールバック",
                    isUidFallback = true
                )
            }
            return MifareReadResult.Error(
                cardUid = cardUid,
                title = "USBリーダー鍵ロードエラー",
                message = "USB NFCリーダー ($readerDisplayName) へのMIFARE鍵ロードコマンド (FF 82) が失敗しました (SW=$lastAuthSw)。"
            )
        }

        if (matchedCandidate == null) {
            if (config.allowUidFallback) {
                val dummyBlock = MifareClassicReader.createSimulatedBlock(cardUid)
                return MifareReadResult.Success(
                    cardUid = cardUid,
                    studentId = cardUid,
                    rawBlockBytes = dummyBlock,
                    sectorIndex = config.sectorIndex,
                    blockIndexInSector = config.blockIndexInSector,
                    absoluteBlockIndex = absoluteBlock,
                    cardTypeDescription = "USBリーダー ($readerDisplayName) UIDフォールバック",
                    isUidFallback = true
                )
            }
            val swInfo = lastAuthSw.ifEmpty { "認証失敗" }
            return MifareReadResult.AuthFailed(
                cardUid = cardUid,
                sectorIndex = config.sectorIndex,
                keyType = keyCandidates.first().keyType,
                message = "USBリーダーでセクター ${config.sectorIndex} (Block #$absoluteBlock) に対し、指定された計 ${keyCandidates.size} 通りの鍵候補すべてで認証に失敗しました (SW=$swInfo)。"
            )
        }

        // Step C: 16バイトデータブロックの読み取り (Read Binary: FF B0 00 <Block> 10)
        val readResp = transmitApdu(
            UsbCcidProtocol.buildReadBinaryBlockApdu(
                absoluteBlock = absoluteBlock,
                length = 16
            )
        )

        if (readResp == null || !readResp.isSuccess || readResp.data.isEmpty()) {
            val swInfo = readResp?.statusHex ?: "応答なし"
            return MifareReadResult.Error(
                cardUid = cardUid,
                title = "USBリーダー ブロック読み取り失敗",
                message = "セクター認証は成功しましたが、Block #$absoluteBlock の16バイト読み取り (FF B0) に失敗しました (SW=$swInfo)。"
            )
        }

        val rawBlock = readResp.data
        val readerDesc = "USB CCID ($readerDisplayName / ${matchedCandidate.summaryLabel})"

        // Step D: 読み取った16バイトブロックから学籍番号をデコード
        return when (val outcome = MifareClassicReader.extractStudentIdFromBlock(rawBlock, config)) {
            is MifareClassicReader.BlockParseOutcome.Valid -> MifareReadResult.Success(
                cardUid = cardUid,
                studentId = outcome.studentId,
                rawBlockBytes = rawBlock,
                sectorIndex = config.sectorIndex,
                blockIndexInSector = config.blockIndexInSector,
                absoluteBlockIndex = absoluteBlock,
                cardTypeDescription = readerDesc
            )

            is MifareClassicReader.BlockParseOutcome.Invalid -> MifareReadResult.ParseFailed(
                cardUid = cardUid,
                rawBlockBytes = rawBlock,
                sectorIndex = config.sectorIndex,
                blockIndexInSector = config.blockIndexInSector,
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
     * CCIDフレームをBulk-OUTで送信し、Bulk-INからCCIDレスポンスフレームを受信する（Time Extension 応答にも対応）
     */
    @Synchronized
    private fun sendCcidCommand(frame: ByteArray): UsbCcidProtocol.CcidResponse? {
        val conn = activeConnection ?: return null
        val epOut = bulkOutEndpoint ?: return null
        val epIn = bulkInEndpoint ?: return null

        val sent = conn.bulkTransfer(epOut, frame, frame.size, USB_TIMEOUT_MS)
        if (sent < 0) return null

        // Time Extension (commandStatus == 2) が返された場合は再度Bulk-INを読み取る
        repeat(6) {
            val rawResp = readFullCcidPacket(conn, epIn) ?: return null
            val parsed = UsbCcidProtocol.parseCcidResponse(rawResp) ?: return null
            if (parsed.commandStatus != 2) {
                return parsed
            }
        }
        return null
    }

    private fun readFullCcidPacket(
        conn: UsbDeviceConnection,
        epIn: UsbEndpoint
    ): ByteArray? {
        val buffer = ByteArray(1024)
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
