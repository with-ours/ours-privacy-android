package com.oursprivacy.android.opmetrics;

import android.content.Context;

import com.oursprivacy.android.util.OfflineMode;
import com.oursprivacy.android.util.ProxyServerInteractor;
import com.oursprivacy.android.util.RemoteService;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocketFactory;

/**
 * Test fake that records every {@code performRequest} body the SDK posts.
 * Treats every request as a complete legacy success unless a response is queued.
 */
final class CapturingRemoteService implements RemoteService {

    private final List<String> mEndpoints = Collections.synchronizedList(new ArrayList<>());
    private final List<String> mBodies = Collections.synchronizedList(new ArrayList<>());
    private final ArrayDeque<Object> mResponses = new ArrayDeque<>();
    private CountDownLatch mRequestCaptured;
    private CountDownLatch mReleaseRequest;

    @Override
    public boolean isOnline(Context context, OfflineMode offlineMode) {
        return true;
    }

    @Override
    public void checkIsOursPrivacyBlocked() {}

    @Override
    public byte[] performRequest(String endpointUrl,
                                 ProxyServerInteractor interactor,
                                 java.util.Map<String, Object> params,
                                 String body,
                                 SSLSocketFactory socketFactory) throws IOException {
        mEndpoints.add(endpointUrl);
        mBodies.add(body);
        Object next;
        synchronized (mResponses) {
            next = mResponses.pollFirst();
        }
        CountDownLatch captured;
        CountDownLatch release;
        synchronized (this) {
            captured = mRequestCaptured;
            release = mReleaseRequest;
            mRequestCaptured = null;
            mReleaseRequest = null;
        }
        if (captured != null) {
            captured.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting to release captured request");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while holding captured request", e);
            }
        }
        if (next instanceof IOException) throw (IOException) next;
        return ((String) (next == null
                ? "{\"success\":true,\"visitor_id\":\"test-visitor\"}" : next))
                .getBytes(StandardCharsets.UTF_8);
    }

    void respondWith(String response) {
        synchronized (mResponses) { mResponses.addLast(response); }
    }

    void failWith(IOException failure) {
        synchronized (mResponses) { mResponses.addLast(failure); }
    }

    synchronized void blockNextRequest(CountDownLatch captured, CountDownLatch release) {
        mRequestCaptured = captured;
        mReleaseRequest = release;
    }

    /** Snapshot of every captured body, in POST order. */
    List<String> bodies() {
        synchronized (mBodies) {
            return new ArrayList<>(mBodies);
        }
    }

    /** Snapshot of every captured endpoint URL, in POST order. */
    List<String> endpoints() {
        synchronized (mEndpoints) {
            return new ArrayList<>(mEndpoints);
        }
    }

    JSONObject bodyAt(int index) throws JSONException {
        return new JSONObject(bodies().get(index));
    }

    int callCount() {
        return mBodies.size();
    }

    void reset() {
        mBodies.clear();
        mEndpoints.clear();
        synchronized (mResponses) { mResponses.clear(); }
    }
}
