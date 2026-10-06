package top.nkbe.npatch.util

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NPatchRemoteStoreMigrationTest {
    private lateinit var context: IsolatedContext
    private lateinit var module: String
    private val group = "migration group"
    private val storedGroup = "migration_group"
    private val hostUserId get() = context.applicationInfo.uid / 100000

    @Before fun setUp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString().replace("-", "")
        module = "top.nkbe.migrationtest.m$suffix"
        context = IsolatedContext(base, File(base.cacheDir, "remote-migration-$suffix"))
    }

    @After fun tearDown() {
        closeStore()
        context.preferenceNames.forEach { context.deleteSharedPreferences(it) }
        assertTrue(context.root.deleteRecursively())
    }

    @Test fun importsEveryLegacyTypeAndKeepsLegacyFile() {
        val legacy = legacy()
        assertTrue(legacy.edit().putString("string", "old").putBoolean("boolean", true)
            .putInt("int", 42).putLong("long", 1234567890123L).putFloat("float", 1.25f)
            .putStringSet("set", setOf("one", "two")).commit())

        val imported = snapshot()

        assertEquals(legacy.all, imported)
        assertTrue(imported["int"] is Int)
        assertTrue(imported["long"] is Long)
        assertTrue(imported["float"] is Float)
        assertEquals(setOf("one", "two"), imported["set"])
        assertEquals(imported, legacy.all)
    }

    @Test fun firstWriteImportsUntouchedLegacyKeysBeforeApplyingDiff() {
        assertTrue(legacy().edit().putString("untouched", "old").putString("changed", "old").commit())

        store().updatePreferences(group, Bundle().apply {
            putSerializable("put", hashMapOf("changed" to "new", "added" to "new"))
        })

        assertEquals(mapOf("untouched" to "old", "changed" to "new", "added" to "new"), snapshot())
    }

    @Test fun clearDoesNotReimportAfterReopeningDatabase() {
        assertTrue(legacy().edit().putString("old", "value").commit())
        assertEquals("value", snapshot()["old"])

        store().updatePreferences(group, Bundle().apply { putBoolean("clear", true) })
        closeStore()

        assertTrue(snapshot().isEmpty())
        assertEquals("value", legacy().getString("old", null))
    }

    @Test fun deleteAsFirstOperationDoesNotReimportAfterReopeningDatabase() {
        assertTrue(legacy().edit().putString("old", "value").commit())

        store().deletePreferences(group)
        closeStore()

        assertTrue(snapshot().isEmpty())
        assertEquals("value", legacy().getString("old", null))
    }

    @Test fun keyDeletionAsFirstOperationDoesNotReimportAfterReopeningDatabase() {
        assertTrue(legacy().edit().putString("deleted", "old").putString("retained", "old").commit())

        store().updatePreferences(group, Bundle().apply {
            putSerializable("delete", hashSetOf("deleted"))
        })
        closeStore()

        assertEquals(mapOf("retained" to "old"), snapshot())
        assertTrue(legacy().contains("deleted"))
    }

    @Test fun upgradesVersionOneAndKeepsExistingSqliteValues() {
        assertTrue(legacy().edit().putString("existing", "legacy").putString("imported", "legacy").commit())
        context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE configs (module_pkg_name TEXT NOT NULL, user_id INTEGER NOT NULL, " +
                "`group` TEXT NOT NULL, `key` TEXT NOT NULL, data BLOB, " +
                "PRIMARY KEY (module_pkg_name, user_id, `group`, `key`))")
            val data = ByteArrayOutputStream().also { bytes ->
                ObjectOutputStream(bytes).use { it.writeObject("sqlite") }
            }.toByteArray()
            db.insertOrThrow("configs", null, ContentValues().apply {
                put("module_pkg_name", module)
                put("user_id", hostUserId)
                put("`group`", storedGroup)
                put("`key`", "existing")
                put("data", data)
            })
            db.version = 1
        }

        assertEquals(mapOf("existing" to "sqlite", "imported" to "legacy"), snapshot())
        context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null).use { db ->
            assertEquals(2, db.version)
            db.rawQuery("SELECT COUNT(*) FROM legacy_pref_migrations", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
        }
    }

    @Test fun foreignUserReadImportsOnlyIntoHostingUser() {
        assertTrue(legacy().edit().putString("old", "value").commit())
        val foreignUserId = hostUserId + 10

        store().getModulePrefs(foreignUserId, storedGroup)

        context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.rawQuery("SELECT user_id FROM configs WHERE module_pkg_name = ?", arrayOf(module)).use {
                assertTrue(it.moveToFirst())
                assertEquals(hostUserId, it.getInt(0))
                assertFalse(it.moveToNext())
            }
        }
        assertEquals(mapOf("old" to "value"), store().getModulePrefs(hostUserId, storedGroup))
    }

    @Test fun emptyLegacyGroupIsMarkedAndOtherGroupsStillImport() {
        assertTrue(snapshot().isEmpty())
        assertTrue(legacy().edit().putString("late", "old").commit())
        assertTrue(legacy("second").edit().putString("second", "value").commit())
        closeStore()

        assertTrue(snapshot().isEmpty())
        assertEquals(mapOf("second" to "value"), store().getModulePrefs(hostUserId, "second"))
    }

    @Test fun failedImportRollsBackValuesAndMarkerAndCanRetry() {
        assertTrue(legacy().edit().putString("one", "value").putString("two", "value").commit())
        store().getModulePrefs(hostUserId, "bootstrap")
        context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TRIGGER fail_second_import BEFORE INSERT ON configs " +
                "WHEN NEW.`group` = '$storedGroup' AND EXISTS " +
                "(SELECT 1 FROM configs WHERE `group` = '$storedGroup') " +
                "BEGIN SELECT RAISE(ABORT, 'injected import failure'); END")
        }

        try {
            snapshot()
            fail("Expected the injected import failure")
        } catch (_: SQLiteException) {
            context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null).use { db ->
                for (table in listOf("configs", "legacy_pref_migrations")) {
                    db.rawQuery("SELECT COUNT(*) FROM $table WHERE `group` = ?", arrayOf(storedGroup)).use {
                        assertTrue(it.moveToFirst())
                        assertEquals(0, it.getInt(0))
                    }
                }
                db.execSQL("DROP TRIGGER fail_second_import")
            }
        }

        assertEquals(mapOf("one" to "value", "two" to "value"), snapshot())
    }

    private fun legacy(name: String = storedGroup): SharedPreferences =
        context.getSharedPreferences("npatch_remote_${module}_$name", Context.MODE_PRIVATE)

    private fun store() = NPatchRemoteStore.get(context, module)

    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    private fun snapshot(): Map<String, Any> =
        store().requestPreferences(group, null).getSerializable("map") as Map<String, Any>

    @Suppress("UNCHECKED_CAST")
    private fun closeStore() {
        val instances = NPatchRemoteStore::class.java.getDeclaredField("INSTANCES").apply { isAccessible = true }
            .get(null) as MutableMap<String, NPatchRemoteStore>
        instances.remove("${context.applicationInfo.dataDir}:$module")
        val helpers = NPatchRemoteStore::class.java.getDeclaredField("HELPERS").apply { isAccessible = true }
            .get(null) as MutableMap<String, SQLiteOpenHelper>
        helpers.remove(context.applicationInfo.dataDir)?.close()
    }

    private class IsolatedContext(base: Context, val root: File) : ContextWrapper(base) {
        val preferenceNames = mutableSetOf<String>()
        private val info = ApplicationInfo(base.applicationInfo).apply { dataDir = root.absolutePath }

        init { check(root.mkdirs()) }

        override fun getApplicationContext(): Context = this
        override fun getApplicationInfo(): ApplicationInfo = info
        override fun getDatabasePath(name: String): File = File(root, name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath, factory, errorHandler)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            preferenceNames.add(name)
            return super.getSharedPreferences(name, mode)
        }
    }

    private companion object {
        const val DB_NAME = "npatch-xposed-remote.db"
    }
}
