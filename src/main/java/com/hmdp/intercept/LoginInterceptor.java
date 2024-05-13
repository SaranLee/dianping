package com.hmdp.intercept;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpStatus;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LoginInterceptor implements HandlerInterceptor {

    /***
     * 注意这里的StringRedisTemplate不能自动注入，因为LoginInterceptor
     * 不是由Spring创建的，而是我们手动new的，因此通过构造函数给它赋值。
     * 在new的时候将注入的stringRedisTemplate值传递给它。
     */
    private final StringRedisTemplate stringRedisTemplate;

    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        System.out.println("===============================================");
//        HttpSession session = request.getSession();
//        User user = (User) session.getAttribute(SystemConstants.STR_USER);
//        // session中没有user
//        if (user == null) {
//            response.setStatus(HttpStatus.HTTP_NOT_AUTHORITATIVE);
//            return false;
//        }
//        // session中有user，将其存到ThreadLocal
//        UserDTO userDTO = new UserDTO();
//        BeanUtils.copyProperties(user, userDTO);
//        UserHolder.saveUser(userDTO);
//        return true;
        // ===================================== 使用Redis ================================================
        // 1. 获取token
        String token = request.getHeader("authorization");
        // 2. 从redis中取token对应的user
        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + token);
        // 3. 用户不存在，未登录
        if (map.isEmpty()) {  // map不可能未null，看entries源码
            response.setStatus(HttpStatus.HTTP_NOT_AUTHORITATIVE);
            return false;
        }
        // 4. 用户存在，已登录，需要刷新redis的ttl
        // 4.1 将map转为user
        UserDTO user = BeanUtil.fillBeanWithMap(map, new UserDTO(), false);
        // 4.2 刷新ttl
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);
        // 5. 将用户存入ThreadLocal
        UserHolder.saveUser(user);
        return true;
    }
}
