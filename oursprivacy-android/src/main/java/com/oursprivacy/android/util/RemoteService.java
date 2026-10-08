package com.oursprivacy.android.util;

import android.content.Context;


import java.io.IOException;
import java.util.Map;

import javax.net.ssl.SSLSocketFactory;


public interface RemoteService {
    boolean isOnline(Context context, OfflineMode offlineMode);

    void checkIsOursPrivacyBlocked();

    byte[] performRequest(String endpointUrl, ProxyServerInteractor interactor, Map<String, Object> params, String body, SSLSocketFactory socketFactory)
            throws ServiceUnavailableException, IOException;

    default byte[] performRequest(String endpointUrl, ProxyServerInteractor interactor,
                                  Map<String, Object> params, String body,
                                  SSLSocketFactory socketFactory,
                                  RequestCancellation cancellation)
            throws ServiceUnavailableException, IOException {
        if (cancellation.isCancelled()) throw new IOException("Request cancelled");
        byte[] response = performRequest(endpointUrl, interactor, params, body, socketFactory);
        if (cancellation.isCancelled()) throw new IOException("Request cancelled");
        return response;
    }

    final class RequestCancellation {
        private boolean cancelled;
        private Runnable onCancel;

        public synchronized boolean isCancelled() {
            return cancelled;
        }

        public void setOnCancel(Runnable action) {
            boolean runNow;
            synchronized (this) {
                onCancel = action;
                runNow = cancelled && action != null;
            }
            if (runNow) action.run();
        }

        public void cancel() {
            Runnable action;
            synchronized (this) {
                if (cancelled) return;
                cancelled = true;
                action = onCancel;
            }
            if (action != null) action.run();
        }
    }

    class ServiceUnavailableException extends Exception {
        public ServiceUnavailableException(String message, String strRetryAfter) {
            super(message);
            int retry;
            try {
                retry = Integer.parseInt(strRetryAfter);
            } catch (NumberFormatException e) {
                retry = 0;
            }
            mRetryAfter = retry;
        }

        public int getRetryAfter() {
            return mRetryAfter;
        }

        private final int mRetryAfter;
    }
}
