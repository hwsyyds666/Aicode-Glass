package com.aicode.feature.workspace.domain

import com.aicode.core.watch.ChangeKind
import com.aicode.core.watch.ChangeRoot
import com.aicode.core.watch.FileChange
import com.aicode.core.watch.FileChangeBatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseInvalidationPlanTest {

    private val root = "/workspace"

    private fun change(path: String, kind: ChangeKind) =
        FileChange(ChangeRoot.WORKSPACE, "/host$path", path, kind)

    @Test
    fun `新建文件_失效父目录`() {
        val plan = planBrowseInvalidation(
            FileChangeBatch(listOf(change("/workspace/src/main.kt", ChangeKind.CREATED))), root
        )
        assertFalse(plan.fullRefresh)
        assertTrue("/workspace/src" in plan.relistDirs)
        assertTrue(plan.dropTrees.isEmpty())
    }

    @Test
    fun `修改文件_同时失效自身与父目录`() {
        val plan = planBrowseInvalidation(
            FileChangeBatch(listOf(change("/workspace/src/main.kt", ChangeKind.MODIFIED))), root
        )
        assertTrue("/workspace/src" in plan.relistDirs)
        assertTrue("/workspace/src/main.kt" in plan.relistDirs)
        assertTrue(plan.dropTrees.isEmpty())
    }

    @Test
    fun `删除目录_整棵树丢弃`() {
        val plan = planBrowseInvalidation(
            FileChangeBatch(listOf(change("/workspace/build", ChangeKind.DELETED))), root
        )
        assertTrue("/workspace/build" in plan.dropTrees)
        assertTrue("/workspace" in plan.relistDirs)
    }

    @Test
    fun `截断批次_只能全量刷新`() {
        val plan = planBrowseInvalidation(
            FileChangeBatch(emptyList(), truncated = true), root
        )
        assertTrue(plan.fullRefresh)
    }

    @Test
    fun `根gitignore被改_提醒重读规则`() {
        val plan = planBrowseInvalidation(
            FileChangeBatch(listOf(change("/workspace/.gitignore", ChangeKind.MODIFIED))), root
        )
        assertTrue(plan.gitignoreTouched)
    }

    @Test
    fun `嵌套目录的gitignore不算根级`() {
        val plan = planBrowseInvalidation(
            FileChangeBatch(listOf(change("/workspace/sub/.gitignore", ChangeKind.MODIFIED))), root
        )
        assertFalse(plan.gitignoreTouched)
    }

    @Test
    fun `空批次_计划为空`() {
        val plan = planBrowseInvalidation(FileChangeBatch(emptyList()), root)
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `根路径自身的变更只失效根`() {
        val plan = planBrowseInvalidation(
            FileChangeBatch(listOf(change("/workspace", ChangeKind.MODIFIED))), root
        )
        assertEquals(setOf("/workspace"), plan.relistDirs)
    }
}
