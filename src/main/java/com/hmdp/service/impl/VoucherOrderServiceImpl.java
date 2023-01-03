package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.IdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService iSeckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private IdWorker idWorker;

    private static final DefaultRedisScript<Long> SECKILL;

    static {
        SECKILL = new DefaultRedisScript<>();
        SECKILL.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL.setResultType(Long.class);
    }

//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //获取消息队列的任务
    private class VoucherOrderHandler implements Runnable{

        String queueName = "stream.order";

        @Override
        public void run() {
            while(true){
                try {
                    //获取消息队列的消息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //失败就继续读取
                    if(list==null || list.isEmpty()){
                        continue;
                    }
                    //成功就解析数据，进行处理,
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while(true){
                try {
                    Thread.sleep(20);
                    //获取消息队列的消息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //失败就说明没有未确认的消息
                    if(list==null || list.isEmpty()){
                        break;
                    }
                    //成功就解析数据，进行处理,
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("消息处理异常");

                }
            }
        }



    }



    /*//获取阻塞队列的任务
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while(true){
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常",e);
                }
            }
        }

    }*/

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();//打断点debug时间比较长，设的时间比较长
        if(!isLock){
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.getVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;


    //基于消息队列的
    @Override
    public Result getOrder(Long voucherId) {
        long orderId = idWorker.nextId("order");
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(SECKILL,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),String.valueOf(orderId));
        int r = result.intValue();
        //不能下单
        if(r != 0){
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }


        proxy = (IVoucherOrderService)AopContext.currentProxy();

        return Result.ok(orderId);


    }


    //基于阻塞队列
    /*@Override
    public Result getOrder(Long voucherId) {

        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(SECKILL,
                Collections.emptyList(),
                voucherId.toString(), userId.toString());
        int r = result.intValue();
        //不能下单
        if(r != 0){
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }
        //可以下单，生成订单ID,并将订单放到阻塞队列里去
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = idWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        orderTasks.add(voucherOrder);

        proxy = (IVoucherOrderService)AopContext.currentProxy();

        return Result.ok(orderId);


    }
*/
   /* @Override
    public Result getOrder(Long voucherId) {
        //先去数据库查询voucher
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);

        //是否开始或者过期
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("活动尚未开始");
        }

        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("活动已结束");
        }

        //是否还有存量
        if (voucher.getStock() <= 0) {
            return Result.fail("优惠券没有了");
        }

        Long userId = UserHolder.getUser().getId();
        *//*synchronized (userId.toString().intern()) {
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            return proxy.getVoucherOrder(voucherId);
        }*//*

        //使用自己的锁
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        //使用redission的锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            return Result.fail("请不要重复下单");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.getVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }


    }*/

    @Transactional
    public void getVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = UserHolder.getUser().getId();
        //一人一单

        int count = query().eq("voucher_id", voucherOrder).eq("user_id", userId).count();
        if (count > 0) {
            return;
        }

        //减库存
        boolean success = iSeckillVoucherService.update().
                setSql("stock=stock-1").eq("voucher_id", voucherOrder).gt("stock", 0).update();
        if (!success) {
            return;
        }


        save(voucherOrder);



    }
}
