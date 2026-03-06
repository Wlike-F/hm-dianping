package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author wliekf
 * @since 2025-06
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送验证码
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 检验传进来的phone 是否合法
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        // 2. 生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 3. 保存验证码到session
        // set方法：key, value, timeout, time unit
        // 3.1 判断redis连接是否正常
        if (stringRedisTemplate == null) {
            return Result.fail("redis连接异常");
        }
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 4. 模拟发送验证码
        log.debug("发送短信验证码成功，手机号：{},验证码：{}", phone,code);

        return Result.ok();
    }

    /**
     * 创建用户
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone) {
        // 1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 2.保存用户
        save(user);
        return user;
    }
    /**
     * 登录功能
     * @param loginForm:phone|code|password
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号格式
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        // 2. 从redis获取验证码并校验
        String code_redis = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (code_redis == null || !code_redis.equals(loginForm.getCode()))
            return Result.fail("验证码错误");

        // 3. 如果验证码正确，查询用户, 不存在则创建
        User user = query().eq("phone", phone).one();
        if (user == null){
            user = createUserWithPhone(phone);
        }

        // 4. 保存用户信息到redis中
        // 4.1 随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString();
        // 4.2 将用户信息转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // 4.3 存储到redis中
        String key = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(key, userMap);
        // 4.4 设置登录令牌的有效期
        stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.SECONDS);

        log.debug("用户登录成功，用户id：{},手机号：{}", user.getId(), phone);
        return Result.ok(token);
    }

    @Override
    public Result logout() {
        return null;
    }


}
