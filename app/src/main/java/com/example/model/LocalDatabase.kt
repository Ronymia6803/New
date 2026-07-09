package com.example.model

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Delete
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "blocked_apps")
data class BlockedApp(
    @PrimaryKey val packageName: String,
    val isPermanent: Boolean = true,
    val startTime: String? = null, // Format: "HH:MM"
    val endTime: String? = null,   // Format: "HH:MM"
    val dailyLimitMinutes: Int = 0, // 0 means no limit
    val usedMinutesToday: Int = 0,
    val lastUsedDate: String? = null // Format: "YYYY-MM-DD"
)

@Entity(tableName = "blocked_websites")
data class BlockedWebsite(
    @PrimaryKey val domain: String
)

@Dao
interface BlockerDao {
    @Query("SELECT * FROM blocked_apps")
    fun getAllBlockedApps(): Flow<List<BlockedApp>>

    @Query("SELECT * FROM blocked_apps")
    suspend fun getBlockedAppsList(): List<BlockedApp>

    @Query("SELECT * FROM blocked_apps WHERE packageName = :packageName LIMIT 1")
    suspend fun getBlockedApp(packageName: String): BlockedApp?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedApp(blockedApp: BlockedApp)

    @Query("DELETE FROM blocked_apps WHERE packageName = :packageName")
    suspend fun deleteBlockedAppByPackage(packageName: String)

    @Query("UPDATE blocked_apps SET usedMinutesToday = :minutes, lastUsedDate = :date WHERE packageName = :packageName")
    suspend fun updateUsedMinutes(packageName: String, minutes: Int, date: String)

    @Query("SELECT * FROM blocked_websites")
    fun getAllBlockedWebsites(): Flow<List<BlockedWebsite>>

    @Query("SELECT * FROM blocked_websites")
    suspend fun getBlockedWebsitesList(): List<BlockedWebsite>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedWebsite(website: BlockedWebsite)

    @Query("DELETE FROM blocked_websites WHERE domain = :domain")
    suspend fun deleteBlockedWebsiteByDomain(domain: String)
}

@Database(entities = [BlockedApp::class, BlockedWebsite::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun blockerDao(): BlockerDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "blocker_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class BlockerRepository(private val blockerDao: BlockerDao) {
    val allBlockedApps: Flow<List<BlockedApp>> = blockerDao.getAllBlockedApps()
    val allBlockedWebsites: Flow<List<BlockedWebsite>> = blockerDao.getAllBlockedWebsites()

    suspend fun getBlockedAppsList(): List<BlockedApp> = blockerDao.getBlockedAppsList()
    suspend fun getBlockedWebsitesList(): List<BlockedWebsite> = blockerDao.getBlockedWebsitesList()

    suspend fun getBlockedApp(packageName: String): BlockedApp? = blockerDao.getBlockedApp(packageName)

    suspend fun insertBlockedApp(blockedApp: BlockedApp) = blockerDao.insertBlockedApp(blockedApp)

    suspend fun deleteBlockedAppByPackage(packageName: String) = blockerDao.deleteBlockedAppByPackage(packageName)

    suspend fun updateUsedMinutes(packageName: String, minutes: Int, date: String) =
        blockerDao.updateUsedMinutes(packageName, minutes, date)

    suspend fun insertBlockedWebsite(domain: String) =
        blockerDao.insertBlockedWebsite(BlockedWebsite(domain))

    suspend fun deleteBlockedWebsiteByDomain(domain: String) =
        blockerDao.deleteBlockedWebsiteByDomain(domain)
}
