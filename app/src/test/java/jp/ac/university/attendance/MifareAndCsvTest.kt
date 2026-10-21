package jp.ac.university.attendance

import jp.ac.university.attendance.data.local.AttendanceExportRow
import jp.ac.university.attendance.data.preferences.MifareConfig
import jp.ac.university.attendance.data.preferences.MifareKeyTextParser
import jp.ac.university.attendance.data.preferences.MifareKeyType
import jp.ac.university.attendance.data.preferences.StudentIdEncoding
import jp.ac.university.attendance.export.CsvExportManager
import jp.ac.university.attendance.nfc.MifareClassicReader
import jp.ac.university.attendance.usb.UsbCcidProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MifareAndCsvTest {

    @Test
    fun parseKeyBytes_validAndInvalidHex() {
        val validConfig = MifareConfig(keyHex = "A0A1A2A3A4A5", bulkKeysText = "A0A1A2A3A4A5")
        val bytes = validConfig.parseKeyBytes()
        assertArrayEquals(
            byteArrayOf(
                0xA0.toByte(),
                0xA1.toByte(),
                0xA2.toByte(),
                0xA3.toByte(),
                0xA4.toByte(),
                0xA5.toByte()
            ),
            bytes
        )

        val invalidShort = MifareConfig(keyHex = "FFFF", bulkKeysText = "FFFF")
        assertNull(invalidShort.parseKeyBytes())

        val invalidChars = MifareConfig(keyHex = "ZZZZZZZZZZZZ", bulkKeysText = "ZZZZZZZZZZZZ")
        assertNull(invalidChars.parseKeyBytes())
    }

    @Test
    fun bulkKeyTextParser_parsesMultiLineAndSectorSpecificKeys() {
        val bulkText = """
            # 共通の初期鍵
            FFFFFFFFFFFF
            A0A1A2A3A4A5 # MAD鍵
            1:B:112233445566
            2:AABBCCDDEEFF:665544332211
            [Sector: 3]
            D3F7D3F7D3F7
        """.trimIndent()

        val summary = MifareKeyTextParser.parseBulkText(
            rawText = bulkText,
            defaultKeyType = MifareKeyType.KEY_A,
            tryBothKeyTypesForPlainLines = true
        )
        assertTrue(summary.isValid)
        assertTrue(summary.invalidLines.isEmpty())

        val configSector1 = MifareConfig(
            sectorIndex = 1,
            keyType = MifareKeyType.KEY_A,
            bulkKeysText = bulkText,
            tryBothKeyTypes = false
        )
        val candidatesS1 = configSector1.resolveKeyCandidatesForSector(targetSector = 1)
        // Sector 1 固有の 112233445566 (Key B) が最優先され、その後に共通鍵 FFFFFFFFFFFF, A0A1A2A3A4A5 (Key A) が続く
        assertEquals(3, candidatesS1.size)
        assertEquals("112233445566", candidatesS1[0].keyHex)
        assertEquals(MifareKeyType.KEY_B, candidatesS1[0].keyType)
        assertEquals("FFFFFFFFFFFF", candidatesS1[1].keyHex)
        assertEquals(MifareKeyType.KEY_A, candidatesS1[1].keyType)
        assertEquals("A0A1A2A3A4A5", candidatesS1[2].keyHex)
    }

    @Test
    fun extractStudentIdFromBlock_asciiWithOffsetAndNullPadding() {
        // 先頭2バイトがフラグ、Byte 2〜9 に "24B10234"、残りが 0x00 の16バイトブロック
        val block = MifareClassicReader.createSimulatedBlock("24B10234", offset = 2)
        val config = MifareConfig(
            sectorIndex = 1,
            blockIndexInSector = 0,
            keyType = MifareKeyType.KEY_A,
            encoding = StudentIdEncoding.ASCII_UTF8,
            byteOffset = 2,
            byteLength = 10,
            trimNullAndSpaces = true
        )

        val outcome = MifareClassicReader.extractStudentIdFromBlock(block, config)
        assertTrue(outcome is MifareClassicReader.BlockParseOutcome.Valid)
        assertEquals("24B10234", (outcome as MifareClassicReader.BlockParseOutcome.Valid).studentId)
    }

    @Test
    fun extractStudentIdFromBlock_bcdEncoding() {
        // BCD表現で "20261234" + パディング 0xFF
        val block = ByteArray(16) { 0xFF.toByte() }
        block[0] = 0x20.toByte()
        block[1] = 0x26.toByte()
        block[2] = 0x12.toByte()
        block[3] = 0x34.toByte()

        val config = MifareConfig(
            encoding = StudentIdEncoding.BCD_DECIMAL,
            byteOffset = 0,
            byteLength = 6,
            trimNullAndSpaces = true
        )

        val outcome = MifareClassicReader.extractStudentIdFromBlock(block, config)
        assertTrue(outcome is MifareClassicReader.BlockParseOutcome.Valid)
        assertEquals("20261234", (outcome as MifareClassicReader.BlockParseOutcome.Valid).studentId)
    }

    @Test
    fun usbCcidProtocol_buildsValidPcScApdusAndFrames() {
        // Sector 1, Block 0 -> Absolute Block 4
        val absBlock = UsbCcidProtocol.sectorToAbsoluteBlock(sectorIndex = 1, blockIndexInSector = 0)
        assertEquals(4, absBlock)

        // Load Key APDU: FF 82 00 00 06 FF FF FF FF FF FF
        val keyBytes = ByteArray(6) { 0xFF.toByte() }
        val loadApdu = UsbCcidProtocol.buildLoadKeyApdu(keyBytes, keyStructure = 0x00, keySlot = 0x00)
        assertEquals(11, loadApdu.size)
        assertEquals(0xFF.toByte(), loadApdu[0])
        assertEquals(0x82.toByte(), loadApdu[1])
        assertEquals(0x06.toByte(), loadApdu[4])

        // General Authenticate APDU: FF 86 00 00 05 01 00 04 60 00
        val authApdu = UsbCcidProtocol.buildGeneralAuthenticateApdu(
            absoluteBlock = absBlock,
            keyType = MifareKeyType.KEY_A,
            keySlot = 0x00
        )
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0x86.toByte(), 0x00, 0x00, 0x05, 0x01, 0x00, 0x04, 0x60, 0x00),
            authApdu
        )

        // Read Binary APDU: FF B0 00 04 10
        val readApdu = UsbCcidProtocol.buildReadBinaryBlockApdu(absoluteBlock = absBlock, length = 16)
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xB0.toByte(), 0x00, 0x04, 0x10),
            readApdu
        )

        // CCID XfrBlock Frame & Response Parsing
        val xfrFrame = UsbCcidProtocol.buildXfrBlock(slot = 0x00, seq = 0x07, apdu = readApdu)
        assertEquals(15, xfrFrame.size)
        assertEquals(UsbCcidProtocol.MSG_PC_TO_RDR_XFR_BLOCK, xfrFrame[0])
        assertEquals(5, xfrFrame[1].toInt())

        // Simulated RDR_to_PC_DataBlock with "24B10234" + 90 00
        val simulatedBlock = MifareClassicReader.createSimulatedBlock("24B10234")
        val apduPayload = simulatedBlock + byteArrayOf(0x90.toByte(), 0x00.toByte())
        val ccidRespBytes = UsbCcidProtocol.buildCcidFrame(
            messageType = UsbCcidProtocol.MSG_RDR_TO_PC_DATA_BLOCK,
            slot = 0x00,
            seq = 0x07,
            param1 = 0x00, // status = 0 (ICC active, no error)
            payload = apduPayload
        )
        val parsedCcid = UsbCcidProtocol.parseCcidResponse(ccidRespBytes)
        assertNotNull(parsedCcid)
        assertTrue(parsedCcid!!.isCardPresent)
        assertEquals(0, parsedCcid.commandStatus)

        val parsedApdu = UsbCcidProtocol.parseApduResponse(parsedCcid.payload)
        assertNotNull(parsedApdu)
        assertTrue(parsedApdu!!.isSuccess)
        assertEquals(16, parsedApdu.data.size)
    }

    @Test
    fun buildCsvContent_containsRequiredColumnsAndEscapesProperly() {
        val sampleRows = listOf(
            AttendanceExportRow(
                recordId = 1L,
                courseId = 10L,
                courseCode = "INFO-101",
                courseName = "情報工学概論",
                scheduleAndRoom = "火曜2限",
                sessionId = 100L,
                sessionNumber = 3,
                sessionTitle = "ネットワーク基礎",
                sessionDate = "2026-10-20",
                studentId = "24B10234",
                cardUid = "04A1B2C3D4",
                registeredAt = 1792428000000L,
                registrationMethod = "NFC(USB)",
                note = ""
            )
        )

        val csv = CsvExportManager.buildCsvContent(sampleRows, includeUtf8Bom = true)
        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("講義コード,講義名,講義回,講義回タイトル,講義日,学籍番号,出席登録日時,登録方法,カードUID,備考"))
        assertTrue(csv.contains("INFO-101,情報工学概論,第3回,ネットワーク基礎,2026-10-20,24B10234,"))
    }
}
