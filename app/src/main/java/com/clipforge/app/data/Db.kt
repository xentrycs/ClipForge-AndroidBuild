package com.clipforge.app.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.io.File

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    val sourceTitle: String,
    val createdAt: Long,
    val durationMs: Long,
    val thumbPath: String?,
    val status: String,            // ANALYZING | READY | FAILED
    val statusMessage: String?,
    val sourcePath: String,
    val sourceKind: String         // LOCAL | YOUTUBE
)

@Entity(
    tableName = "clips",
    foreignKeys = [ForeignKey(
        entity = ProjectEntity::class,
        parentColumns = ["id"],
        childColumns = ["projectId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("projectId")]
)
data class ClipEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val title: String,
    val category: String,
    val score: Int,
    val startMs: Long,
    val endMs: Long,
    val filePath: String,
    val thumbPath: String?,
    /** true once this clip has been saved to the gallery (used by the Continue tab). */
    @ColumnInfo(defaultValue = "0") val exported: Boolean = false
)

data class ClipCount(val projectId: String, val n: Int)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    fun projects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun project(id: String): Flow<ProjectEntity?>

    @Query("SELECT * FROM clips WHERE projectId = :id ORDER BY score DESC")
    fun clips(id: String): Flow<List<ClipEntity>>

    @Query("SELECT projectId, COUNT(*) AS n FROM clips GROUP BY projectId")
    fun clipCounts(): Flow<List<ClipCount>>

    /** Clips that have not been exported yet, per project. */
    @Query("SELECT projectId, COUNT(*) AS n FROM clips WHERE exported = 0 GROUP BY projectId")
    fun unexportedCounts(): Flow<List<ClipCount>>

    @Query("UPDATE clips SET exported = 1 WHERE id = :id")
    suspend fun markExported(id: String)

    @Query("UPDATE clips SET exported = 1 WHERE projectId = :projectId")
    suspend fun markProjectExported(projectId: String)

    @Query("SELECT * FROM clips WHERE projectId = :id")
    suspend fun clipsOnce(id: String): List<ClipEntity>

    @Query("DELETE FROM clips WHERE projectId = :id")
    suspend fun deleteClipsOf(id: String)

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun projectOnce(id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: ProjectEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClip(c: ClipEntity)

    @Query("UPDATE projects SET status = :status, statusMessage = :message WHERE id = :id")
    suspend fun setStatus(id: String, status: String, message: String?)

    @Query("UPDATE projects SET status = 'FAILED', statusMessage = 'Interrupted: Android stopped the app while analysing. Tap Retry.' WHERE status = 'ANALYZING'")
    suspend fun failInterrupted()

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProject(id: String)

    @Query("DELETE FROM clips WHERE id = :id")
    suspend fun deleteClip(id: String)
}

@Database(entities = [ProjectEntity::class, ClipEntity::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): ProjectDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(ctx: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "clipforge.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}

/** v2.3 -> v2.4: remember which clips were already exported. Old clips count as not exported yet. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE clips ADD COLUMN exported INTEGER NOT NULL DEFAULT 0")
    }
}

object Paths {
    fun projects(ctx: Context) = File(ctx.filesDir, "projects")
    fun staging(ctx: Context) = File(ctx.filesDir, "staging")
}
