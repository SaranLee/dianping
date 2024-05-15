package com.hmdp.intercept;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class EverythingInterceptor implements HandlerInterceptor {
    /**
     * 拦截所有请求，用于刷新用户登录状态
     */

    private StringRedisTemplate stringRedisTemplate;

    public EverythingInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 获取token
        String token = request.getHeader("authorization");
        String userKey = RedisConstants.LOGIN_USER_KEY + token;
        // 2. 在redis中查用户
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(userKey);
        // 3. 如果用户存在，将User存入ThreadLocal，更新ttl
        if (userMap.size() > 0) {
            UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
            UserHolder.saveUser(userDTO);
            stringRedisTemplate.expire(userKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);
        }
        // 4. 用户不存在，直接返回
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
