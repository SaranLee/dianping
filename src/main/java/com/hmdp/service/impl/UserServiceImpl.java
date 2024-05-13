package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.lang.generator.UUIDGenerator;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
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
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    final
    StringRedisTemplate stringRedisTemplate;

    public UserServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 校验手机号格式
        if (RegexUtils.isPhoneInvalid(phone))
            return Result.fail("手机号码格式不正确！");
        // 生成验证码
        String verifyCode = RandomUtil.randomNumbers(6);
        // 将验证码保存到redis，使用手机号作为key，并且设置5min有效期
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, verifyCode, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
                // session.setAttribute(SystemConstants.STR_VERIFY_CODE, verifyCode);
        // 发送验证码给用户
        log.info("验证码：{}", verifyCode);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String verifyCodeLogin = loginForm.getCode();
        // 校验手机号格式
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone))
            return Result.fail("手机号码格式不正确！");

        // 从Redis中获取验证码，key为手机号
        String verifyCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
                // Object verifyCode = session.getAttribute(SystemConstants.STR_VERIFY_CODE);
        // 校验验证码
        if (Objects.isNull(verifyCode))
            return Result.fail("请发送验证码！");
        if (!Objects.equals(verifyCode, verifyCodeLogin))
            return Result.fail("验证码不正确！");

        // 查询用户是否存在
        User user = this.query().eq("phone", phone).one();
        // 如果用户不存在，新建用户
        if (user == null) {
            user = new User();
            user.setPhone(phone);
            user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
            this.save(user);
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 生成一个token，将其作为保存用户的key，将用户保存到redis
        // 生成token
        String token = UUID.randomUUID().toString(true);
        // 将User存到redis，用hash形式
        Map<String, Object> userMap = BeanUtil.beanToMap(
                userDTO,
                new HashMap<>(),
                CopyOptions.create().setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
        );
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + token, userMap);
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);
                //session.setAttribute(SystemConstants.STR_USER, user);
        // 将token返回给客户端
        return Result.ok(token);
    }
}
