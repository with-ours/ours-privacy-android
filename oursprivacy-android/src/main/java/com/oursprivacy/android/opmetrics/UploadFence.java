package com.oursprivacy.android.opmetrics;

import com.oursprivacy.android.util.RemoteService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

final class UploadFence {
    private final PersistentIdentity identity;
    private final List<Lease> active = new ArrayList<>();
    private boolean open;

    UploadFence(PersistentIdentity identity) {
        this.identity = identity;
        open = !identity.getOptOut();
    }

    synchronized Lease begin(String generation) {
        if (!open || !identity.canUpload(generation)) return null;
        Lease lease = new Lease();
        active.add(lease);
        return lease;
    }

    synchronized List<Lease> revoke() {
        open = false;
        return new ArrayList<>(active);
    }

    synchronized void open() {
        open = true;
    }

    final class Lease {
        private final RemoteService.RequestCancellation cancellation =
                new RemoteService.RequestCancellation();
        private final CountDownLatch completed = new CountDownLatch(1);

        RemoteService.RequestCancellation cancellation() {
            return cancellation;
        }

        void finishTransport() {
            synchronized (UploadFence.this) {
                active.remove(this);
            }
            completed.countDown();
        }

        void cancel() {
            cancellation.cancel();
        }

        void awaitCompletion() {
            boolean interrupted = false;
            while (true) {
                try {
                    completed.await();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
