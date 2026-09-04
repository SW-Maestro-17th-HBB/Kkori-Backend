package com.aisw.kkori;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시성 통합 테스트 공용 하네스 — 2중 배리어(ready + start) 동시 실행.
 *
 * <p>두 워커가 모두 배리어에 도달한 것을 확인한 뒤 해제한다 — 스케줄링에 따라 한쪽이
 * 전부 끝난 뒤 다른 쪽이 시작하는 무경합 실행으로 통과하는 false-pass를 방지한다.
 * 이 보장이 각 테스트에 사본으로 흩어지면 한 곳의 수정이 전파되지 않으므로 여기서만 정의한다.
 */
public final class ConcurrencyTestSupport {

    private ConcurrencyTestSupport() {
    }

    public static void runConcurrently(Runnable first, Runnable second) throws Exception {
        runConcurrently(first, second, 10);
    }

    public static void runConcurrently(Runnable first, Runnable second, long timeoutSeconds) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = List.of(
                    pool.submit(() -> awaitAndRun(ready, start, first)),
                    pool.submit(() -> awaitAndRun(ready, start, second)));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(timeoutSeconds, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static Object awaitAndRun(CountDownLatch ready, CountDownLatch start, Runnable task) {
        ready.countDown();
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        task.run();
        return null;
    }
}
