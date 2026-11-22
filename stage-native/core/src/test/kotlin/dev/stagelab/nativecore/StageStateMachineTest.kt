package dev.stagelab.nativecore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StageStateMachineTest {

    private class FakeClock(private var now: Long = 1_000L) {
        fun tick(delta: Long = 100L): Long {
            now += delta
            return now
        }
        fun current(): Long = now
    }

    private class RecordingNativeWindowBridge : NativeWindowBridge {
        private var nextTaskId = 100
        val launchedPackages = mutableListOf<String>()
        val switches = mutableListOf<Pair<List<StageTask>, List<StageTask>>>()
        val parkedBatches = mutableListOf<List<StageTask>>()
        val broughtToActive = mutableListOf<StageTask>()
        val closedTaskIds = mutableListOf<Int>()
        var showDesktopCalls = 0

        override fun launchNativeWindow(packageName: String, preferredBounds: WindowBounds?): Int {
            launchedPackages += packageName
            return ++nextTaskId
        }

        override fun switchNativeStage(tasksToShow: List<StageTask>, tasksToPark: List<StageTask>) {
            switches += tasksToShow to tasksToPark
        }

        override fun parkNativeTasks(tasks: List<StageTask>) {
            parkedBatches += tasks
        }

        override fun bringTaskToActiveStage(task: StageTask) {
            broughtToActive += task
        }

        override fun updateTaskBounds(taskId: Int, bounds: WindowBounds) = Unit

        override fun showNativeDesktop() {
            showDesktopCalls++
        }

        override fun closeNativeTask(taskId: Int) {
            closedTaskIds += taskId
        }
    }

    private fun assertInvariants(session: StageSession) {
        val seenTaskIds = mutableSetOf<Int>()
        var activeCount = 0
        for ((id, stage) in session.stages) {
            assertEquals(id, stage.stageId)
            assertTrue("Stage $id must not be empty", stage.tasks.isNotEmpty())
            assertTrue("Stage $id exceeded 4 tasks", stage.tasks.size <= Stage.MAX_TASKS_PER_STAGE)
            if (stage.isActive) activeCount++
            for (t in stage.tasks) {
                assertTrue("Task ${t.taskId} belongs to multiple stages!", seenTaskIds.add(t.taskId))
            }
        }
        assertTrue("At most one Active Stage allowed", activeCount <= 1)
        if (session.activeStageId != null) {
            assertEquals(1, activeCount)
            assertTrue(session.stages[session.activeStageId]!!.isActive)
            assertFalse(session.recentStageIds.contains(session.activeStageId))
        } else {
            assertEquals(0, activeCount)
        }
        assertTrue(session.visibleRecentStages.size <= StageSession.MAX_VISIBLE_RECENT_STAGES)
    }

    @Test
    fun s1_launchNewAppCreatesStageAndMovesPreviousToRecentTop() {
        val clock = FakeClock()
        val bridge = RecordingNativeWindowBridge()
        val registry = StageRegistry(clock = { clock.tick() })
        val controller = StageTransitionController(registry, bridge)

        // Stage A: WeChat + Notes
        val sA = controller.onNativeTaskAppeared(
            StageTask(taskId = 1, packageName = "com.tencent.mm", bounds = WindowBounds(100, 100, 900, 1000))
        )
        val stageAId = sA.activeStageId!!
        controller.mergeTaskIntoActiveStage(
            StageTask(taskId = 2, packageName = "com.coloros.note", bounds = WindowBounds(920, 100, 1720, 1000))
        )

        // User launches Bilibili (not in any Stage)
        val sB = controller.onAppLaunchedFromLauncherOrDock("tv.danmaku.bili")
        assertInvariants(sB)
        assertNotNull(sB.activeStage)
        assertTrue(sB.activeStageId != stageAId)
        assertEquals(listOf("tv.danmaku.bili"), sB.activeStage!!.tasks.map { it.packageName })

        // Stage A preserved with 2 tasks and sits at top of Recent Strip
        val stageA = sB.stages[stageAId]!!
        assertFalse(stageA.isActive)
        assertEquals(listOf(1, 2), stageA.taskIds)
        assertEquals(stageAId, sB.visibleRecentStages.first().stageId)
    }

    @Test
    fun s1_launchExistingAppActivatesOwningStageWithoutDuplicatingTask() {
        val clock = FakeClock()
        val bridge = RecordingNativeWindowBridge()
        val registry = StageRegistry(clock = { clock.tick() })
        val controller = StageTransitionController(registry, bridge)

        val s1 = controller.onAppLaunchedFromLauncherOrDock("com.tencent.mm")
        val stageAId = s1.activeStageId!!
        val taskAId = s1.activeStage!!.tasks.single().taskId

        val s2 = controller.onAppLaunchedFromLauncherOrDock("tv.danmaku.bili")
        assertTrue(s2.activeStageId != stageAId)

        // Launching WeChat again activates Stage A instead of launching a duplicate task
        val launchesBefore = bridge.launchedPackages.size
        val s3 = controller.onAppLaunchedFromLauncherOrDock("com.tencent.mm")
        assertInvariants(s3)
        assertEquals(launchesBefore, bridge.launchedPackages.size)
        assertEquals(stageAId, s3.activeStageId)
        assertEquals(listOf(taskAId), s3.activeStage!!.taskIds)
    }

    @Test
    fun s2_switchStagePreservesTaskIdsBoundsAndZOrder() {
        val clock = FakeClock()
        val bridge = RecordingNativeWindowBridge()
        val registry = StageRegistry(clock = { clock.tick() })
        val controller = StageTransitionController(registry, bridge)
        val recentController = RecentStageController(controller)

        // Stage A = WeChat(10) + Notes(11)
        val boundsWeChat = WindowBounds(120, 120, 1020, 1400)
        val boundsNotes = WindowBounds(1100, 120, 2100, 1400)
        val aStageId = controller.onNativeTaskAppeared(
            StageTask(taskId = 10, packageName = "com.tencent.mm", bounds = boundsWeChat)
        ).activeStageId!!
        controller.mergeTaskIntoActiveStage(
            StageTask(taskId = 11, packageName = "com.coloros.note", bounds = boundsNotes)
        )
        // Focus WeChat so WeChat is top Z-order
        controller.onNativeTaskFocused(10)

        // Stage B = Bili(20) + Music(21)
        val bStageId = controller.onNativeTaskAppeared(
            StageTask(taskId = 20, packageName = "tv.danmaku.bili", bounds = WindowBounds(200, 150, 1200, 1450))
        ).activeStageId!!
        controller.mergeTaskIntoActiveStage(
            StageTask(taskId = 21, packageName = "com.netease.cloudmusic", bounds = WindowBounds(1250, 150, 2250, 1450))
        )

        // Click Stage A in Recent Strip
        val afterSwitch = recentController.onStageThumbnailClicked(aStageId)
        assertInvariants(afterSwitch)
        assertEquals(aStageId, afterSwitch.activeStageId)
        assertEquals(bStageId, afterSwitch.visibleRecentStages.first().stageId)

        val restoredA = afterSwitch.activeStage!!
        assertEquals(setOf(10, 11), restoredA.taskIds.toSet())
        assertEquals(boundsWeChat, restoredA.findTask(10)!!.bounds)
        assertEquals(boundsNotes, restoredA.findTask(11)!!.bounds)
        assertEquals(10, restoredA.topTask!!.taskId)

        // Verify bridge received live switch without relaunching
        assertEquals(0, bridge.launchedPackages.size)
        assertEquals(1, bridge.switches.size)
        val (shown, parked) = bridge.switches.single()
        assertEquals(listOf(11, 10), shown.map { it.taskId }) // bottom-to-top Z-order
        assertEquals(setOf(20, 21), parked.map { it.taskId }.toSet())
    }

    @Test
    fun s3_mergeFromRecentStageDestroysEmptySourceStage() {
        val clock = FakeClock()
        val bridge = RecordingNativeWindowBridge()
        val registry = StageRegistry(clock = { clock.tick() })
        val controller = StageTransitionController(registry, bridge)
        val recentController = RecentStageController(controller)

        val stageAId = controller.onNativeTaskAppeared(StageTask(1, "pkg.a")).activeStageId!!
        val stageBId = controller.onNativeTaskAppeared(StageTask(2, "pkg.b")).activeStageId!!

        // Drag task 1 from Stage A into Active Stage B
        val afterMerge = recentController.onStageTaskDraggedToCenter(stageAId, taskId = 1)
        assertInvariants(afterMerge)
        assertEquals(stageBId, afterMerge.activeStageId)
        assertEquals(setOf(1, 2), afterMerge.activeStage!!.taskIds.toSet())
        // Source Stage A became empty -> auto-destroyed
        assertNull(afterMerge.stages[stageAId])
        assertTrue(afterMerge.visibleRecentStages.isEmpty())
    }

    @Test
    fun s4_unlimitedWindowsPerStageNeverEvicts() {
        val clock = FakeClock()
        val bridge = RecordingNativeWindowBridge()
        val registry = StageRegistry(
            evictionPolicy = EvictionPolicy.LOWEST_Z_ORDER_THEN_EARLIEST,
            clock = { clock.tick() },
        )
        val controller = StageTransitionController(registry, bridge)

        controller.onNativeTaskAppeared(StageTask(1, "pkg.a"))
        controller.mergeTaskIntoActiveStage(StageTask(2, "pkg.b"))
        controller.mergeTaskIntoActiveStage(StageTask(3, "pkg.c"))
        controller.mergeTaskIntoActiveStage(StageTask(4, "pkg.d"))
        controller.mergeTaskIntoActiveStage(StageTask(5, "pkg.e"))
        val afterSix = controller.mergeTaskIntoActiveStage(StageTask(6, "pkg.mini", isMiniFloating = true))
        assertInvariants(afterSix)

        val active = afterSix.activeStage!!
        assertEquals(6, active.tasks.size)
        assertEquals(setOf(1, 2, 3, 4, 5, 6), active.taskIds.toSet())
        assertTrue(active.findTask(6)!!.isMiniFloating)
        assertTrue(bridge.closedTaskIds.isEmpty())
        assertTrue(afterSix.visibleRecentStages.isEmpty())
    }

    @Test
    fun s5_minimizeMultiWindowSplitsAndSingleWindowShowsDesktop() {
        val clock = FakeClock()
        val bridge = RecordingNativeWindowBridge()
        val registry = StageRegistry(clock = { clock.tick() })
        val controller = StageTransitionController(registry, bridge)
        val dockController = DockIntegrationController(controller, bridge)

        // Stage A = WeChat(1) + Notes(2) + Browser(3)
        val stageAId = controller.onNativeTaskAppeared(StageTask(1, "com.tencent.mm")).activeStageId!!
        controller.mergeTaskIntoActiveStage(StageTask(2, "com.coloros.note"))
        controller.mergeTaskIntoActiveStage(StageTask(3, "com.heytap.browser"))

        // Minimize Notes(2)
        val afterSplit = dockController.onWindowMenuMinimize(2)
        assertInvariants(afterSplit)
        assertEquals(stageAId, afterSplit.activeStageId)
        assertEquals(setOf(1, 3), afterSplit.activeStage!!.taskIds.toSet())
        assertEquals(listOf(2), afterSplit.visibleRecentStages.first().taskIds)
        assertEquals(0, bridge.showDesktopCalls)

        // Minimize remaining windows down to empty Active Stage
        dockController.onWindowMenuMinimize(1)
        val afterLastMin = dockController.onWindowMenuMinimize(3)
        assertInvariants(afterLastMin)
        assertNull(afterLastMin.activeStageId)
        assertEquals(1, bridge.showDesktopCalls)
        assertEquals(3, afterLastMin.allRecentStages.size)
    }

    @Test
    fun s6_closingLastTaskAutoDestroysStageWithoutAffectingOthers() {
        val clock = FakeClock()
        val bridge = RecordingNativeWindowBridge()
        val registry = StageRegistry(clock = { clock.tick() })
        val controller = StageTransitionController(registry, bridge)

        val stageAId = controller.onNativeTaskAppeared(StageTask(1, "pkg.a")).activeStageId!!
        val stageBId = controller.onNativeTaskAppeared(StageTask(2, "pkg.b")).activeStageId!!

        // Task 2 dies / closes
        val afterClose = controller.onNativeTaskVanished(2)
        assertInvariants(afterClose)
        assertNull(afterClose.stages[stageBId])
        assertNull(afterClose.activeStageId)
        assertEquals(1, bridge.showDesktopCalls)
        // Stage A remains untouched in Recent Strip
        assertNotNull(afterClose.stages[stageAId])
        assertEquals(listOf(stageAId), afterClose.visibleRecentStages.map { it.stageId })
    }

    @Test
    fun recentStripShowsMaxFourWhileKeepingHistoryAndHandles50RapidSwitches() {
        val clock = FakeClock()
        val bridge = RecordingNativeWindowBridge()
        val registry = StageRegistry(clock = { clock.tick() })
        val controller = StageTransitionController(registry, bridge)

        val stageIds = (1..6).map { i ->
            controller.onNativeTaskAppeared(StageTask(i, "pkg.$i")).activeStageId!!
        }
        val session = registry.snapshot()
        assertInvariants(session)
        assertEquals(4, session.visibleRecentStages.size)
        assertEquals(5, session.allRecentStages.size) // 6 total - 1 active = 5 recent in history

        // 50 rapid switches
        repeat(50) { idx ->
            val target = stageIds[idx % stageIds.size]
            val s = controller.switchToStage(target)
            assertInvariants(s)
            assertEquals(target, s.activeStageId)
        }
    }

    @Test
    fun duplicateTaskCallbacksAreIdempotentAndSystemRestartRestoresCleanly() {
        val clock = FakeClock()
        val bridge = RecordingNativeWindowBridge()
        val registry = StageRegistry(clock = { clock.tick() })
        val controller = StageTransitionController(registry, bridge)

        val t1 = StageTask(42, "com.android.settings", bounds = WindowBounds(100, 100, 900, 900))
        controller.onNativeTaskAppeared(t1)
        val afterDup = controller.onNativeTaskAppeared(t1.copy(bounds = WindowBounds(150, 150, 950, 950)))
        assertInvariants(afterDup)
        assertEquals(1, afterDup.stages.size)
        assertEquals(WindowBounds(150, 150, 950, 950), afterDup.activeStage!!.findTask(42)!!.bounds)

        // Simulate SystemUI restart with 5 visible + 2 background live tasks
        val live = listOf(
            StageTask(101, "pkg.1", zOrder = 1, isVisible = true),
            StageTask(102, "pkg.2", zOrder = 2, isVisible = true),
            StageTask(103, "pkg.3", zOrder = 3, isVisible = true),
            StageTask(104, "pkg.4", zOrder = 4, isVisible = true),
            StageTask(105, "pkg.5", zOrder = 5, isVisible = true),
            StageTask(106, "pkg.6", zOrder = 1, isVisible = false, lastFocusedAtMs = 500),
            StageTask(107, "pkg.7", zOrder = 1, isVisible = false, lastFocusedAtMs = 800),
        )
        val restored = controller.restoreFromSystemTasks(live)
        assertInvariants(restored)
        assertEquals(setOf(101, 102, 103, 104, 105), restored.activeStage!!.taskIds.toSet())
        assertEquals(2, restored.allRecentStages.size) // 106 + 107 (no 4-window overflow eviction)
    }
}
