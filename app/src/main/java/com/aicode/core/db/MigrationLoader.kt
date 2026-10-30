package com.aicode.core.db

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aicode.core.util.FileLogger

class FileMigration(
    val version: Int,
    val scriptName: String,
    val sqlStatements: List<String>
) : Migration(version - 1, version) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 整段迁移包在事务里：任一条语句失败整体回滚，避免留半迁移状态。
        // 迁移记录不再写入一个未声明的临时表；Room 会把该表视为意外 schema，
        // 导致所有历史迁移在校验阶段失败。版本和脚本名已由资产文件与日志提供。
        db.beginTransaction()
        try {
            for (sql in sqlStatements) {
                db.execSQL(sql)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        FileLogger.i("MigrationLoader", "Applied migration: $scriptName")
    }
}

object MigrationLoader {
    fun loadMigrations(context: Context): Array<Migration> {
        val assetManager = context.assets
        val migrationsDir = "migrations"
        val files = runCatching { assetManager.list(migrationsDir) }.getOrNull() ?: emptyArray()
        
        val migrations = mutableListOf<Migration>()
        
        // File format: {version}_{description}.sql, e.g., "7_add_workspace_path.sql"
        for (fileName in files) {
            if (!fileName.endsWith(".sql")) continue
            
            val versionStr = fileName.substringBefore('_')
            val version = versionStr.toIntOrNull() ?: continue
            
            val sqlContent = runCatching {
                assetManager.open("$migrationsDir/$fileName").bufferedReader().use { it.readText() }
            }.getOrNull() ?: continue
            
            val statements = SqlScriptSplitter.split(sqlContent)
            
            migrations.add(FileMigration(version, fileName, statements))
        }
        
        return migrations.toTypedArray()
    }
}
