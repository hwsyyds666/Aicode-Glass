package com.aicode.core.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aicode.feature.agent.data.local.database.AgentDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Room 官方迁移测试（Robolectric，JVM 上跑真实的 Android SQLite）。
 *
 * 本容器（Android 宿主上的 PRoot）JVM 加载不了 Robolectric 的 conscrypt native 库
 * （UnsatisfiedLinkError，java.library.path 指向 /data/app、/system 等 Android 路径），
 * 故用 [guardEnvironment] 在非标准 Linux 环境跳过——跳过不算失败；
 * CI（ubuntu x86_64）与普通开发机上真实执行。
 *
 * 历史版本（8~49）的 schema json 当年未导出，[MigrationTestHelper] 只能覆盖
 * 「当前版本及以后」的路径；历史路径的连续/无重复由 [MigrationFilesParseTest] 与
 * scripts/check_migrations.py 双向兜底。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java
    )

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    companion object {
        @JvmStatic
        @BeforeClass
        fun guardEnvironment() {
            val androidContainer = File("/system").exists() ||
                System.getProperty("java.library.path")?.contains("/data/app") == true
            // 除 Android 容器外，只要当前 JVM 加载不了 Robolectric 依赖的 conscrypt native
            // 库（如 Windows ARM 缺 conscrypt_openjdk_jni-windows-aarch_64），同样无法执行——
            // 按「能不能跑」而不是「是不是 Android」判断，避免非标准开发机把这条用例打成失败。
            val conscryptLoadable = runCatching {
                // isAvailable() 会真正触发 native 库加载并吞掉 UnsatisfiedLinkError，
                // 因此是「当前环境能不能跑 Robolectric」的准确判据。
                Class.forName("org.conscrypt.Conscrypt")
                    .getMethod("isAvailable")
                    .invoke(null) as Boolean
            }.getOrDefault(false)
            assumeTrue(
                "Robolectric 需要标准 Linux/CI 环境且能加载 conscrypt native 库（当前环境不满足）",
                !androidContainer && conscryptLoadable
            )
        }
    }

    @Test
    fun open_current_schema_passes_identity_check() {
        helper.createDatabase(dbName, AgentDatabase.SCHEMA_VERSION).close()

        val db = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
        // 打开即触发 Room 的 identity/schema 校验，entity 与迁移产物不符会抛异常
        db.openHelper.writableDatabase
        db.close()
    }

    /**
     * 性能专项 17·P1-4：迁移 59（58→59）为 agent_messages 建 (sessionId, timestamp) 复合索引。
     *
     * 验证点：
     * 1. 旧版本库（v58）灌入的数据升级后完整无损；
     * 2. 旧单列索引 index_agent_messages_sessionId 被删、新复合索引存在
     *    （sqlite_master 与 PRAGMA index_info 双重确认）；
     * 3. 核心查询 `WHERE sessionId = ? ORDER BY timestamp DESC LIMIT n` 走复合索引，
     *    且不再出现 USE TEMP B-TREE FOR ORDER BY。
     *
     * runMigrationsAndValidate 会按导出的 59.json 逐表校验 TableInfo（含索引），
     * 迁移产物与实体 Index 注解不一致时这里直接抛异常。
     */
    @Test
    fun migration_58_to_59_agentMessagesSessionTimestampIndex() {
        // 1. 以 v58 建库并灌两条样本消息（覆盖 agent_messages 全部 NOT NULL 列）
        helper.createDatabase(dbName, 58).use { db ->
            db.execSQL(
                "INSERT INTO agent_messages (id, sessionId, role, content, timestamp, isError, " +
                    "isCompacted, isContextSummary, isCompactionMarker, inputTokens, outputTokens, " +
                    "cachedInputTokens, isContextExcluded) VALUES " +
                    "('m1', 'sess-a', 'USER', '你好', 1000, 0, 0, 0, 0, 0, 0, 0, 0), " +
                    "('m2', 'sess-a', 'ASSISTANT', '在的', 2000, 0, 0, 0, 0, 0, 0, 0, 0)"
            )
        }

        // 2. 从 assets 取 58→59 的文件式迁移，跑迁移并按 59.json 校验 schema
        val migration59 = MigrationLoader.loadMigrations(context)
            .filterIsInstance<FileMigration>()
            .first { it.version == 59 }
        val db = helper.runMigrationsAndValidate(
            dbName,
            59,
            true,
            migration59
        )

        // 3. 数据完整：两条消息都在
        db.query("SELECT COUNT(*) FROM agent_messages").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2, c.getInt(0))
        }

        // 4. 索引换血：旧单列索引已删、新复合索引存在
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'agent_messages'"
        ).use { c ->
            val names = mutableListOf<String>()
            while (c.moveToNext()) names += c.getString(0)
            assertFalse("旧单列索引应已删除，实际: $names", names.contains("index_agent_messages_sessionId"))
            assertTrue("新复合索引应存在，实际: $names", names.contains("index_agent_messages_sessionId_timestamp"))
        }
        db.query("PRAGMA index_info(index_agent_messages_sessionId_timestamp)").use { c ->
            val cols = mutableListOf<String>()
            while (c.moveToNext()) cols += c.getString(2)
            assertEquals(listOf("sessionId", "timestamp"), cols)
        }

        // 5. 核心查询走复合索引且无 TEMP B-TREE 排序
        db.query(
            "EXPLAIN QUERY PLAN SELECT * FROM agent_messages " +
                "WHERE sessionId = ? ORDER BY timestamp DESC LIMIT ?",
            arrayOf<Any>("sess-a", 20)
        ).use { c ->
            val plan = mutableListOf<String>()
            while (c.moveToNext()) plan += c.getString(3)
            assertTrue(
                "应使用复合索引 index_agent_messages_sessionId_timestamp，实际计划: $plan",
                plan.any { it.contains("index_agent_messages_sessionId_timestamp") }
            )
            assertFalse(
                "不应再出现 USE TEMP B-TREE FOR ORDER BY，实际计划: $plan",
                plan.any { it.contains("USE TEMP B-TREE") }
            )
        }
        db.close()
    }

    @Test
    fun migration_59_to_67_preservesSubagentsAndDropsOpenAIProfiles() {
        helper.createDatabase(dbName, 59).use { db ->
            db.execSQL(
                "INSERT INTO chat_sessions (id, title, createdAt, updatedAt, workspacePath, mode, " +
                    "reasoningEffort, providerId, model, totalInputTokens, totalOutputTokens, lastInputTokens, isPinned, parentId, subagentType) " +
                    "VALUES ('parent', 'parent', 1, 1, '/workspace/app', 'BUILD', 'MEDIUM', NULL, NULL, 0, 0, 0, 0, NULL, NULL), " +
                    "('child', 'child', 2, 2, '/workspace/app', 'BUILD', 'MEDIUM', 'openai_account_old', 'gpt-old', 0, 0, 0, 0, 'parent', 'Explore')"
            )
        }
        val migrations = MigrationLoader.loadMigrations(context)
            .filterIsInstance<FileMigration>()
            .filter { it.version in 60..67 }
            .sortedBy(FileMigration::version)
        assertEquals(listOf(60, 61, 62, 63, 64, 65, 66, 67), migrations.map(FileMigration::version))
        val db = helper.runMigrationsAndValidate(dbName, 67, true, *migrations.toTypedArray())
        db.query("SELECT subagentStatus, allowedWritePaths, allowedReadPaths FROM chat_sessions WHERE id = 'child'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("COMPLETED", c.getString(0))
            assertTrue(c.isNull(1))
            assertTrue(c.isNull(2))
        }
        db.query("SELECT subagentCreatedCount FROM chat_sessions WHERE id = 'parent'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db.query("SELECT subagentReworkCount FROM chat_sessions WHERE id = 'child'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.query("SELECT providerId, model FROM chat_sessions WHERE id = 'child'").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
            assertTrue(c.isNull(1))
        }
        db.query("PRAGMA table_info(checkpoint_file_snapshots)").use { c ->
            val columns = buildSet {
                while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name")))
            }
            assertTrue(columns.contains("expectedFingerprint"))
        }
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'openai_accounts'").use { c ->
            assertFalse(c.moveToFirst())
        }
        db.close()

        val roomDb = Room.databaseBuilder(context, AgentDatabase::class.java, dbName).build()
        runBlocking {
            val dao = roomDb.chatSessionDao()
            assertEquals(1, dao.reserveSubagentRework("child", "parent", 2))
            dao.updateSubagentStatus("child", "COMPLETED")
            assertEquals(1, dao.reserveSubagentRework("child", "parent", 2))
            dao.updateSubagentStatus("child", "COMPLETED")
            assertEquals("the persisted limit must reject a third round", 0, dao.reserveSubagentRework("child", "parent", 2))
            assertEquals(2, dao.getById("child")?.subagentReworkCount)
        }
        roomDb.close()
    }
}
