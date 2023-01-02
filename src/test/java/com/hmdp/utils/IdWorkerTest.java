package com.hmdp.utils;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class IdWorkerTest {

    @Resource
    private IdWorker idWorker;

    private ExecutorService executor = Executors.newFixedThreadPool(500);

    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(100);

        Runnable task = new Runnable(){
            @Override
            public void run() {
                for (int i = 0; i < 300; i++) {
                    long orderId = idWorker.nextId("orderId");
                    System.out.println(orderId);
                }
                countDownLatch.countDown();
            }
        };

        long start = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            executor.submit(task);
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("共耗时："+(end-start)/1000+"秒");


    }

}