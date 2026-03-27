package com.jktt;


import com.jktt.entity.Shop;
import com.jktt.service.impl.ShopServiceImpl;

import com.jktt.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.jktt.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    CacheClient cacheClient;

    @Resource
    private ShopServiceImpl shopService;

    @Test
    public void testSaveShopToRedis() {
        // 提前存入热点key
        Shop shop = shopService.getById(2);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + shop.getId(), shop, 20L, TimeUnit.SECONDS);
    }
}
