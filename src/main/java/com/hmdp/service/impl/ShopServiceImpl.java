package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {

        /*String key = CACHE_SHOP_KEY + id;
        //从Redis中查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //查到商铺信息
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }

        //Redis中没有商铺信息，去数据库中查找
        Shop shop = getById(id);
        if (shop == null) {
            //数据库中也没有
            return Result.fail("商铺信息不存在");
        }

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);
*/

//        Shop shop = queryWithPassThrough(id);
//        Shop shop = queryWithMutex(id);
        Shop shop = queryWithExpioreTime(id);
        if (shop == null) {
            return Result.fail("商铺信息不存在");
        }
        return Result.ok(shop);

    }


    /*
     * 解决缓存穿透的问题
     * Redis中存放空值
     *
     * */
    public Shop queryWithPassThrough(Long id) {

        String key = CACHE_SHOP_KEY + id;
        //从Redis中查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //查到商铺信息
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        if (shopJson != null) {
            return null;
        }

        //Redis中没有商铺信息，去数据库中查找
        Shop shop = getById(id);
        if (shop == null) {
            //数据库中也没有
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;


    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /*
     * 设置过期时间解决缓存穿透问题
     * */
    public Shop queryWithExpioreTime(long id) {
        String key = CACHE_SHOP_KEY + id;
        String dataJson = stringRedisTemplate.opsForValue().get(key);

        //未命中
        if (StrUtil.isBlank(dataJson)) {
            return null;
        }

        //命中
        RedisData redisData = JSONUtil.toBean(dataJson, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        JSONObject data = (JSONObject)redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);

        if (expireTime.isAfter(LocalDateTime.now())) {
            //数据未过期
            return shop;
        }

        //数据过期
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            //获得锁，开启一个独立的线程去修改Redis
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShop2Redis(id, 20L);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    unLock(lockKey);
                }

            });
        }

        return shop;

    }

    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        //从数据库中获取对应店铺的信息
        Shop shop = getById(id);
        Thread.sleep(200);
        //将shop封装成RedisData实例
        RedisData data = new RedisData();
        data.setData(shop);
        data.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //保存在Redis中
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(data));
    }

    public Shop queryWithMutex(Long id) {

        String key = CACHE_SHOP_KEY + id;
        //从Redis中查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //查到商铺信息,不用争锁
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        //缓存穿透的问题
        if (shopJson != null) {
            return null;
        }

        //商铺信息不存在,需要争锁
        String lock_key = LOCK_SHOP_KEY + id;

        boolean isLock = tryLock(lock_key);
        Shop shop = null;
        try {
            if (!isLock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }


            //Redis中没有商铺信息，去数据库中查找
            shop = getById(id);
            Thread.sleep(200);
            if (shop == null) {
                //数据库中也没有
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unLock(lock_key);
        }


        return shop;


    }

    public boolean tryLock(String key) {
        Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return isLock;
    }

    public void unLock(String key) {
        stringRedisTemplate.delete(key);
    }


    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }

        boolean flag = updateById(shop);

        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok("信息更新成功");


    }
}
