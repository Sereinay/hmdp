package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
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
    StringRedisTemplate stringRedisTemplate;

    //使用互斥锁来解决缓存击穿问题：
    @Override
    public Result queryById(Long id) {
        //1.从redis里查询是否存在
        String cacheStr = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.存在，直接返回
        if (StrUtil.isNotBlank(cacheStr)) {
            Shop shop = JSONUtil.toBean(cacheStr, Shop.class);
            return Result.ok(shop);
        }
        //判断是否cacheStr==null 来解决缓存穿透的问题(保存空对象)
        if (cacheStr != null) {
            return Result.fail("商铺不存在！");
        }
        //引入解决缓存击穿的方案：互斥锁
        Shop shop = null;
        try {
            //1.获取锁
            boolean flag = isLock(LOCK_SHOP_KEY + id);
            //2.判断锁是否获取成功
            if (!flag) {
                //3.失败 等待后重新尝试获取
                Thread.sleep(50);
                return queryById(id);
            }
            //4.成功 判断是否缓存重建完毕
            String newCacheStr = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            if (StrUtil.isNotBlank(newCacheStr)) {
                return Result.ok(JSONUtil.toBean(newCacheStr, Shop.class));
            }
            // 否则去数据库里进行查询
            shop = getById(id);
            //模拟重建延迟
            Thread.sleep(200);

            if (shop == null) {
                //数据库的值为空 则缓存空对象
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                //然后返回不存在
                return Result.fail("查询的商户不存在！");
            }
            //5.把查询结果写回redis，返回数据给请求端,同时添加了TTL 超时更新策略
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //6.释放锁
            unLock(LOCK_SHOP_KEY + id);
        }
        return Result.ok(shop);
    }
    //引入一个线程池来做缓存重建
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //使用逻辑过期来解决缓存击穿问题：
    public Result queryByLogicExpireTime(Long id) {
        //1.从redis里查询是否存在
        String cacheStr = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.未命中 直接返回null
        if (StrUtil.isBlank(cacheStr)) {
            return Result.fail("商户不存在");
        }
        //引入解决缓存击穿的方案：逻辑过期
        //3.命中 则需要判断过期时间
        RedisData redisData = JSONUtil.toBean(cacheStr, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //4. 过期时间判断结果
        if (expireTime.isAfter(LocalDateTime.now())) {
            //4.1 未过期，返回商铺信息
            return Result.ok(shop);
        }
        //4.2 已过期 需要重建缓存
        //5. 重建缓存
        //5.1 尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = isLock(lockKey);
        if (isLock) {
            //5.1.1 成功
            //首先进行过期时间判断 若未过期则无需重建缓存 直接返回即可
            RedisData newRedisData = JSONUtil.toBean(cacheStr, RedisData.class);
            Shop shopNew = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
            LocalDateTime expireTimeNew = redisData.getExpireTime();
            //4. 过期时间判断结果
            if (expireTimeNew.isAfter(LocalDateTime.now())) {
                //4.1 未过期，返回商铺信息
                return Result.ok(shop);
            }
            //4.2 过期 开始启独立线程(使用线程池来做)
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //5.1.1.1 重建缓存
                    this.saveShopData2Redis(id, LOCK_SHOP_TTL);
                }catch (Exception e){
                    throw new  RuntimeException(e) ;
                }
                finally {
                    //5.1.1.2 释放锁
                    unLock(lockKey);
                }
            });
        }
        //5.1.2 返回旧的信息
        return Result.ok(shop);
    }

    private boolean isLock(String key) {
        Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(key, "lock", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(isLock);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    //用于逻辑时间解决缓存击穿的预热 进行热点数据提前存入Redis并设置逻辑过期时间
    public void saveShopData2Redis(Long id, Long expireSecond) throws InterruptedException {
        //1.根据id查询数据
        Shop shop = getById(id);
        Thread.sleep(200);
        //2.将数据写入RedisData
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSecond));
        //3.把RedisData保存到Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺的id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
