package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;

import org.springframework.beans.factory.annotation.Autowired;
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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

//多用户请求会触发 Tomcat 多个 JVM 线程并行跑 Java 代码；如果查询库存和扣库存拆成两段 Java 代码，就会出现同时读到库存大于 0，引发超卖。
//todo: Java 分布式锁可以解决这个问题，但是并发性能差；
// Lua 脚本把判断和扣库存放到 Redis 内部原子执行，Redis 单线程串行执行脚本，消除查询与修改的时间间隙，高并发性能更好
/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    //提前加载Lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
    }


    //线程
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    //获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //判断消息获取是否成功
                    if (list == null | list.isEmpty()) {
                        //失败，（没有消息）继续下一次循环
                        continue;
                    }
                    //解析订单中的信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //成功，则下单
                    handlerVoucherOrder(voucherOrder);
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "q1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常信息", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    //获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS streams.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("q1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //判断消息获取是否成功
                    if (list == null | list.isEmpty()) {
                        //失败，（Pending-list没有消息）结束
                        break;
                    }
                    //解析订单中的信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //成功，则下单
                    handlerVoucherOrder(voucherOrder);
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "q1", record.getId());
                } catch (Exception e) {
                    log.error("处理Pending-list订单异常信息", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }

/*    //生成阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            try {
                //获取队列中的订单信息
                VoucherOrder voucherOrder = orderTasks.take();
                //创建订单
                handlerVoucherOrder(voucherOrder);

            } catch (InterruptedException e) {
                log.error("处理订单异常信息",e);
            }
        }
    }*/

    // (redissonClient) 防止同一个用户多条消息并发执行数据库下单，保证一人一单
    private void handlerVoucherOrder(VoucherOrder voucherOrder) {
        //获取用户id
        Long userId = voucherOrder.getUserId();
        //        创建锁对象
//        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        Boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("不可重复下单");
        }
        try {
            proxy.createVoucherOrder(voucherOrder, userId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }


/*    @Override
    public Result seckillVoucher(Long voucherId) {
        //查询优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        //判断秒杀是否开始
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀活动未开始");
        }
        //判断秒杀是否已经结束
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀活动已结束");
        }
//        //判断库存是否充足
//        if (seckillVoucher.getStock() < 1){
//            return Result.fail("库存不足");
//        }
        Long userId = UserHolder.getUser().getId();

//         todo 为什么要用 proxy 代理，而不是直接 this.createVoucherOrder(...)？
//
//         1. 问题本质：@Transactional 是基于 Spring AOP 动态代理实现的，事务的开启/提交/回滚逻辑
//            写在【代理对象】里，而不是写在原始对象（this）里。
//            Spring 容器对外暴露的 Bean 其实是这个代理对象：
//                调用方 --> 代理对象（开启事务 --> 目标方法 --> 提交事务）
//
//         2. 直接 this 调用会绕过代理：
//            在 seckillVoucher() 内部写 this.createVoucherOrder(...)，这是同一个类的【自调用】，
//            走的是原始对象自身的方法，完全没有经过代理对象，
//            结果就是 @Transactional 注解彻底失效 —— 不会开启事务。
//
//         3. 失效带来的后果（对应本秒杀场景）：
//            createVoucherOrder 里有「扣减库存」+「创建订单」两步数据库写操作。
//            没有事务保护时，若扣减库存成功、创建订单失败（或抛异常），
//            库存不会回滚，造成数据不一致：库存少了却没有对应订单。
//
//         4. 解决方案：通过 AopContext.currentProxy() 拿到当前线程正在执行的代理对象，
//            用 proxy 去调用 createVoucherOrder()，这样调用就会重新走一遍代理链路，
//            @Transactional 才能生效。
//
//         5. 为什么要放在 synchronized 内部获取 proxy？
//            顺序必须是「先加锁 --> 再开事务 --> 提交事务 --> 后释放锁」，
//            保证事务提交完成后锁才释放，避免其他线程读到未提交的数据（详见文件末尾的错误案例）。
//            如果这里直接调用 this，锁虽然生效，但事务失效，锁就失去意义了。
//
//         6. 使用前提：AopContext.currentProxy() 需要开启 exposeProxy = true，
//            否则运行时会抛 IllegalStateException: Cannot find current proxy。
//            需在启动类上添加：@EnableAspectJAutoProxy(exposeProxy = true)

            other:
            <dependency>
            <groupId>org.aspectj</groupId>
            <artifactId>aspectjweaver</artifactId>
            </dependency>

//        synchronized (userId.toString().intern()) {
//            //获取代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId, userId);
//        }

        // todo: 创建分布式锁对象，保证同一个用户争抢同一把锁
//        创建锁对象
//        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        try {
            //获取锁
            Boolean isLock = lock.tryLock();
            if (!isLock) {
                return Result.fail("不允许重复下单");
            }

            //获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId, userId);

        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }


    }*/

    private IVoucherOrderService proxy;

    //抢单
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //生成订单Id
        long orderId = redisIdWorker.nextId("order");
        //执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        //判断结果是否为0
        if (r !=0){
            return Result.fail(r == 1 ? "库存不足" : "不可重复下单");
        }
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回成功结果
        return Result.ok(orderId);
    }


    /*//抢单
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        int r = result.intValue();
        //判断结果是否为0
      if (r !=0){
          return Result.fail(r == 1 ? "库存不足" : "不可重复下单");
      }
        //为0，把下单信息保存到阻塞队列
        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //生成订单Id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //用户Id
        voucherOrder.setUserId(userId);
        //代金券Id
        voucherOrder.setVoucherId(voucherId);
        //放入阻塞队列中
        orderTasks.add(voucherOrder);
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回成功结果
        return Result.ok(orderId);
    }
*/

    //创建订单
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder, Long userId) {
    //todo: 一人一单

    //核心目的：给同一个用户加锁
    //serId 是 Long 对象，每次从 UserHolder 拿出来，**可能是 new 出来不同 Long 对象**，就算 id 值相同，对象地址不一样，锁失效！
    //同一个 userId，调用`intern()`之后，拿到常量池同一个字符串对象。

        //查询并判断用户是否下过单
        Integer count = query().eq("user_id", userId).count();
        if (count > 0) {
            log.error("不可重复下单");
        }

        //扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .gt("stock", 0) // 增加条件：stock > 0，数据库层面校验库存>0才扣减 //todo: 防止超卖(推荐方法) 库存阈值判断
                .eq("voucher_id", voucherOrder.getVoucherId()).update();
        if (!success) {
            log.error("库存不足");
        }
        //把订单写入数据库
        save(voucherOrder);

/*        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单Id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //用户Id
        voucherOrder.setUserId(userId);
        //代金券Id
        voucherOrder.setVoucherId(voucherId);
        //把订单写入数据库
        save(voucherOrder);
        //返回订单Id
        return Result.ok(orderId);*/
    }

    //todo:错误案例
/*    事务先开启，再上锁：锁失效
    @Transactional
    public Result seckill(){
        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()){
            //数据库操作
        }
    }
    原因：事务提交是方法执行完才提交。锁释放了，事务还没提交！别的线程直接进入，并发下依然能重复下单！
    */

}



