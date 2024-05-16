package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
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

    final
    StringRedisTemplate stringRedisTemplate;

    public ShopServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Result queryById(Long id) {
        Shop shop = queryByIdBreakDown(id);
//        Shop shop = queryByIdPassThrough(id);
        return Result.ok(shop);
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
}
