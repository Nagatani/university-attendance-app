package app.nagatani.dev.attendance.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 講義（科目）マスタエンティティ
 */
@Entity(
    tableName = "courses",
    indices = [Index(value = ["courseCode"])]
)
data class CourseEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 講義コード（例: CS-2026-A） */
    val courseCode: String,
    /** 講義名（例: 情報工学概論） */
    val courseName: String,
    /** 担当教員名（任意） */
    val instructorName: String = "",
    /** 曜日・時限・教室（例: 火曜2限・大講義室201） */
    val scheduleAndRoom: String = "",
    /** 作成日時 (Epoch ms) */
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 講義回エンティティ（第1回〜第15回など）
 */
@Entity(
    tableName = "lecture_sessions",
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["courseId", "sessionNumber"], unique = true)
    ]
)
data class LectureSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 紐づく講義ID */
    val courseId: Long,
    /** 講義回（1, 2, ... 15） */
    val sessionNumber: Int,
    /** 講義回タイトル・サブテーマ（例: 第1回 ガイダンス） */
    val sessionTitle: String = "",
    /** 実施日（YYYY-MM-DD） */
    val sessionDate: String,
    /** 作成日時 (Epoch ms) */
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 出席登録データエンティティ
 * 同一講義回（sessionId）における同一学籍番号（studentId）の重複登録をユニーク制約でも防止
 */
@Entity(
    tableName = "attendance_records",
    foreignKeys = [
        ForeignKey(
            entity = LectureSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CourseEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sessionId", "studentId"], unique = true),
        Index(value = ["courseId"]),
        Index(value = ["studentId"])
    ]
)
data class AttendanceRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 講義ID */
    val courseId: Long,
    /** 講義回ID */
    val sessionId: Long,
    /** 学籍番号（MIFARE Classicブロックから読み取った文字列、または手動入力） */
    val studentId: String,
    /** 学生証MIFAREカードのUID（16進数表記、手動入力時は空文字） */
    val cardUid: String = "",
    /** 出席登録日時（ミリ秒タイムスタンプ） */
    val registeredAt: Long = System.currentTimeMillis(),
    /** 登録手段（"NFC" または "MANUAL"） */
    val registrationMethod: String = "NFC",
    /** 備考（遅刻・手動追加理由など） */
    val note: String = ""
)

/**
 * 講義回ごとの出席者数集計つきモデル
 */
data class SessionWithCount(
    @Embedded
    val session: LectureSessionEntity,
    @ColumnInfo(name = "attendanceCount")
    val attendanceCount: Int
)

/**
 * エクスポートおよび一覧表示用の結合データモデル
 * （講義・講義回・学籍番号・出席登録日時をすべて保持）
 */
data class AttendanceExportRow(
    val recordId: Long,
    val courseId: Long,
    val courseCode: String,
    val courseName: String,
    val scheduleAndRoom: String,
    val sessionId: Long,
    val sessionNumber: Int,
    val sessionTitle: String,
    val sessionDate: String,
    val studentId: String,
    val cardUid: String,
    val registeredAt: Long,
    val registrationMethod: String,
    val note: String
)
