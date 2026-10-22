package app.nagatani.dev.attendance.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nagatani.dev.attendance.data.preferences.MifareConfig
import app.nagatani.dev.attendance.data.preferences.MifareKeyType
import app.nagatani.dev.attendance.data.preferences.StudentIdEncoding
import app.nagatani.dev.attendance.nfc.MifareClassicReader
import app.nagatani.dev.attendance.nfc.MifareReadResult
import app.nagatani.dev.attendance.usb.UsbDeviceSummary
import app.nagatani.dev.attendance.usb.UsbReaderState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MifareSettingsScreen(
    currentConfig: MifareConfig,
    lastDiagnosticResult: MifareReadResult?,
    usbReaderState: UsbReaderState,
    detectedUsbDevices: List<UsbDeviceSummary>,
    onScanUsbReaders: () -> Unit,
    onConnectUsbDevice: (String) -> Unit,
    onDisconnectUsbReader: () -> Unit,
    onSaveConfig: (MifareConfig) -> Unit,
    onDraftConfigChanged: (MifareConfig?) -> Unit = {},
    onSimulateDiagnosticCard: (sampleStudentId: String) -> Unit
) {
    val clipboardManager = LocalClipboardManager.current

    // 編集用ステート（保存済み設定で初期化）
    var sectorText by remember(currentConfig) { mutableStateOf(currentConfig.sectorIndex.toString()) }
    var blockText by remember(currentConfig) { mutableStateOf(currentConfig.blockIndexInSector.toString()) }
    var keyType by remember(currentConfig) { mutableStateOf(currentConfig.keyType) }
    var bulkKeysText by remember(currentConfig) {
        mutableStateOf(currentConfig.bulkKeysText.ifBlank { currentConfig.keyHex })
    }
    var tryBothKeyTypes by remember(currentConfig) {
        mutableStateOf(currentConfig.tryBothKeyTypesForPlainHex)
    }
    var showKeyPlain by remember { mutableStateOf(true) }

    var encoding by remember(currentConfig) { mutableStateOf(currentConfig.encoding) }
    var offsetText by remember(currentConfig) { mutableStateOf(currentConfig.byteOffset.toString()) }
    var lengthText by remember(currentConfig) { mutableStateOf(currentConfig.byteLength.toString()) }
    var trimPadding by remember(currentConfig) { mutableStateOf(currentConfig.trimNullAndSpaces) }
    var regexText by remember(currentConfig) { mutableStateOf(currentConfig.validationRegex) }
    var allowUidFallback by remember(currentConfig) { mutableStateOf(currentConfig.allowUidFallback) }
    var kioskPin by remember(currentConfig) { mutableStateOf(currentConfig.kioskPin) }

    // 現在フォームに入力されているドラフト設定を生成
    val draftConfig = remember(
        sectorText, blockText, keyType, bulkKeysText, tryBothKeyTypes, encoding,
        offsetText, lengthText, trimPadding, regexText, allowUidFallback, kioskPin
    ) {
        val safeOffset = (offsetText.toIntOrNull() ?: 0).coerceIn(0, 15)
        val safeLength = (lengthText.toIntOrNull() ?: 10).coerceIn(1, 16 - safeOffset)
        val temp = MifareConfig(
            sectorIndex = (sectorText.toIntOrNull() ?: 1).coerceIn(0, 39),
            blockIndexInSector = (blockText.toIntOrNull() ?: 0).coerceIn(0, 14),
            keyType = keyType,
            bulkKeysText = bulkKeysText,
            tryBothKeyTypesForPlainHex = tryBothKeyTypes,
            encoding = encoding,
            byteOffset = safeOffset,
            byteLength = safeLength,
            trimNullAndSpaces = trimPadding,
            validationRegex = regexText,
            allowUidFallback = allowUidFallback,
            kioskPin = kioskPin.trim()
        )
        val primary = temp.resolveKeyCandidatesForSector(temp.sectorIndex).firstOrNull()
        temp.copy(
            keyHex = primary?.keyHex ?: "FFFFFFFFFFFF"
        )
    }

    val parseSummary = remember(draftConfig) { draftConfig.parseBulkKeys() }
    val activeCandidatesForSector = remember(draftConfig) {
        draftConfig.resolveKeyCandidatesForSector(draftConfig.sectorIndex)
    }
    val isKeyValid = activeCandidatesForSector.isNotEmpty() && parseSummary.errors.isEmpty()

    // 設定画面で入力中の鍵・セクターを「保存ボタンを押す前」でも即座にUSB/本体NFC診断へ反映
    LaunchedEffect(draftConfig, isKeyValid) {
        if (isKeyValid) {
            onDraftConfigChanged(draftConfig)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            onDraftConfigChanged(null)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ============================================================
        // 左ペイン: MIFARE Classic 鍵・セクター・抽出フォーマット設定フォーム
        // ============================================================
        Card(
            modifier = Modifier
                .weight(1.15f)
                .fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "MIFARE Classic 学生証 鍵・読み取り設定",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = { onSaveConfig(draftConfig) },
                        enabled = isKeyValid
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("設定を保存")
                    }
                }

                HorizontalDivider()

                // 1. セクター・ブロック・認証鍵の一括テキスト設定
                Text(
                    text = "1. 読み取り対象セクター・ブロック ＆ 鍵データ一括テキスト指定",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = sectorText,
                        onValueChange = { sectorText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("読み取りセクター番号 (1K: 0〜15)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = blockText,
                        onValueChange = { blockText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("セクター内ブロック番号 (通常 0〜2)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                // 一括テキスト指定のツールバー（クリップボード貼り付け・プリセット追加・表示切替）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "セクター認証鍵リスト（複数行・一括テキスト指定）:",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                val clip = clipboardManager.getText()?.text
                                if (!clip.isNullOrBlank()) {
                                    bulkKeysText = clip
                                }
                            }
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("貼り付け")
                        }

                        IconButton(onClick = { showKeyPlain = !showKeyPlain }) {
                            Icon(
                                imageVector = if (showKeyPlain) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "鍵テキストの表示切替"
                            )
                        }
                    }
                }

                // 複数行の鍵データ一括入力フィールド
                OutlinedTextField(
                    value = bulkKeysText,
                    onValueChange = { bulkKeysText = it },
                    label = {
                        Text("1行に1件ずつ指定（12桁HEX / セクター:AまたはB:12桁HEX / セクター:KeyA:KeyB）")
                    },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp
                    ),
                    isError = !isKeyValid,
                    visualTransformation = if (showKeyPlain) VisualTransformation.None else PasswordVisualTransformation(),
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )

                // 代表的な鍵リストや書式サンプルをワンタップで挿入するチップ群
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AssistChip(
                        onClick = {
                            bulkKeysText =
                                "# 標準プリセット鍵一括指定 (MIFARE Classic Tool互換)\n" +
                                "FFFFFFFFFFFF\n" +
                                "A0A1A2A3A4A5\n" +
                                "D3F7D3F7D3F7"
                        },
                        label = { Text("標準3鍵を一括セット") }
                    )
                    AssistChip(
                        onClick = {
                            val sec = draftConfig.sectorIndex
                            bulkKeysText =
                                "# セクター番号・Key A/B 明示指定サンプル\n" +
                                "$sec:A:FFFFFFFFFFFF\n" +
                                "$sec:B:FFFFFFFFFFFF\n" +
                                "$sec:A0A1A2A3A4A5:B0B1B2B3B4B5"
                        },
                        label = { Text("セクター:A/B:鍵 書式例") }
                    )
                    AssistChip(
                        onClick = { bulkKeysText = "FFFFFFFFFFFF" },
                        label = { Text("初期値 (FFFF...) のみ") }
                    )
                }

                // 12桁HEX単体記述時の挙動設定
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("優先鍵種別:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    MifareKeyType.entries.forEach { type ->
                        FilterChip(
                            selected = keyType == type,
                            onClick = { keyType = type },
                            label = { Text("${type.displayName} 優先") }
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { tryBothKeyTypes = !tryBothKeyTypes }
                ) {
                    Checkbox(
                        checked = tryBothKeyTypes,
                        onCheckedChange = { tryBothKeyTypes = it }
                    )
                    Text(
                        text = "鍵種別(A/B)を省略した12桁HEX行は Key A と Key B の両方を順に自動試行する",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                // リアルタイム鍵パース診断ボックス
                Surface(
                    color = if (isKeyValid) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (parseSummary.errors.isNotEmpty()) {
                            Text(
                                text = "鍵テキストの書式エラー (${parseSummary.errors.size}件):",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            parseSummary.errors.take(4).forEach { err ->
                                Text(
                                    text = "・$err",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        } else if (activeCandidatesForSector.isEmpty()) {
                            Text(
                                text = "セクター ${draftConfig.sectorIndex} に適用可能な鍵が0件です。有効な12桁HEXキーを入力してください。",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else {
                            Text(
                                text = "有効な鍵行: ${parseSummary.validLineCount}行  →  セクター ${draftConfig.sectorIndex} への認証試行候補: ${activeCandidatesForSector.size}通り",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            val previewList = activeCandidatesForSector.take(6).mapIndexed { idx, c ->
                                "${idx + 1}. ${c.keyType.displayName}:${if (showKeyPlain) c.keyHex else "••••••••••••"}"
                            }.joinToString("  /  ")
                            Text(
                                text = "試行順: $previewList" +
                                    if (activeCandidatesForSector.size > 6) " ...他${activeCandidatesForSector.size - 6}件" else "",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                HorizontalDivider()

                // 2. 学籍番号データ抽出範囲・文字コード設定
                Text(
                    text = "2. 16バイトブロックからの学籍番号抽出フォーマット",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StudentIdEncoding.entries.forEach { enc ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { encoding = enc }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = encoding == enc,
                                onClick = { encoding = enc }
                            )
                            Column {
                                Text(text = enc.displayName, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = enc.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = offsetText,
                        onValueChange = { offsetText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("開始バイト位置 (Offset: 0〜15)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lengthText,
                        onValueChange = { lengthText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("読み取りバイト長 (Length: 1〜16)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { trimPadding = !trimPadding }
                ) {
                    Checkbox(
                        checked = trimPadding,
                        onCheckedChange = { trimPadding = it }
                    )
                    Text("NULL文字 (0x00) や前後の空白・制御文字を自動的に除去する（推奨）")
                }

                OutlinedTextField(
                    value = regexText,
                    onValueChange = { regexText = it },
                    label = { Text("抽出・検証用正規表現（任意・空欄で全抽出 / 例: [0-9A-Za-z-]+）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider()

                // 3. 運用補助・セキュリティ設定
                Text(
                    text = "3. 運用補助・セキュリティオプション",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                OutlinedTextField(
                    value = kioskPin,
                    onValueChange = { kioskPin = it.filter { ch -> ch.isDigit() }.take(8) },
                    label = { Text("出席受付モード解除用の管理者PIN（任意・空欄でPINなし）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { allowUidFallback = !allowUidFallback }
                ) {
                    Checkbox(
                        checked = allowUidFallback,
                        onCheckedChange = { allowUidFallback = it }
                    )
                    Text("テスト用フォールバック: MIFARE Classic以外のNFCタグ検出時にカードUIDを学籍番号として読み取る")
                }

                Button(
                    onClick = { onSaveConfig(draftConfig) },
                    enabled = isKeyValid,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("このMIFARE読み取り設定を保存して適用する", fontWeight = FontWeight.Bold)
                }
            }
        }

        // ============================================================
        // 右ペイン: 外付けUSB NFCリーダー管理 ＆ リアルタイム・カード診断
        // ============================================================
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // --------------------------------------------------------
                // 外付け USB NFCリーダー (PC/SC CCID) 接続状態＆デバイス一覧
                // --------------------------------------------------------
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Usb,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "外付け USB NFCリーダー (PC/SC CCID)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (usbReaderState is UsbReaderState.Connected) {
                            OutlinedButton(onClick = onDisconnectUsbReader) {
                                Text("切断")
                            }
                        }
                        OutlinedButton(onClick = onScanUsbReaders) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("USB再検出")
                        }
                    }
                }

                // 現在のUSB接続ステータスボックス
                val (statusBg, statusTitle, statusDetail) = when (usbReaderState) {
                    is UsbReaderState.Connected -> Triple(
                        Color(0xFFDCFCE7),
                        "接続中: ${usbReaderState.deviceName}",
                        String.format(
                            "VID:%04X / PID:%04X  |  ステータス: %s",
                            usbReaderState.vendorId,
                            usbReaderState.productId,
                            if (usbReaderState.cardPresent) "カード検知中" else "カードタッチ待機中"
                        )
                    )
                    is UsbReaderState.PermissionRequired -> Triple(
                        Color(0xFFFEF3C7),
                        "USBアクセス権限の許可待ち: ${usbReaderState.deviceName}",
                        "画面に表示されたUSBアクセス許可ダイアログで「OK」を押してください"
                    )
                    is UsbReaderState.Error -> Triple(
                        Color(0xFFFEE2E2),
                        "USBリーダー通信エラー",
                        usbReaderState.message
                    )
                    is UsbReaderState.Disconnected -> Triple(
                        MaterialTheme.colorScheme.surfaceVariant,
                        "USB NFCリーダー未接続（本体内蔵NFCのみ待機中）",
                        "ACS ACR122U / ACR1252U、Sony RC-S300、Identiv 等のPC/SC (CCID) 対応USBリーダーを接続すると自動認識します"
                    )
                }

                Surface(
                    color = statusBg,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = statusTitle,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = statusDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (detectedUsbDevices.isNotEmpty()) {
                    Text(
                        text = "検出されたUSBデバイス (${detectedUsbDevices.size}台):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        detectedUsbDevices.forEach { dev ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = dev.displayName,
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = buildString {
                                                append(dev.vidPidHex)
                                                if (dev.isCcidClass) append("  [CCIDスマートカード規格]")
                                                if (dev.isKnownNfcVendor) append("  [NFC対応メーカー]")
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { onConnectUsbDevice(dev.usbDeviceNameKey) }
                                    ) {
                                        Text("この機器に接続")
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                // --------------------------------------------------------
                // リアルタイム・カード診断（本体NFC ＆ 外付けUSBリーダー共通）
                // --------------------------------------------------------
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Nfc,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "リアルタイム・カード診断（16バイト検証）",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            onSaveConfig(draftConfig)
                            onSimulateDiagnosticCard("24B10234")
                        }
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("サンプルデータで検証")
                    }
                }

                Text(
                    text = "この画面を開いた状態で実際の学生証（MIFARE Classic）を【外付けUSB NFCリーダー】または【タブレット本体NFC】にかざすと、出席登録は行わずにセクター認証結果と16バイトブロックの生データ（HEX/ASCII）および抽出される学籍番号をその場で確認できます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                if (lastDiagnosticResult == null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Nfc,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "学生証をタッチするか、右上の「サンプルデータで検証」を押してください",
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    // 直近のカード診断結果を表示
                    val rawBytes: ByteArray? = when (lastDiagnosticResult) {
                        is MifareReadResult.Success -> lastDiagnosticResult.rawBlockBytes
                        is MifareReadResult.ParseFailed -> lastDiagnosticResult.rawBlockBytes
                        else -> null
                    }

                    // ステータスカード
                    when (lastDiagnosticResult) {
                        is MifareReadResult.Success -> {
                            Surface(
                                color = Color(0xFFDCFCE7),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = Color(0xFF15803D),
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "セクター認証・ブロック読み取り成功",
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF14532D)
                                        )
                                        Text(
                                            text = "UID: ${lastDiagnosticResult.cardUid}  |  ${lastDiagnosticResult.cardTypeDescription}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF166534)
                                        )
                                        Text(
                                            text = "Sector ${lastDiagnosticResult.sectorIndex} / Block ${lastDiagnosticResult.blockIndexInSector} (絶対Block #${lastDiagnosticResult.absoluteBlockIndex})",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF166534)
                                        )
                                    }
                                }
                            }
                        }

                        is MifareReadResult.AuthFailed -> {
                            Surface(
                                color = Color(0xFFFEE2E2),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Error,
                                        contentDescription = null,
                                        tint = Color(0xFFB91C1C),
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "セクター認証失敗 (UID: ${lastDiagnosticResult.cardUid})",
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF7F1D1D)
                                        )
                                        Text(
                                            text = lastDiagnosticResult.message,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF991B1B)
                                        )
                                    }
                                }
                            }
                        }

                        is MifareReadResult.ParseFailed -> {
                            Surface(
                                color = Color(0xFFFEF3C7),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = "ブロック読み取り成功・文字列抽出エラー (UID: ${lastDiagnosticResult.cardUid})",
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF78350F)
                                    )
                                    Text(
                                        text = lastDiagnosticResult.reason,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF92400E)
                                    )
                                }
                            }
                        }

                        is MifareReadResult.Error -> {
                            Surface(
                                color = Color(0xFFFEE2E2),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = lastDiagnosticResult.title,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF7F1D1D)
                                    )
                                    Text(
                                        text = lastDiagnosticResult.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF991B1B)
                                    )
                                }
                            }
                        }
                    }

                    // 16バイトの生データグリッド表示 & 現在のドラフト設定でのリアルタイム抽出プレビュー
                    if (rawBytes != null && rawBytes.isNotEmpty()) {
                        val selectedRange = draftConfig.byteOffset until
                            (draftConfig.byteOffset + draftConfig.byteLength).coerceAtMost(rawBytes.size)

                        Text(
                            text = "読み取った16バイトブロックの内訳（青枠＝現在の抽出範囲 Offset ${draftConfig.byteOffset}〜${selectedRange.last}）:",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            rawBytes.forEachIndexed { idx, b ->
                                val inSelectedRange = idx in selectedRange
                                val unsigned = b.toInt() and 0xFF
                                val hexStr = String.format("%02X", unsigned)
                                val asciiChar = if (unsigned in 0x20..0x7E) unsigned.toChar().toString() else "·"

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .width(50.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (inSelectedRange) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .border(
                                            width = if (inSelectedRange) 2.dp else 1.dp,
                                            color = if (inSelectedRange) MaterialTheme.colorScheme.primary else Color.Transparent,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .padding(vertical = 6.dp, horizontal = 4.dp)
                                ) {
                                    Text(
                                        text = "+$idx",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = hexStr,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = asciiChar,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        // 現在のドラフト設定をこの16バイトブロックに適用したリアルタイム抽出結果
                        val liveOutcome = remember(rawBytes, draftConfig) {
                            MifareClassicReader.extractStudentIdFromBlock(rawBytes, draftConfig)
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "現在の設定による抽出結果プレビュー（学籍番号）:",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                when (liveOutcome) {
                                    is MifareClassicReader.BlockParseOutcome.Valid -> {
                                        Text(
                                            text = liveOutcome.studentId,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 28.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }

                                    is MifareClassicReader.BlockParseOutcome.Invalid -> {
                                        Text(
                                            text = "抽出エラー: ${liveOutcome.reason}",
                                            color = MaterialTheme.colorScheme.error,
                                            fontWeight = FontWeight.Bold
                                        )
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
