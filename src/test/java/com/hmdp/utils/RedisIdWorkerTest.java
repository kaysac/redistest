package com.hmdp.utils;

import com.sun.scenario.effect.impl.sw.sse.SSEBlend_SRC_OUTPeer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RedisIdWorkerTest {

    @Resource
    private RedisIdWorker redisIdWorker;

    @Test
    void testId(){
        long orderId = redisIdWorker.nextId("orderId");
        System.out.println(orderId);
    }

}