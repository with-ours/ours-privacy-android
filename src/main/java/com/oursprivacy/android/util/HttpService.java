package com.oursprivacy.android.util;

import static com.oursprivacy.android.util.OPConstants.URL.OURSPRIVACY_API;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * An HTTP utility class for internal use in the OursPrivacy library. Not thread-safe.
 */
public class HttpService implements RemoteService {


    private final boolean shouldGzipRequestPayload;

    private static boolean sIsOursPrivacyBlocked;
    private static final int MIN_UNAVAILABLE_HTTP_RESPONSE_CODE = HttpURLConnection.HTTP_INTERNAL_ERROR;
    private static final int MAX_UNAVAILABLE_HTTP_RESPONSE_CODE = 599;

    public HttpService(boolean shouldGzipRequestPayload) {
        this.shouldGzipRequestPayload = shouldGzipRequestPayload;
    }

    public HttpService() {
        this(false);
    }
    @Override
    public void checkIsOursPrivacyBlocked() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    InetAddress apiOursPrivacyInet = InetAddress.getByName("api.oursprivacy.com");
                    sIsOursPrivacyBlocked = apiOursPrivacyInet.isLoopbackAddress() ||
                            apiOursPrivacyInet.isAnyLocalAddress();
                    if (sIsOursPrivacyBlocked) {
                        OPLog.v(LOGTAG, "AdBlocker is enabled. Won't be able to use OursPrivacy services.");
                    }
                } catch (Exception e) {
                }
            }
        });

        t.start();
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("MissingPermission")
    @Override
    public boolean isOnline(Context context, OfflineMode offlineMode) {
        if (sIsOursPrivacyBlocked) return false;
        if (onOfflineMode(offlineMode)) return false;

        boolean isOnline;
        try {
            final ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            final NetworkInfo netInfo = cm.getActiveNetworkInfo();
            if (netInfo == null) {
                isOnline = true;
                OPLog.v(LOGTAG, "A default network has not been set so we cannot be certain whether we are offline");
            } else {
                isOnline = netInfo.isConnectedOrConnecting();
                OPLog.v(LOGTAG, "ConnectivityManager says we " + (isOnline ? "are" : "are not") + " online");
            }
        } catch (final SecurityException e) {
            isOnline = true;
            OPLog.v(LOGTAG, "Don't have permission to check connectivity, will assume we are online");
        }
        return isOnline;
    }

    private boolean onOfflineMode(OfflineMode offlineMode) {
        boolean onOfflineMode;

        try {
            onOfflineMode = offlineMode != null && offlineMode.isOffline();
        } catch (Exception e) {
            onOfflineMode = false;
            OPLog.v(LOGTAG, "Client State should not throw exception, will assume is not on offline mode", e);
        }

        return onOfflineMode;
    }

    @Override
    public byte[] performRequest(String endpointUrl, ProxyServerInteractor interactor, Map<String, Object> params, String body, SSLSocketFactory socketFactory) throws ServiceUnavailableException, IOException {
        OPLog.v(LOGTAG, "Attempting request to " + endpointUrl);

        byte[] response = null;

        // the while(retries) loop is a workaround for a bug in some Android HttpURLConnection
        // libraries- The underlying library will attempt to reuse stale connections,
        // meaning the second (or every other) attempt to connect fails with an EOFException.
        // Apparently this nasty retry logic is the current state of the workaround art.
        int retries = 0;
        boolean succeeded = false;
        while (retries < 3 && !succeeded) {
            InputStream in = null;
            OutputStream out = null;
            OutputStream bout = null;
            HttpURLConnection connection = null;

            try {
                final URL url = new URL(endpointUrl);
                connection = (HttpURLConnection) url.openConnection();
                if (null != socketFactory && connection instanceof HttpsURLConnection) {
                    ((HttpsURLConnection) connection).setSSLSocketFactory(socketFactory);
                }

                if (interactor != null && isProxyRequest(endpointUrl)) {
                    Map<String,String> headers = interactor.getProxyRequestHeaders();
                    if (headers != null) {
                        for (Map.Entry<String, String> entry : headers.entrySet()) {
                            connection.setRequestProperty(entry.getKey(), entry.getValue());
                        }
                    }
                }

                connection.setConnectTimeout(2000);
                connection.setReadTimeout(30000);
                if (null != params) {
                    Uri.Builder builder = new Uri.Builder();
                    for (Map.Entry<String, Object> param : params.entrySet()) {
                        builder.appendQueryParameter(param.getKey(), param.getValue().toString());
                    }
                    String query = builder.build().getEncodedQuery();
                    if (shouldGzipRequestPayload) {
                        connection.setRequestProperty(CONTENT_ENCODING_HEADER, GZIP_CONTENT_TYPE_HEADER);
                    } else {
                        connection.setFixedLengthStreamingMode(body.length());
                    }
                    connection.setDoOutput(true);
                    connection.setRequestMethod("POST");
                    out = connection.getOutputStream();
                    OutputStreamWriter osw = new OutputStreamWriter(out, StandardCharsets.UTF_8);
                    osw.write(body);
                    osw.flush();
                    osw.close();
                    osw = null;
//                    bout = getBufferedOutputStream(out);
//                    bout.write(query.getBytes("UTF-8"));
//                    bout.flush();
//                    bout.close();
//                    bout = null;
                    out.close();
                    out = null;
                }
                if (interactor != null && isProxyRequest(endpointUrl)) {
                    interactor.onProxyResponse(endpointUrl, connection.getResponseCode());
                }
                in = connection.getInputStream();
                response = slurp(in);
                in.close();
                in = null;
                succeeded = true;
            } catch (final EOFException e) {
                OPLog.d(LOGTAG, "Failure to connect, likely caused by a known issue with Android lib. Retrying.");
                retries = retries + 1;
            } catch (final IOException e) {
                if (connection != null && connection.getResponseCode() >= MIN_UNAVAILABLE_HTTP_RESPONSE_CODE && connection.getResponseCode() <= MAX_UNAVAILABLE_HTTP_RESPONSE_CODE) {
                    throw new ServiceUnavailableException("Service Unavailable", connection.getHeaderField("Retry-After"));
                } else {
                    throw e;
                }
            }
            finally {
                if (null != bout)
                    try { bout.close(); } catch (final IOException e) {}
                if (null != out)
                    try { out.close(); } catch (final IOException e) {}
                if (null != in)
                    try { in.close(); } catch (final IOException e) {}
                if (null != connection)
                    connection.disconnect();
            }
        }
        if (retries >= 3) {
            OPLog.v(LOGTAG, "Could not connect to OursPrivacy service after three retries.");
        }
        return response;
    }

    private OutputStream getBufferedOutputStream(OutputStream out) throws IOException {
        if(shouldGzipRequestPayload) {
          return new GZIPOutputStream(new BufferedOutputStream(out), HTTP_OUTPUT_STREAM_BUFFER_SIZE);
        } else {
            return new BufferedOutputStream(out);
        }
    }

    private static boolean isProxyRequest(String endpointUrl) {
        return !endpointUrl.toLowerCase().contains(OURSPRIVACY_API.toLowerCase());
    }

    private static byte[] slurp(final InputStream inputStream)
            throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        int nRead;
        byte[] data = new byte[8192];

        while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }

        buffer.flush();
        return buffer.toByteArray();
    }

    private static final String LOGTAG = "OursPrivacyAPI.Message";
    private static final int HTTP_OUTPUT_STREAM_BUFFER_SIZE = 8192;
    private static final String CONTENT_ENCODING_HEADER = "Content-Encoding";
    private static final String GZIP_CONTENT_TYPE_HEADER = "gzip";
}
