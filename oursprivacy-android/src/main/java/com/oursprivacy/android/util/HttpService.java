package com.oursprivacy.android.util;

import static com.oursprivacy.android.util.OPConstants.URL.OURSPRIVACY_API;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * Posts JSON bodies to the ingest endpoint. Not thread-safe; the SDK serializes
 * all calls through {@code AnalyticsMessages.Worker}.
 */
public final class HttpService implements RemoteService {

    private final boolean mShouldGzip;
    private static boolean sIsOursPrivacyBlocked;

    private static final int MIN_UNAVAILABLE_HTTP_RESPONSE_CODE = HttpURLConnection.HTTP_INTERNAL_ERROR;
    private static final int MAX_UNAVAILABLE_HTTP_RESPONSE_CODE = 599;

    public HttpService(boolean shouldGzipRequestPayload) {
        mShouldGzip = shouldGzipRequestPayload;
    }

    public HttpService() {
        this(false);
    }

    @Override
    public void checkIsOursPrivacyBlocked() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    final InetAddress addr = InetAddress.getByName("cdn.oursprivacy.com");
                    sIsOursPrivacyBlocked = addr.isLoopbackAddress() || addr.isAnyLocalAddress();
                    if (sIsOursPrivacyBlocked) {
                        OPLog.v(LOGTAG, "AdBlocker is enabled. Won't be able to use OursPrivacy services.");
                    }
                } catch (Exception ignored) {}
            }
        }).start();
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("MissingPermission")
    @Override
    public boolean isOnline(Context context, OfflineMode offlineMode) {
        if (sIsOursPrivacyBlocked) return false;
        if (isOfflineMode(offlineMode)) return false;
        try {
            final ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            final NetworkInfo netInfo = cm.getActiveNetworkInfo();
            if (netInfo == null) return true;
            return netInfo.isConnectedOrConnecting();
        } catch (final SecurityException e) {
            // ACCESS_NETWORK_STATE not granted — assume online and let the request fail naturally.
            return true;
        }
    }

    private boolean isOfflineMode(OfflineMode offlineMode) {
        try {
            return offlineMode != null && offlineMode.isOffline();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * POSTs {@code body} as {@code application/json} to {@code endpointUrl}.
     * {@code params} is ignored — the canonical ingest endpoint takes a JSON body, not form params.
     */
    @Override
    public byte[] performRequest(String endpointUrl,
                                 ProxyServerInteractor interactor,
                                 Map<String, Object> params,
                                 String body,
                                 SSLSocketFactory socketFactory)
            throws ServiceUnavailableException, IOException {
        OPLog.v(LOGTAG, "Attempting request to " + endpointUrl);
        if (body == null) {
            throw new IOException("HttpService.performRequest called with null body");
        }

        byte[] response = null;
        int retries = 0;
        boolean succeeded = false;

        // Workaround for a known HttpURLConnection bug where stale connections cause spurious EOFExceptions.
        while (retries < 3 && !succeeded) {
            InputStream in = null;
            OutputStream out = null;
            HttpURLConnection connection = null;
            try {
                final URL url = new URL(endpointUrl);
                connection = (HttpURLConnection) url.openConnection();
                if (socketFactory != null && connection instanceof HttpsURLConnection) {
                    ((HttpsURLConnection) connection).setSSLSocketFactory(socketFactory);
                }

                if (interactor != null && isProxyRequest(endpointUrl)) {
                    final Map<String, String> headers = interactor.getProxyRequestHeaders();
                    if (headers != null) {
                        for (Map.Entry<String, String> e : headers.entrySet()) {
                            connection.setRequestProperty(e.getKey(), e.getValue());
                        }
                    }
                }

                connection.setConnectTimeout(2000);
                connection.setReadTimeout(30000);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("Accept", "application/json");

                final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                out = connection.getOutputStream();
                if (mShouldGzip) {
                    connection.setRequestProperty("Content-Encoding", "gzip");
                    final GZIPOutputStream gz = new GZIPOutputStream(new BufferedOutputStream(out));
                    gz.write(payload);
                    gz.flush();
                    gz.close();
                } else {
                    out.write(payload);
                    out.flush();
                }
                out.close();
                out = null;

                if (interactor != null && isProxyRequest(endpointUrl)) {
                    interactor.onProxyResponse(endpointUrl, connection.getResponseCode());
                }

                in = connection.getInputStream();
                response = slurp(in);
                in.close();
                in = null;
                succeeded = true;
            } catch (final EOFException e) {
                OPLog.d(LOGTAG, "Stale connection; retrying.");
                retries++;
            } catch (final IOException e) {
                if (connection != null
                        && connection.getResponseCode() >= MIN_UNAVAILABLE_HTTP_RESPONSE_CODE
                        && connection.getResponseCode() <= MAX_UNAVAILABLE_HTTP_RESPONSE_CODE) {
                    throw new ServiceUnavailableException("Service Unavailable",
                            connection.getHeaderField("Retry-After"));
                }
                throw e;
            } finally {
                if (out != null) try { out.close(); } catch (IOException ignored) {}
                if (in != null) try { in.close(); } catch (IOException ignored) {}
                if (connection != null) connection.disconnect();
            }
        }

        if (retries >= 3) {
            OPLog.v(LOGTAG, "Could not connect to OursPrivacy service after three retries.");
        }
        return response;
    }

    private static boolean isProxyRequest(String endpointUrl) {
        return !endpointUrl.toLowerCase().contains(OURSPRIVACY_API.toLowerCase());
    }

    private static byte[] slurp(InputStream in) throws IOException {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        final byte[] data = new byte[8192];
        int n;
        while ((n = in.read(data, 0, data.length)) != -1) {
            buf.write(data, 0, n);
        }
        buf.flush();
        return buf.toByteArray();
    }

    private static final String LOGTAG = "OursPrivacy.Http";
}
