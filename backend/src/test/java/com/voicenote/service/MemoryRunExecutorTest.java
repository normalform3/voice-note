package com.voicenote.service;

import com.voicenote.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class MemoryRunExecutorTest {
    @Test
    void runsImmediateMemoryWorkOnASeparateExecutorThread() throws Exception {
        AppProperties properties = enabledProperties();
        MemoryWorker worker = mock(MemoryWorker.class);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Thread> workerThread = new AtomicReference<>();
        doAnswer(invocation -> {
            workerThread.set(Thread.currentThread());
            completed.countDown();
            return null;
        }).when(worker).processExtraction("turn-id");

        try {
            new MemoryRunExecutor(properties, worker, executor).processExtraction("turn-id");

            assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(workerThread.get()).isNotSameAs(Thread.currentThread());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void leavesQueuedWorkForTheScheduledWorkerWhenMemoryIsDisabled() {
        AppProperties properties = new AppProperties();
        properties.getWorkers().setEnabled(true);
        MemoryWorker worker = mock(MemoryWorker.class);

        new MemoryRunExecutor(properties, worker, Runnable::run).processExtraction("turn-id");

        verifyNoInteractions(worker);
    }

    private static AppProperties enabledProperties() {
        AppProperties properties = new AppProperties();
        properties.getWorkers().setEnabled(true);
        properties.getMemory().setEnabled(true);
        return properties;
    }
}
