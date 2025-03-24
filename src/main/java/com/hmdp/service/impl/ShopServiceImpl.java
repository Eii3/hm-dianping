package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;


import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
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
        // 解决缓存穿透
        //Shop shop = queryWithPassThrough(id);

        // 解决缓存击穿
        Shop shop = queryWithMutex(id);
        if(shop == null){
            return Result.fail("店铺不存在");
        }

        // 5.返回商铺信息
        return Result.ok(shop);
    }

    public void saveShopToRedis(Long id, Long expireSeconds){
        // 1.查询店铺数据
        Shop shop = getById(id);

        // 2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        // 3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
        // 没有设置ttl 永久有效 真正过期时间为设置的逻辑过期时间
    }


    // 互斥锁解决缓存击穿
    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在
        //isNotBlank: null、""、"\t\n"为false
        if(StrUtil.isNotBlank(shopJson)){
            // 3.存在 直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 存在但为空值
        if(shopJson != null){
            // 返回一个错误信息
            return null;
        }

        // 4.不存在 实现缓存重建
        // 4.1.实现互斥锁
        String lockKey = "lock:shop:"+id;
        Shop shop = null;
        try {
            // 4.2.判断是否获取锁
            if(!tryLock(lockKey)){
                // 4.3.获取失败 休眠重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            // 4.4.获取成功 根据id查询数据库
            shop = getById(id);

            // 5.不存在 返回错误
            if(shop == null){
                // 将空值写入redis
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }

            // 6.存在 写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            // 这是个打断异常 不用做处理
            throw new RuntimeException(e);
        } finally {
            // 7.释放互斥锁
            unlock(lockKey);
        }

        // 8.返回商铺信息
        return shop;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    // 封装解决缓存穿透的代码
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        //isNotBlank: null、""、"\t\n"为false
        if(StrUtil.isNotBlank(shopJson)){
            // 3.存在 直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断是否命中空值
        if(shopJson != null){
            // 返回一个错误信息
            return null;
        }
        // 4.不存在 根据id查询数据库
        Shop shop = getById(id);
        // 4.1.判断商铺是否存在
        if(shop == null){
            // 4.2.不存在 返回404
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        // 4.3.存在 存入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 5.返回商铺信息
        return shop;
    }




    @Override
    @Transactional //开启事务
    public Result update(Shop shop) {
        // 1.检验id
        Long id = shop.getId();
        if(id == null){
            return Result.fail("商铺id不能为空");
        }
        // 2.更新数据库
        updateById(shop);

        // 3.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
