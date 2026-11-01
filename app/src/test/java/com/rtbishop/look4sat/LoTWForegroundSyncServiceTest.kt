package com.rtbishop.look4sat

import android.app.Application
import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import com.rtbishop.look4sat.core.domain.repository.LoTWForegroundStatus
import com.rtbishop.look4sat.core.domain.repository.LoTWForegroundSyncStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LoTWForegroundSyncServiceTest {
    @Before fun resetStatus() { LoTWForegroundSyncStatus.publish(LoTWForegroundStatus()) }
    @After fun clearStatus() { LoTWForegroundSyncStatus.publish(LoTWForegroundStatus()) }

    @Test @Config(sdk = [24]) fun androidSevenCreatesServiceWithoutCallingNotificationChannelApis() {
        val controller = Robolectric.buildService(LoTWForegroundSyncService::class.java).create()
        controller.get().onStartCommand(Intent().setAction("invalid"), 0, 1)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
    }

    @Test fun staleCompletionCannotStopOrOverwriteAnotherRun() {
        val controller = Robolectric.buildService(LoTWForegroundSyncService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "currentRun", 2L)
        LoTWForegroundSyncStatus.publish(LoTWForegroundStatus(running = true, message = "current run"))
        val finish = service.javaClass.getDeclaredMethod("finish", Long::class.javaPrimitiveType, String::class.java)
            .apply { isAccessible = true }
        finish.invoke(service, 1L, "old run")
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals("current run", LoTWForegroundSyncStatus.state.value.message)
        assertTrue(LoTWForegroundSyncStatus.state.value.running)
        finish.invoke(service, 2L, "done")
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(LoTWForegroundSyncStatus.state.value.running)
        controller.destroy()
    }

    @Test fun systemTimeoutStopsTheServiceAndPublishesAnInterruptedState() {
        val controller = Robolectric.buildService(LoTWForegroundSyncService::class.java).create()
        ReflectionHelpers.setField(controller.get(), "currentRun", 3L)
        LoTWForegroundSyncStatus.publish(LoTWForegroundStatus(running = true))
        controller.get().onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertFalse(LoTWForegroundSyncStatus.state.value.running)
        assertTrue(LoTWForegroundSyncStatus.state.value.message.orEmpty().contains("interrupted"))
        controller.destroy()
    }


    @Test fun completedDownloadProcessingDoesNotShowEndlessProgressSpinner() {
        val controller = Robolectric.buildService(LoTWForegroundSyncService::class.java).create()
        val service = controller.get()
        val method = service.javaClass.getDeclaredMethod(
            "notification", String::class.java,
            com.rtbishop.look4sat.core.domain.repository.LoTWProgress::class.java,
            Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }
        val connecting = method.invoke(service, "Connecting to LoTW", null, true) as Notification
        assertTrue(connecting.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        val saving = method.invoke(service, "Saving synchronized contacts", null, false) as Notification
        assertFalse(saving.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertEquals(0, saving.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        controller.destroy()
    }

    @Test fun idleCancellationDoesNotStartADownload() {
        val controller = Robolectric.buildService(LoTWForegroundSyncService::class.java).create()
        controller.get().onStartCommand(Intent().setAction(LoTWForegroundSyncService.ACTION_CANCEL), 0, 1)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertFalse(LoTWForegroundSyncStatus.state.value.running)
        controller.destroy()
    }
}
