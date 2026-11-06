package com.tabtap.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The project database.
 *
 * Journal mode is write-ahead logging, which is both faster and safer for the frequent
 * small autosaves this app makes, and synchronous mode is left at Room's default FULL so
 * a save that has returned really is on disk before the app can be killed.
 */
@Database(
    entities = [ProjectEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class TabTapDatabase : RoomDatabase() {

    abstract fun projects(): ProjectDao

    companion object {
        private const val NAME = "tabtap.db"

        @Volatile
        private var instance: TabTapDatabase? = null

        fun get(context: Context): TabTapDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): TabTapDatabase =
            Room.databaseBuilder(context, TabTapDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                // There are no migrations yet; when there are, they go here. Destructive
                // fallback is deliberately *not* enabled: losing a user's projects to a
                // schema change is never an acceptable outcome.
                .build()

        /**
         * Forget the open database and delete its files.
         *
         * Only for tests: the singleton outlives a single test in a shared JVM, so
         * without this one test's projects turn up in the next one's library, and the
         * app opens the most recent project instead of showing an empty list.
         */
        fun resetForTests(context: Context) {
            synchronized(this) {
                instance?.close()
                instance = null
            }
            val app = context.applicationContext
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                app.getDatabasePath(NAME + suffix).delete()
            }
        }

        /** An in-memory database, for tests. */
        fun inMemory(context: Context): TabTapDatabase =
            Room.inMemoryDatabaseBuilder(context, TabTapDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
