package com.oursprivacy.android.opmetrics;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;

import com.oursprivacy.android.util.HttpService;
import com.oursprivacy.android.util.OPLog;
import com.oursprivacy.android.util.RemoteService;
import com.oursprivacy.android.util.RemoteService.ServiceUnavailableException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Owns the background worker thread that:
 * <ul>
 *   <li>persists track/identify events into the queue,
 *   <li>drains the queue to the ingest endpoint on a timer / queue-size threshold / explicit flush,
 *   <li>retries with exponential backoff on transient failures.
 * </ul>
 * The wire envelope is {@code {token, is_manually_set_id, data:[PayloadItem...]}}.
 */
/* package */ class AnalyticsMessages {

    /**
     * Test-only override for the network poster. When set, replaces the default
     * {@link HttpService}. Tests can install a capturing fake to inspect the
     * canonical envelope without making real network calls.
     */
    /* package */ static volatile RemoteService sTestRemoteService;

    private final Context mContext;
    private final OPConfig mConfig;
    private final String mToken;
    private final PersistentIdentity mPersistence;
    private final Worker mWorker;

    AnalyticsMessages(Context context, OPConfig config, String token, PersistentIdentity persistence) {
        mContext = context.getApplicationContext();
        mConfig = config;
        mToken = token;
        mPersistence = persistence;
        mWorker = new Worker();
        getPoster().checkIsOursPrivacyBlocked();
    }

    void enqueue(JSONObject event) {
        final Message m = Message.obtain();
        m.what = ENQUEUE_EVENT;
        m.obj = event;
        mWorker.runMessage(m);
    }

    void flushNow() {
        final Message m = Message.obtain();
        m.what = FLUSH;
        mWorker.runMessage(m);
    }

    void clearQueue() {
        final Message m = Message.obtain();
        m.what = CLEAR_QUEUE;
        mWorker.runMessage(m);
    }

    void hardKill() {
        final Message m = Message.obtain();
        m.what = KILL_WORKER;
        mWorker.runMessage(m);
    }

    boolean isDead() {
        return mWorker.isDead();
    }

    /** Overridable for tests. */
    protected RemoteService getPoster() {
        final RemoteService override = sTestRemoteService;
        if (override != null) return override;
        return new HttpService(mConfig.shouldGzipRequestPayload());
    }

    /**
     * Test-only: blocks the caller until the worker has processed every message
     * queued before this call. Returns {@code true} on success, {@code false} on timeout.
     */
    /* package */ boolean awaitWorkerIdle(long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        final Message m = Message.obtain();
        m.what = SENTINEL;
        m.obj = latch;
        mWorker.runMessage(m);
        try {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Test-only: parks the worker thread inside a single message handler so the
     * caller can deterministically queue follow-on messages before the worker
     * starts draining them. {@code parked} fires once the worker is blocked;
     * countDown {@code release} to let it resume.
     */
    /* package */ void parkWorker(CountDownLatch parked, CountDownLatch release) {
        final Message m = Message.obtain();
        m.what = BARRIER;
        m.obj = new CountDownLatch[]{parked, release};
        mWorker.runMessage(m);
    }

    // ---------- worker ----------

    private final class Worker {
        private final Object mHandlerLock = new Object();
        private Handler mHandler;
        private final long mFlushInterval;

        Worker() {
            final HandlerThread thread = new HandlerThread(
                    "com.oursprivacy.android.AnalyticsWorker", Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            mHandler = new AnalyticsMessageHandler(thread.getLooper());
            mFlushInterval = mConfig.getFlushInterval();
            scheduleFlush(mFlushInterval);
        }

        boolean isDead() {
            synchronized (mHandlerLock) { return mHandler == null; }
        }

        void runMessage(Message msg) {
            synchronized (mHandlerLock) {
                if (mHandler == null) {
                    OPLog.i(LOGTAG, "Worker is dead; dropping message " + msg);
                } else {
                    mHandler.sendMessage(msg);
                }
            }
        }

        private void scheduleFlush(long delayMs) {
            // FLUSH_PERIODIC is the recurring timer-driven flush. We coalesce
            // only against ourselves — never against user-initiated FLUSH
            // messages, which must survive to fire sendBatch().
            synchronized (mHandlerLock) {
                if (mHandler == null) return;
                mHandler.removeMessages(FLUSH_PERIODIC);
                final Message m = Message.obtain();
                m.what = FLUSH_PERIODIC;
                mHandler.sendMessageDelayed(m, delayMs);
            }
        }

        private final class AnalyticsMessageHandler extends Handler {
            AnalyticsMessageHandler(android.os.Looper looper) { super(looper); }

            @Override
            public void handleMessage(Message msg) {
                try {
                    switch (msg.what) {
                        case ENQUEUE_EVENT: {
                            final JSONObject event = (JSONObject) msg.obj;
                            mPersistence.enqueueEvent(event);
                            if (mPersistence.getQueueSize() >= mConfig.getBulkUploadLimit()) {
                                sendBatch();
                            }
                            break;
                        }
                        case FLUSH:
                        case FLUSH_PERIODIC: {
                            sendBatch();
                            scheduleFlush(mFlushInterval);
                            break;
                        }
                        case CLEAR_QUEUE: {
                            mPersistence.clearQueue();
                            break;
                        }
                        case KILL_WORKER: {
                            synchronized (mHandlerLock) {
                                getLooper().quit();
                                mHandler = null;
                            }
                            break;
                        }
                        case SENTINEL: {
                            final CountDownLatch latch = (CountDownLatch) msg.obj;
                            if (latch != null) latch.countDown();
                            break;
                        }
                        case BARRIER: {
                            final CountDownLatch[] gates = (CountDownLatch[]) msg.obj;
                            gates[0].countDown();
                            try { gates[1].await(); } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                            }
                            break;
                        }
                        default:
                            OPLog.e(LOGTAG, "Unknown worker message: " + msg.what);
                    }
                } catch (final RuntimeException e) {
                    OPLog.e(LOGTAG, "Worker crashed processing message", e);
                }
            }
        }

        private void sendBatch() {
            final RemoteService poster = getPoster();
            if (!poster.isOnline(mContext, mConfig.getOfflineMode())) {
                return;
            }

            final int batchSize = mConfig.getFlushBatchSize();
            while (mPersistence.getQueueSize() > 0) {
                final JSONArray snapshot = mPersistence.getQueueSnapshot();
                final int take = Math.min(batchSize, snapshot.length());
                final JSONArray batch = new JSONArray();
                for (int i = 0; i < take; i++) {
                    batch.put(snapshot.opt(i));
                }

                final String body;
                try {
                    body = buildEnvelope(batch).toString();
                } catch (JSONException e) {
                    OPLog.e(LOGTAG, "Failed to build envelope; dropping batch", e);
                    mPersistence.dropFromQueue(take);
                    continue;
                }

                try {
                    final byte[] response = poster.performRequest(
                            mConfig.getEventsEndpoint(),
                            mConfig.getProxyServerInteractor(),
                            null,
                            body,
                            mConfig.getSSLSocketFactory());
                    if (response == null) {
                        OPLog.v(LOGTAG, "No response from ingest endpoint; will retry on next flush");
                        return;
                    }
                    mPersistence.dropFromQueue(take);
                } catch (final ServiceUnavailableException e) {
                    OPLog.v(LOGTAG, "Service unavailable; backing off " + e.getRetryAfter() + "s");
                    final long retryMs = Math.max(1000L, e.getRetryAfter() * 1000L);
                    scheduleFlush(retryMs);
                    return;
                } catch (final IOException e) {
                    OPLog.i(LOGTAG, "Flush failed; will retry on next flush", e);
                    return;
                }
            }
        }

        private JSONObject buildEnvelope(JSONArray batch) throws JSONException {
            final JSONObject envelope = new JSONObject();
            envelope.put("token", mToken);
            envelope.put("is_manually_set_id", mPersistence.isManuallySetId());
            envelope.put("data", batch);
            return envelope;
        }
    }

    private static final int ENQUEUE_EVENT = 0;
    private static final int FLUSH = 1;
    private static final int CLEAR_QUEUE = 2;
    private static final int KILL_WORKER = 3;
    private static final int SENTINEL = 4;
    private static final int FLUSH_PERIODIC = 5;
    private static final int BARRIER = 6;

    private static final String LOGTAG = "OursPrivacy.Analytics";
}
