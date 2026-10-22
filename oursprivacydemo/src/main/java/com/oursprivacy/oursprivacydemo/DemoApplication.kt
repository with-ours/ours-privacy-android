package com.oursprivacy.oursprivacydemo

import android.app.Application
import com.oursprivacy.android.opmetrics.OursPrivacyAPI

class DemoApplication : Application() {
    val sdk: OursPrivacyAPI? by lazy {
        if (BuildConfig.OURSPRIVACY_TOKEN.isBlank()) {
            null
        } else {
            OursPrivacyAPI(this).also {
                it.initialize(
                    BuildConfig.OURSPRIVACY_TOKEN,
                    demoInitOptions(BuildConfig.RECORDER_URL, BuildConfig.OURSPRIVACY_TOKEN)
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sdk
    }
}
