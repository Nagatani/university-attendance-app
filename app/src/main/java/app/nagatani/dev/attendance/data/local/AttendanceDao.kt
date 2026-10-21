package app.nagatani.dev.attendance.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AttendanceDao {

    // =========================================================
    // 講義（Course）操作
    // =========================================================

    @Query("SELECT * FROM courses ORDER BY createdAt DESC")
    fun observeAllCourses(): Flow<List<CourseEntity>>

    @Query("SELECT * FROM courses WHERE id = :courseId LIMIT 1")
    suspend fun getCourseById(courseId: Long): CourseEntity?

    @Query("SELECT COUNT(*) FROM courses")
    suspend fun getCourseCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourse(course: CourseEntity): Long

    @Update
    suspend fun updateCourse(course: CourseEntity)

    @Delete
    suspend fun deleteCourse(course: CourseEntity)

    // =========================================================
    // 講義回（LectureSession）操作
    // =========================================================

    @Query(
        """
        SELECT s.*, COUNT(a.id) AS attendanceCount
        FROM lecture_sessions s
        LEFT JOIN attendance_records a ON s.id = a.sessionId
        WHERE s.courseId = :courseId
        GROUP BY s.id
        ORDER BY s.sessionNumber ASC
        """
    )
    fun observeSessionsWithCountByCourse(courseId: Long): Flow<List<SessionWithCount>>

    @Query("SELECT * FROM lecture_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: Long): LectureSessionEntity?

    @Query("SELECT MAX(sessionNumber) FROM lecture_sessions WHERE courseId = :courseId")
    suspend fun getMaxSessionNumber(courseId: Long): Int?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSession(session: LectureSessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSessions(sessions: List<LectureSessionEntity>)

    @Update
    suspend fun updateSession(session: LectureSessionEntity)

    @Delete
    suspend fun deleteSession(session: LectureSessionEntity)

    // =========================================================
    // 出席登録（AttendanceRecord）操作
    // =========================================================

    @Query(
        """
        SELECT * FROM attendance_records
        WHERE sessionId = :sessionId
        ORDER BY registeredAt DESC
        """
    )
    fun observeAttendanceBySession(sessionId: Long): Flow<List<AttendanceRecordEntity>>

    @Query(
        """
        SELECT * FROM attendance_records
        WHERE sessionId = :sessionId AND studentId = :studentId
        LIMIT 1
        """
    )
    suspend fun findAttendanceRecord(sessionId: Long, studentId: String): AttendanceRecordEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttendance(record: AttendanceRecordEntity): Long

    @Delete
    suspend fun deleteAttendance(record: AttendanceRecordEntity)

    @Query("DELETE FROM attendance_records WHERE id = :recordId")
    suspend fun deleteAttendanceById(recordId: Long)

    // =========================================================
    // CSVエクスポート・履歴検索用結合クエリ
    // =========================================================

    @Query(
        """
        SELECT
            a.id AS recordId,
            c.id AS courseId,
            c.courseCode AS courseCode,
            c.courseName AS courseName,
            c.scheduleAndRoom AS scheduleAndRoom,
            s.id AS sessionId,
            s.sessionNumber AS sessionNumber,
            s.sessionTitle AS sessionTitle,
            s.sessionDate AS sessionDate,
            a.studentId AS studentId,
            a.cardUid AS cardUid,
            a.registeredAt AS registeredAt,
            a.registrationMethod AS registrationMethod,
            a.note AS note
        FROM attendance_records a
        INNER JOIN courses c ON a.courseId = c.id
        INNER JOIN lecture_sessions s ON a.sessionId = s.id
        ORDER BY a.registeredAt DESC
        """
    )
    fun observeAllAttendanceExportRows(): Flow<List<AttendanceExportRow>>

    @Query(
        """
        SELECT
            a.id AS recordId,
            c.id AS courseId,
            c.courseCode AS courseCode,
            c.courseName AS courseName,
            c.scheduleAndRoom AS scheduleAndRoom,
            s.id AS sessionId,
            s.sessionNumber AS sessionNumber,
            s.sessionTitle AS sessionTitle,
            s.sessionDate AS sessionDate,
            a.studentId AS studentId,
            a.cardUid AS cardUid,
            a.registeredAt AS registeredAt,
            a.registrationMethod AS registrationMethod,
            a.note AS note
        FROM attendance_records a
        INNER JOIN courses c ON a.courseId = c.id
        INNER JOIN lecture_sessions s ON a.sessionId = s.id
        WHERE a.sessionId = :sessionId
        ORDER BY a.registeredAt ASC
        """
    )
    suspend fun getExportRowsBySession(sessionId: Long): List<AttendanceExportRow>

    @Query(
        """
        SELECT
            a.id AS recordId,
            c.id AS courseId,
            c.courseCode AS courseCode,
            c.courseName AS courseName,
            c.scheduleAndRoom AS scheduleAndRoom,
            s.id AS sessionId,
            s.sessionNumber AS sessionNumber,
            s.sessionTitle AS sessionTitle,
            s.sessionDate AS sessionDate,
            a.studentId AS studentId,
            a.cardUid AS cardUid,
            a.registeredAt AS registeredAt,
            a.registrationMethod AS registrationMethod,
            a.note AS note
        FROM attendance_records a
        INNER JOIN courses c ON a.courseId = c.id
        INNER JOIN lecture_sessions s ON a.sessionId = s.id
        WHERE a.courseId = :courseId
        ORDER BY s.sessionNumber ASC, a.registeredAt ASC
        """
    )
    suspend fun getExportRowsByCourse(courseId: Long): List<AttendanceExportRow>

    @Query(
        """
        SELECT
            a.id AS recordId,
            c.id AS courseId,
            c.courseCode AS courseCode,
            c.courseName AS courseName,
            c.scheduleAndRoom AS scheduleAndRoom,
            s.id AS sessionId,
            s.sessionNumber AS sessionNumber,
            s.sessionTitle AS sessionTitle,
            s.sessionDate AS sessionDate,
            a.studentId AS studentId,
            a.cardUid AS cardUid,
            a.registeredAt AS registeredAt,
            a.registrationMethod AS registrationMethod,
            a.note AS note
        FROM attendance_records a
        INNER JOIN courses c ON a.courseId = c.id
        INNER JOIN lecture_sessions s ON a.sessionId = s.id
        ORDER BY c.courseCode ASC, s.sessionNumber ASC, a.registeredAt ASC
        """
    )
    suspend fun getAllExportRows(): List<AttendanceExportRow>
}
