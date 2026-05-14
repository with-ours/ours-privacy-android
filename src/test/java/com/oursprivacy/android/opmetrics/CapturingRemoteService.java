package com.oursprivacy.android.opmetrics;

import android.content.Context;

import com.oursprivacy.android.util.OfflineMode;
import com.oursprivacy.android.util.ProxyServerInteractor;
import com.oursprivacy.android.util.RemoteService;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.SSLSocketFactory;

/**
 * Test fake that records every {@code performRequest} body the SDK posts.
 * Treats every request as a 200 OK with an empty response body.
 */
final class CapturingRemoteService implements RemoteService {

    private final List<String> mEndpoints = Collections.synchronizedList(new ArrayList<>());
    private final List<String> mBodies = Collections.synchronizedList(new ArrayList<>());

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
                                 SSLSocketFactory socketFactory) {
        mEndpoints.add(endpointUrl);
        mBodies.add(body);
        return new byte[0];
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
    }
}
