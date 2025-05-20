/**
 * This package contains the interface to OursPrivacy that you can use from your
 * Android apps. You can use OursPrivacy to send events, update people analytics properties.
 *
 * The primary interface to OursPrivacy services is in {@link com.oursprivacy.android.opmetrics.OursPrivacyAPI}.
 * At it's simplest, you can send events with
 * <pre>
 * {@code
 *
 * OursPrivacyAPI oursprivacy = OursPrivacyAPI.getInstance(context, OURSPRIVACY_TOKEN);
 * oursprivacy.track("Library integrated", null);
 *
 * }
 * </pre>
 *
 * In addition to this reference documentation, you can also see our overview
 * and getting started documentation at
 * <a href="https://oursprivacy.com/help/reference/android" target="_blank"
 *    >https://oursprivacy.com/help/reference/android</a>
 *
 */
package com.oursprivacy.android.opmetrics;