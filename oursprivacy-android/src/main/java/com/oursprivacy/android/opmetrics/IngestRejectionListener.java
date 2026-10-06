package com.oursprivacy.android.opmetrics;

/** Receives a rejected event's ID and stable ingest code after the batch is acknowledged. */
@FunctionalInterface
public interface IngestRejectionListener {
    void onRejected(String distinctId, String code);
}
