package com.oursprivacy.oursprivacydemo

import com.oursprivacy.android.opmetrics.OursPrivacyInitOptions

fun demoInitOptions(recorderUrl: String): OursPrivacyInitOptions {
    val builder = OursPrivacyInitOptions.builder().trackAutomaticEvents(true)
    if (recorderUrl.isNotBlank()) {
        builder.serverURL(recorderUrl)
    }
    return builder.build()
}
