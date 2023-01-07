package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
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
import java.util.concurrent.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {


    @Resource
    private ISeckillVoucherService iSeckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT= new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //获取消息队列
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
                    log.error("消息处理异常");
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



    //获取阻塞队列
    /*private class VoucherOrderHandler implements Runnable{
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
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;

    //消息队列
    @Override
    public Result seckillVoucher(Long voucherId) {
        //执行luna脚本，判断返回结果
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        //执行luna脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),String.valueOf(orderId));
        int r = result.intValue();
        if(r != 0){
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }


        //获取代理对象(事务)
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(orderId);
    }

    //阻塞队列
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //执行luna脚本，判断返回结果
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString());
        int r = result.intValue();
        if(r != 0){
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }

        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        orderTasks.add(voucherOrder);
        //获取代理对象(事务)
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(orderId);
    }*/


//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //查询优惠券
//        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
//        //判断时间和库存
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始");
//        }
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已结束");
//        }
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//
//        Long userId = UserHolder.getUser().getId();
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        boolean isLock = lock.tryLock();//打断点debug时间比较长，设的时间比较长
//        if(!isLock){
//            return Result.fail("不允许重复下单");
//        }
//
//        try {
//            //获取代理对象(事务)
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//
//
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {

        //一人一单的判断
        Long userId = voucherOrder.getUserId();


        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder).count();
        if (count > 0) {
            log.error("您已经购买过该优惠券");
            return;
        }

        //扣减库存
        boolean success = iSeckillVoucherService.update().
                setSql("stock = stock-1").
                eq("voucher_id", voucherOrder).gt("stock", 0).
                update();
        if (!success) {
            log.error("库存不足！");
        }

        save(voucherOrder);


    }
}
