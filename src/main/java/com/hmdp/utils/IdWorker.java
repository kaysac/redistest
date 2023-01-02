package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * @version 1.0
 * @description: TODO 生成优惠券的订单号
 * @author: 陈家豪
 * @date 2023/1/2 13:49
 */

@Component
public class IdWorker {

    private static final long BEGIN_TIMESTAMP = 1580515200L;//开始时间2020.1.1 00:00:00,离1970的秒数 单位秒

    private static final int COUNT = 32;

    private StringRedisTemplate stringRedisTemplate;

    public IdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String prefix){
        //获取当前时间戳
        LocalDateTime now = LocalDateTime.now();
        long timeNow = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = timeNow - BEGIN_TIMESTAMP;

        //获取redis的自增数据
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long increment = stringRedisTemplate.opsForValue().increment("icr:" + prefix + ":" + date);

        //合成订单id
        return (timeStamp<<COUNT)| increment;
    }



}
