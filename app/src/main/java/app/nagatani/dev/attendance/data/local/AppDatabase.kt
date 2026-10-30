package app.nagatani.dev.attendance.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.time.LocalDate

@Database(
    entities = [
        CourseEntity::class,
        LectureSessionEntity::class,
        AttendanceRecordEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun attendanceDao(): AttendanceDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "university_attendance.db"
                )
                    // 出席記録を失わないよう破壊的マイグレーションは使用しない。
                    // スキーマ変更時は version を上げ、app/schemas の差分をもとに Migration を addMigrations() で追加すること
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private const val META_PREFS_NAME = "university_attendance_db_meta"
        private const val KEY_SAMPLE_DATA_SEEDED = "sample_data_seeded"

        /**
         * 初回起動時に講義マスタが空の場合、すぐにタブレットで動作確認できるようサンプル講義・講義回を作成する。
         * ユーザーが講義を全件削除した後に再作成されないよう、判定は一度だけ行う。
         */
        suspend fun ensureSampleDataOnFirstLaunch(context: Context, dao: AttendanceDao) {
            val metaPrefs = context.applicationContext.getSharedPreferences(META_PREFS_NAME, Context.MODE_PRIVATE)
            if (metaPrefs.getBoolean(KEY_SAMPLE_DATA_SEEDED, false)) return
            if (dao.getCourseCount() == 0) {
                insertSampleData(dao)
            }
            metaPrefs.edit().putBoolean(KEY_SAMPLE_DATA_SEEDED, true).apply()
        }

        private suspend fun insertSampleData(dao: AttendanceDao) {

            val today = LocalDate.now().toString()
            val courseId1 = dao.insertCourse(
                CourseEntity(
                    courseCode = "INFO-101",
                    courseName = "情報工学概論",
                    instructorName = "情報 太郎",
                    scheduleAndRoom = "火曜2限 / 講義棟201室"
                )
            )
            dao.insertSessions(
                (1..15).map { num ->
                    LectureSessionEntity(
                        courseId = courseId1,
                        sessionNumber = num,
                        sessionTitle = when (num) {
                            1 -> "ガイダンス・イントロダクション"
                            2 -> "コンピュータアーキテクチャ基礎"
                            3 -> "ネットワークと情報セキュリティ"
                            else -> "第${num}回 講義"
                        },
                        sessionDate = today
                    )
                }
            )

            val courseId2 = dao.insertCourse(
                CourseEntity(
                    courseCode = "MATH-204",
                    courseName = "線形代数学演習",
                    instructorName = "理数 花子",
                    scheduleAndRoom = "木曜3限 / 演習室B"
                )
            )
            dao.insertSessions(
                (1..15).map { num ->
                    LectureSessionEntity(
                        courseId = courseId2,
                        sessionNumber = num,
                        sessionTitle = "第${num}回 演習",
                        sessionDate = today
                    )
                }
            )
        }
    }
}
