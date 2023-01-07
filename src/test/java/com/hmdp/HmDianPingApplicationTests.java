package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
//import org.springframework.data.redis.domain.geo.GeoLocation;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService es = Executors.newFixedThreadPool(10);

    @Test
    void testSaveShop() throws InterruptedException {
        shopService.saveShop2Redis(1L,10L);
    }

    @Test
    void TestIdWorker() throws InterruptedException {

        CountDownLatch countDownLatch = new CountDownLatch(300);
        Runnable task = ()->{
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id="+id);
            }
            countDownLatch.countDown();
        };


        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();

        System.out.println("time="+(end-begin));
    }

    @Test
    void loadShopTest(){
        //查询店铺信息
        List<Shop> list = shopService.list();
        //根据TypeId分组
        Map<Long,List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //分批存入
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            Long typeId = entry.getKey();
            List<Shop> value = entry.getValue();
            String key = "shop:geo:"+typeId;
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            for (Shop shop : value) {
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())));
            }

//            for (Shop shop : value) {
//                stringRedisTemplate.opsForGeo().add(key,new Point(shop.getX(), shop.getY()),shop.getId().toString());
//            }
            stringRedisTemplate.opsForGeo().add(key,locations);
        }
    }


}
