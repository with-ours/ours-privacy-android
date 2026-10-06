package com.oursprivacy.oursprivacydemo

import com.oursprivacy.android.opmetrics.OursPrivacyInitOptions

fun demoVisitorLink(token: String): String =
    "https://example.com/landing?ours_visitor_id=$token-visitor&utm_source=demo&utm_medium=android&gclid=demoGclid"

fun demoInitOptions(recorderUrl: String, token: String = ""): OursPrivacyInitOptions {
    val builder = OursPrivacyInitOptions.builder().trackAutomaticEvents(true)
    if (recorderUrl.isNotBlank()) {
        builder.serverURL(recorderUrl)
        if (token.isNotBlank()) {
            builder.initialURL(demoVisitorLink(token))
        }
    }
    return builder.build()
}
