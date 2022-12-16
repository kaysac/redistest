package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public void saveData(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }
    public void savaDataExpire() {

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
            saveData(key, "", expireTime, unit);
            return null;
        }
        saveData(key, r, expireTime, unit);
        return r;
    }


}
