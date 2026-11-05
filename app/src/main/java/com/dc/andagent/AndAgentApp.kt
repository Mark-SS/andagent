package com.dc.andagent

import android.app.Application
import com.dc.andagent.cron.CronScheduler

class AndAgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { CronScheduler.ensure(this) }
            .onFailure { android.util.Log.e("AndAgentApp", "Cron recovery failed", it) }
    }
}
