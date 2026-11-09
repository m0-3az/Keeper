package com.keeper.vault.duress.intruder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntruderPurgeSchedulingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun periodicPurgeIsScheduledAsUniqueWork() {
        // The app's startup provider may already have initialized WorkManager in this process;
        // the test initializer must not run twice. Either configuration is fine for this check.
        runCatching { WorkManagerTestInitHelper.initializeTestWorkManager(context) }

        IntruderPurgeScheduler.schedule(context)

        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(IntruderPurgeScheduler.UNIQUE_WORK_NAME)
            .get()

        assertThat(workInfos).isNotEmpty()
        assertThat(workInfos.map { it.state }).contains(WorkInfo.State.ENQUEUED)
    }
}
