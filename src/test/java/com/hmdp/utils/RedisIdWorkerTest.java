package com.hmdp.utils;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RedisIdWorkerTest {

    @Resource
    RedisIdWorker redisIdWorker;

    private final ExecutorService service = Executors.newFixedThreadPool(500);

    @Test
    void nextIdWorker() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long l = redisIdWorker.nextId("order");
                System.out.println("id =" + l);
            }
            countDownLatch.countDown();
        };
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            service.submit(task);
        }
        countDownLatch.await();
        long endTime = System.currentTimeMillis();
        long x = endTime - startTime;
        System.out.println("time=" + x);
    }
}