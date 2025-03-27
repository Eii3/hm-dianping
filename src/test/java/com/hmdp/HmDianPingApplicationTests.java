package com.hmdp;


import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;

import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

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
