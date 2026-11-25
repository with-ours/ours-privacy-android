package com.oursprivacy.android.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * An ad blocker that sinks cdn.oursprivacy.com must not stop uploads to a custom serverURL:
 * a first-party proxy exists to get past that block.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class HttpServiceBlockedHostTest {
    @After
    public void clearBlockedFlag() throws Exception {
        setDefaultHostBlocked(false);
    }

    @Test
    public void customServerUrlStillUploadsWhenDefaultHostIsBlocked() throws Exception {
        setDefaultHostBlocked(true);
        Context context = ApplicationProvider.getApplicationContext();
        assertTrue("a blocked default host must not mark the device offline",
                new HttpService().isOnline(context, null));
        assertUploadsToLocalProxy("/ingest");
    }

    @Test
    public void customServerUrlWithUpstreamUrlInPathStillUploads() throws Exception {
        setDefaultHostBlocked(true);
        assertUploadsToLocalProxy("/https://cdn.oursprivacy.com/ingest");
    }

    @Test
    public void customServerUrlWithUpstreamUrlInQueryStillUploads() throws Exception {
        setDefaultHostBlocked(true);
        assertUploadsToLocalProxy("/ingest?upstream=https://cdn.oursprivacy.com");
    }

    @Test
    public void blockedDefaultHostIsNotAttempted() throws Exception {
        setDefaultHostBlocked(true);
        assertNull(new HttpService().performRequest(OPConstants.URL.OURSPRIVACY_API + "/v1/ingest",
                null, null, "{}", null));
    }

    @Test
    public void blockedDefaultHostOverHttpIsNotAttempted() throws Exception {
        setDefaultHostBlocked(true);
        assertNull(new HttpService().performRequest("http://CDN.oursprivacy.com/v1/ingest",
                null, null, "{}", null));
    }

    /** Serves one request on a local port and asserts the upload reaches it and returns its body. */
    private static void assertUploadsToLocalProxy(String pathAndQuery) throws Exception {
        ExecutorService threads = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0)) {
            Future<?> server = threads.submit(() -> {
                try (Socket socket = listener.accept()) {
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    String line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) {}
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 16\r\n"
                            + "Connection: close\r\n\r\n{\"success\":true}").getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            byte[] response = new HttpService().performRequest("http://127.0.0.1:" + listener.getLocalPort()
                    + pathAndQuery, null, null, "{}", null);
            assertEquals("{\"success\":true}", new String(response, StandardCharsets.UTF_8));
            server.get(5, TimeUnit.SECONDS);
        } finally { threads.shutdownNow(); }
    }

    private static void setDefaultHostBlocked(boolean blocked) throws Exception {
        Field field = HttpService.class.getDeclaredField("sIsOursPrivacyBlocked");
        field.setAccessible(true);
        field.setBoolean(null, blocked);
    }
}
