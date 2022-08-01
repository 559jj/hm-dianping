package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 *  在实现扣库存的场景中使用到了乐观锁
 *        悲观锁：添加同步锁，让线程串行执行      乐观锁：不加锁，在更新时判断是否有其他线程在修改
 * 使用场景： 高并发查询操作                      高并发修改操作
 *  优点：   加单粗暴                           性能好
 *  缺点：   性能一般                           存在成功率低的问题
 * </p>
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    ISeckillVoucherService seckillVoucherService;
    @Resource
    RedisIdWorker redisIdWorker;
    @Autowired
    StringRedisTemplate stringRedisTemplate;
    @Autowired
    RedissonClient redissonClient;
    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始，没有则返回异常
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀未开始");
        }
        //3.判断秒杀是否结束，结束则返回异常
        if (LocalDateTime.now().isAfter(voucher.getEndTime())){
            return Result.fail("秒杀已结束");
        }
        //4.判断库存是否充足，不足直接返回异常
        if (voucher.getStock() - 1 < 0) {
            return Result.fail("秒杀券库存不足");
        }
        //5.一人一单
        //使用到了悲观锁 当多线程并发访问时，防止同一用户频繁下单
        //在执行查询语句之前只对同一个的用户进行加锁 保证相同用户只能串行执行
        Long userId = UserHolder.getUser().getId();//每个线程过来时userId都是一个全新的对象，上锁需要看对象的值所以用到了toString
        //TODO synchronized (userId.toString().intern()) {//但是每当调用toString方法时也都是创建了一个新的string对象，所以调用intern()方法相当于引用类型比较变成常量比较
            /* 因为createVoucherOrder方法用到了事务注解
               如果只是像下面这样书写的话并没用用到spring提供的代理对象只是用过this当前类去调用
               而事务的生效需要用到spring对当前类做动态代理，用生成的代理对象去做事务的处理*/
            //TODO return createVoucherOrder(voucherId, userId);
        //TODO }  通过TODO中synchronized方式获取锁不能解决分布式架构中多台服务器并发访问所带来的线程一人一单问题
        //通过redis解决分布式集群中多线程并发访问所带来的线程一人一单问题  通过setnx来设置互斥问题（同一个用户只能创建一个锁）
        //创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order" + userId);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁 参数分别是：获取锁的最大等待时间（期间会重试），锁自动释放时间，时间单位
        boolean tryLock = lock.tryLock();
        //判断获取锁是否成功 若失败返回错误
        if (!tryLock){
            return Result.fail("每人限购一单");
        }
        try {
            //获取代理对象  1添加注解 2启动类中添加注释
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId, userId);
        } finally {
            //释放锁
            lock.unlock();
        }
    }
    @Transactional
    public Result createVoucherOrder(Long voucherId, Long userId) {
        //5.1 查询订单
        int count = query().eq("user_id", userId).eq("voucher", voucherId).count();
        if (count > 0) {
            return Result.fail("每人仅限购一单");
        }
        //6.若库存充足，扣库存
        // 使用到了乐观锁,当多线程并发访问时，防止优惠券超卖，
        // 在执行更新语句时添加条件gt("stock",0)去预防  每次减库存前都会进行gt判断 成立以后才会扣库存
        seckillVoucherService.update()
                .setSql("stock=stock-1")// set stock=stock-1
                .eq("voucher_id", voucherId).gt("stock", 0)//where "voucher_id"=? and "stock">0
                .update();
        //7.生成订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //7.1 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //7.2 用户id
        voucherOrder.setUserId(userId);
        //7.3 代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        //7.4 返回订单id
        return Result.ok(orderId);
    }
}
