<p align="center">
  <img src="https://user-images.githubusercontent.com/71290498/231855731-2d3774c3-dc41-4595-abfb-9c49f5f84103.png" alt="OursPrivacy Android Library" height="150"/>
</p>

# Latest Version

##### _March 31, 2025_ - [v8.0.3](https://github.com/oursprivacy/oursprivacy-android/releases/tag/v8.0.3)

# Table of Contents

<!-- MarkdownTOC -->

- [Quick Start Guide](#quick-start-guide)
    - [Install OursPrivacy](#1-install-oursprivacy)
    - [Initialize OursPrivacy](#2-initialize-oursprivacy)
    - [Send Data](#3-send-data)
    - [Check for Success](#4-check-for-success)
- [FAQ](#i-want-to-know-more)
- [I want to know more!](#i-want-to-know-more)
- [Want to Contribute?](#want-to-contribute)
- [Changelog](#changelog)
- [License](#license)

<!-- /MarkdownTOC -->

<a name="quick-start-guide"></a>
# Quick Start Guide

Check out our official documentation for more in depth information on installing and using OursPrivacy on Android.

## 1. Install OursPrivacy
You will need your project token for initializing your library.

**Step 1 - Add the oursprivacy-android library as a gradle dependency:**
We publish builds of our library to the Maven central repository as an .aar file. This file contains all of the classes, resources, and configurations that you'll need to use the library. To install the library inside Android Studio, you can simply declare it as dependency in your build.gradle file.

Add the following lines to the `dependencies` section in *app/build.gradle*

```gradle
implementation "com.oursprivacy.android:oursprivacy-android:7.+"
```
 
Once you've updated your build.gradle file, you can force Android Studio to sync with your new configuration by clicking the Sync Project with Gradle Files icon at the top of the window.

![Sync Android With Gradle](https://storage.googleapis.com/cdn-mxpnl-com/static/readme/android-sync-gradle.png)

This should download the .aar dependency at which point you'll have access to the OursPrivacy library API calls. If it cannot find the dependency, you should make sure you've specified `mavenCentral()` as a repository in your `build.gradle`.

**Step 2 - Add permissions to your AndroidManifest.xml:**
In order for the library to work you'll need to ensure that you're requesting the following permissions in your AndroidManifest.xml:

```java
<!--
This permission is required to allow the application to send
events and properties to OursPrivacy.
-->
<uses-permission
  android:name="android.permission.INTERNET" />

<!--
  This permission is optional but recommended so we can be smart
  about when to send data.
 -->
<uses-permission
  android:name="android.permission.ACCESS_NETWORK_STATE" />

<!--
  This permission is optional but recommended so events will
  contain information about bluetooth state
-->
<uses-permission
  android:name="android.permission.BLUETOOTH" />
```
At this point, you're ready to use the OursPrivacy Android library inside Android Studio.

## 2. Initialize OursPrivacy
Once you've set up your build system or IDE to use the OursPrivacy library, you can initialize it in your code by calling OursPrivacyAPI.getInstance with your application context, your OursPrivacy project token and automatic events setting.

```java
import com.oursprivacy.android.opmetrics.OursPrivacyAPI;


public class MainActivity extends ActionBarActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        trackAutomaticEvents = false;
        OursPrivacyAPI oursprivacy = OursPrivacyAPI.getInstance(this, "YOUR_TOKEN", trackAutomaticEvents);
    }
}
```
[See all configuration options](http://oursprivacy.github.io/oursprivacy-android/index.html)

## 3. Send Data
Let's get started by sending event data. You can send an event from anywhere in your application. Better understand user behavior by storing details that are specific to the event (properties). 

```java
JSONObject props = new JSONObject();
props.put("source", "Pat's affiliate site");
props.put("Opted out of email", true);

oursprivacy.track("Sign Up", props);
```

## 4. Check for Success

Once data hits our API, it generally takes ~60 seconds for it to be processed, stored, and queryable in your project.


# FAQ
**I want to stop tracking an event/event property in OursPrivacy. Is that possible?**

Yes, in Lexicon, you can intercept and drop incoming events or properties. OursPrivacy won’t store any new data for the event or property you select to drop. 

**I have a test user I would like to opt out of tracking. How do I do that?**

OursPrivacy’s client-side tracking library contains the optOutTracking() method, which will set the user’s local opt-out state to “true” and will prevent data from being sent from a user’s device. More detailed instructions can be found in the section.

**Why aren't my events showing up?**

First make sure your test device has internet access. To preserve battery life and customer bandwidth, the OursPrivacy library doesn't send the events you record immediately. Instead, it sends batches to the OursPrivacy servers every 60 seconds while your application is running, as well as when the application transitions to the background. You can call flush() manually if you want to force a flush at a particular moment for example before your application is completely shutdown.

If your events are still not showing up after 60 seconds, check if you have opted out of tracking. You can also enable OursPrivacy debugging and logging, it allows you to see the debug output from the OursPrivacy Android library. To enable it, you will want to add the following permission within your AndroidManifest.xml inside the `<application>` tag:

```java
...
<application>
    <meta-data
      android:name="com.oursprivacy.android.MPConfig.EnableDebugLogging"
      android:value="true" />
    ...
</application>
...
```

<a name="i-want-to-know-more"></a>

<a name="license"></a>
# License

```
See LICENSE File for details. The Base64Coder,
ConfigurationChecker, and StackBlurManager classes, and the entirety of the
 com.oursprivacy.android.java_websocket package used by this
software have been licensed from non-OursPrivacy sources and modified
for use in the library. Please see the relevant source files, and the
LICENSE file in the com.oursprivacy.android.java_websocket package for details.

The StackBlurManager class uses an algorithm by Mario Klingemann <mario@quansimondo.com>
You can learn more about the algorithm at
http://www.quasimondo.com/StackBlurForCanvas/StackBlurDemo.html.
```

## Contributing

If you'd like to contribute to this SDK, please see our [publishing documentation](PUBLISHING.md) for development and publishing guidelines.

## Support

For help with this SDK, please:

- Check the [documentation](https://docs.oursprivacy.com)
- Open an issue on [GitHub](https://github.com/with-ours/ours-privacy-android/issues)
- Contact us at support@oursprivacy.com