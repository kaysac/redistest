package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

@Component
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public void saveData(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }
    public void saveDataExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisData.setData(value);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData), time, unit);
    }

    // 通过锁解决缓存击穿
     public <R, ID> R queryWithMutex(
             String keyPrefix , ID id, Class<R> type, Function<ID, R> function, Long expireTime, TimeUnit unit) {
        String key = keyPrefix + id;
        String Json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(Json)) {
            R r = JSONUtil.toBean(Json, type);
            return r;
        }

        if (Json != null) {
            return null;
        }

         R apply;
        String lockKey = LOCK_SHOP_KEY + id;
        try {
            if(!trylock(lockKey)) {
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, function, expireTime, unit);
            }

            apply = function.apply(id);
            if (apply == null) {
                saveData(key, "", expireTime, unit);
                return null;
            }
            saveData(key, apply, expireTime, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);
        }

        return apply;
    }

    // 逻辑过期解决缓存击穿
    public <R, ID> R queryWithLogicExpire(
            String keyPrefix , ID id, Class<R> type, Function<ID, R> function, Long expireTime, TimeUnit unit) {
        String key = keyPrefix + id;
        String Json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(Json)) {
            return null;
        }
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
        LocalDateTime time = redisData.getTime();
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);

        if (time.isAfter(LocalDateTime.now())) {
            return r;
        }
        String lockKey = LOCK_SHOP_KEY + id;
        if (trylock(lockKey)) {
            CACHE_REBUILD_EXECUTOR.submit(()->{
                R apply = function.apply(id);
                saveDataExpire(key, apply, expireTime, unit);
                unlock(lockKey);
            });

        }
        return r;
    }

    // 缓存穿透
    public <R, ID> R queryWithPassThrough(
            String keyPrefix , ID id, Class<R> type, Function<ID, R> function, Long expireTime, TimeUnit unit) {
        String key = keyPrefix + id;
        String Json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(Json)) {
            R r = JSONUtil.toBean(Json, type);
            return r;
        }
        if (Json != null) {
            return null;
        }

        R r = function.apply(id);
        if (r == null) {
            saveData(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        saveData(key, r, expireTime, unit);
        return r;
    }

    private boolean trylock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1");
        stringRedisTemplate.expire(key, LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return flag;
    }
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }


}
