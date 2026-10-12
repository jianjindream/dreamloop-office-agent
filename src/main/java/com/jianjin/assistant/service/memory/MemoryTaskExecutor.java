package com.jianjin.assistant.service.memory;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded executor for memory extraction, compaction and graph synchronization work. */
@Component
public class MemoryTaskExecutor implements Executor {
    private final ThreadPoolExecutor delegate;

    public MemoryTaskExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "memory-task-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        delegate = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256), factory, new ThreadPoolExecutor.CallerRunsPolicy());
        delegate.allowCoreThreadTimeOut(true);
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(command);
    }

    @PreDestroy
    public void shutdown() {
        delegate.shutdown();
        try {
            if (!delegate.awaitTermination(5, TimeUnit.SECONDS)) delegate.shutdownNow();
        } catch (InterruptedException e) {
            delegate.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
