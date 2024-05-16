package com.hmdp;

import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import org.apache.ibatis.annotations.Result;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private IShopService shopService;

    @Test
    public void testShop2Redis() {
        try {
            shopService.shop2Redis(1L, 10L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
