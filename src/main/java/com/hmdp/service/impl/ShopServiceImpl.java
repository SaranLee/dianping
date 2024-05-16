package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.RedisData;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

    static final ExecutorService QUERY_SHOP_EXECUTORS = Executors.newFixedThreadPool(4);
    final
    StringRedisTemplate stringRedisTemplate;

    public ShopServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Result queryById(Long id) {
//        Shop shop = queryByIdBreakDown(id);
//        Shop shop = queryByIdPassThrough(id);
        Shop shop = queryByIdLogicalExpire(id);
        return Result.ok(shop);
    }


    /**
     * 用逻辑过期时间，解决热点数据缓存击穿问题
     *
     * @param id
     * @return
     */
    private Shop queryByIdLogicalExpire(Long id) {
        // 1. 查redis缓存
        String dataJsonStr = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        // 2. 缓存不存在，直接返回，因为热点数据都是预热过的，如果缓存不存在，则说明数据库也不存在。
        if (dataJsonStr == null)
            return null;
        // 3. 查询逻辑过期时间
        // 3.1 反序列化RedisData
        RedisData data = JSONUtil.toBean(dataJsonStr, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) data.getData(), Shop.class);
        // 3.2 查过期时间
        if (data.getExpireTime().isAfter(LocalDateTime.now())) {
            // 4. 未过期，返回店铺数据
            return shop;
        }
        // 5. 过期了
        // 5.1 获取锁
        if (tryLock(RedisConstants.LOCK_SHOP_KEY + id)) {
            // 5.2 获取锁成功，开启一个线程将shop数据存入redis，然后释放锁
            QUERY_SHOP_EXECUTORS.submit(() -> {
                try {
                    shop2Redis(id, 20L);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(RedisConstants.LOCK_SHOP_KEY + id);
                }
            });
        }
        // 5.3 获取锁失败，直接返回shop数据
        return shop;
    }


    /**
     * 解决缓存穿透的queryById
     *
     * @param id
     * @return
     */
    private Shop queryByIdPassThrough(Long id) {
        // 1. 直接从redis中查
        String shopJsonStr = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        // 2. 如果redis命中，并且不是空串，直接返回结果；
        if (StrUtil.isNotBlank(shopJsonStr)) { // blank: null, "", " ", "\t"
            return JSONUtil.toBean(shopJsonStr, Shop.class);
        }
        // 3. 没有命中，或者是空串。如果是空串，则店铺在数据库中不存在（缓存空串解决缓存穿透问题）
        if (shopJsonStr != null) // redis命中了，是空串
            return null;

        // 4. 缓存没有命中，查数据库
        Shop shop = this.getById(id);
        if (shop == null) {
            // 如果数据库中没有数据，缓存空串解决缓存穿透问题，返回错误信息
            stringRedisTemplate.opsForValue().set(
                    RedisConstants.CACHE_SHOP_KEY + id,
                    "",
                    RedisConstants.CACHE_NULL_TTL,
                    TimeUnit.MINUTES
            );
            return null;
        }
        // 5. 数据库中有数据，将数据缓存到redis
        stringRedisTemplate.opsForValue().set(
                RedisConstants.CACHE_SHOP_KEY + id,
                JSONUtil.toJsonStr(shop),
                RedisConstants.CACHE_SHOP_TTL,
                TimeUnit.MINUTES
        );
        return shop;
    }

    /**
     * 解决缓存穿透和缓存击穿的queryBuId
     *
     * @param id
     * @return
     */
    private Shop queryByIdBreakDown(Long id) {
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        try {
            boolean hasLock = false;
            int maxSpin = 100;   // 设置最大自旋次数，自旋次数超过，返回错误信息
            while (maxSpin > 0) {
                // 1. 直接从redis中查
                String shopJsonStr = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
                // 2. 如果redis命中，并且不是空串，直接返回结果；
                if (StrUtil.isNotBlank(shopJsonStr)) { // blank: null, "", " ", "\t"
                    return JSONUtil.toBean(shopJsonStr, Shop.class);
                }
                // 3. 没有命中，或者是空串。如果是空串，则店铺在数据库中不存在（缓存空串解决缓存穿透问题）
                if (shopJsonStr != null) // redis命中了，是空串
                    return null;

                // 4. 缓存没有命中，查数据库
                // 4.1 为了解决缓存击穿问题，访问数据库时，加锁，保证只有一个请求会访问数据库
                hasLock = tryLock(lockKey);
                // 4.2 如果拿到了锁，访问数据库
                if (hasLock) {
                    Shop shop = this.getById(id);
                    Thread.sleep(300); // 模拟复制业务，需要消耗较长时间来查询数据库
                    if (shop == null) {
                        // 如果数据库中没有数据，缓存空串解决缓存穿透问题，返回错误信息
                        stringRedisTemplate.opsForValue().set(
                                RedisConstants.CACHE_SHOP_KEY + id,
                                "",
                                RedisConstants.CACHE_NULL_TTL,
                                TimeUnit.MINUTES
                        );
                        return null;
                    }
                    // 5. 数据库中有数据，将数据缓存到redis
                    stringRedisTemplate.opsForValue().set(
                            RedisConstants.CACHE_SHOP_KEY + id,
                            JSONUtil.toJsonStr(shop),
                            RedisConstants.CACHE_SHOP_TTL,
                            TimeUnit.MINUTES
                    );
                    return shop;
                }
                Thread.sleep(50);   // 自旋过程中，休眠50ms
                maxSpin--;
            }
            // 超过自选次数，报异常或返回错误信息等
            return null;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);
        }
    }

    @Override
    @Transactional
    public Result updateByIdCacheable(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("店铺id错误！");
        }
        // 1. 首先更新数据库
        updateById(shop);
        // 2. 删除redis缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    /**
     * 用redis的setnx命令来获取一个锁
     *
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        // 设置过期时间，避免死锁
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 将shop数据存入redis，并设置逻辑过期时间
     *
     * @param id
     * @param seconds: 逻辑过期时间，持续多少秒
     */
    public void shop2Redis(Long id, Long seconds) throws InterruptedException {
        Shop shop = getById(id);
        Thread.sleep(200);
        RedisData<Shop> data = new RedisData<>(
                shop,
                LocalDateTime.now().plusSeconds(seconds)
        );
        stringRedisTemplate.opsForValue().set(
                RedisConstants.CACHE_SHOP_KEY + id,
                JSONUtil.toJsonStr(data)
        );
    }
}
