package com.jktt.service.impl;

import com.jktt.dto.Result;
import com.jktt.entity.Shop;
import com.jktt.mapper.ShopMapper;
import com.jktt.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jktt.utils.TwoLevelCacheClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.jktt.utils.RedisConstants.*;

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
    private TwoLevelCacheClient twoLevelCacheClient;

    @Override
    public Result queryById(Long id) {
        Shop shop = twoLevelCacheClient
                .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        // 5.返回商铺信息
        return Result.ok(shop);
    }

    @Override
    @Transactional //开启事务
    public Result update(Shop shop) {
        // 1.检验id
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商铺id不能为空");
        }
        // 2.更新数据库
        updateById(shop);

        // 3.删除缓存
        twoLevelCacheClient.invalidate(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
