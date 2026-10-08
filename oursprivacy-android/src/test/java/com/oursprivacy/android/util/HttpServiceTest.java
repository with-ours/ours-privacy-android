package com.oursprivacy.android.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class HttpServiceTest {
    @Test
    public void responseBodyCanPauseLongerThanCancellationPoll() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try (ServerSocket listener = new ServerSocket(0)) {
            Future<?> server = threads.submit(() -> {
                try (Socket socket = listener.accept()) {
                    BufferedReader input = new BufferedReader(
                            new InputStreamReader(socket.getInputStream()));
                    String line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) {}
                    socket.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\na".getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                    Thread.sleep(2_000);
                    socket.getOutputStream().write('b');
                    socket.getOutputStream().flush();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            Future<byte[]> request = threads.submit(() -> new HttpService().performRequest(
                    "http://127.0.0.1:" + listener.getLocalPort() + "/ingest",
                    null, null, "{}", null, new RemoteService.RequestCancellation()));
            assertArrayEquals("ab".getBytes(StandardCharsets.UTF_8), request.get(5, TimeUnit.SECONDS));
            server.get(5, TimeUnit.SECONDS);
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    public void cancellationClosesAnActiveResponseWithoutWaitingForReadTimeout() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try (ServerSocket listener = new ServerSocket(0)) {
            Future<?> server = threads.submit(() -> {
                try (Socket socket = listener.accept()) {
                    BufferedReader input = new BufferedReader(
                            new InputStreamReader(socket.getInputStream()));
                    String line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) {}
                    socket.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\n".getBytes());
                    socket.getOutputStream().flush();
                    requestReceived.countDown();
                    releaseServer.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            RemoteService.RequestCancellation cancellation =
                    new RemoteService.RequestCancellation();
            Future<?> request = threads.submit(() -> {
                try {
                    new HttpService().performRequest(
                            "http://127.0.0.1:" + listener.getLocalPort() + "/ingest",
                            null, null, "{}", null, cancellation);
                    throw new AssertionError("request unexpectedly completed");
                } catch (IOException expected) {
                    if (!cancellation.isCancelled()) throw new AssertionError(expected);
                } catch (RemoteService.ServiceUnavailableException e) {
                    throw new AssertionError(e);
                }
            });
            assertTrue(requestReceived.await(3, TimeUnit.SECONDS));
            long started = System.nanoTime();
            cancellation.cancel();
            request.get(3, TimeUnit.SECONDS);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            System.out.println("Task11 HTTP cancellation latency: " + elapsedMs + " ms");
            assertTrue("cancellation took " + elapsedMs + " ms", elapsedMs < 2_000);
            releaseServer.countDown();
            server.get(3, TimeUnit.SECONDS);
        } finally {
            releaseServer.countDown();
            threads.shutdownNow();
        }
    }
}
