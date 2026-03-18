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
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
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

    /**
     * 登出功能
     * @param request
     * @return
     */
    @Override
    public Result logout(HttpServletRequest request) {
        // 获取token
        String token = request.getHeader("authorization");
        if (token == null || token.isEmpty()) {
            return Result.ok();
        }
        // 删除redis中的用户信息
        stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        return Result.ok();
    }

    /**
     * 签到功能
     * @return
     */
    @Override
    public Result sign() {
        // 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 获取当前日期
        LocalDateTime now = LocalDateTime.now();
        // 拼接key
        String key = USER_SIGN_KEY + userId + ":" + now.getYear() + now.getMonthValue();
        // 计算今天是这个月的第几天
        int dayOfMonth = now.getDayOfMonth();

        // 判断这个日期是否已经签到
        Boolean isSign = stringRedisTemplate.opsForValue().getBit(key, dayOfMonth -1 );
        if (Boolean.TRUE.equals(isSign)) {
            return Result.fail("今天已经签到");
        }
        // 签到
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1 , true);
        return Result.ok(UserHolder.getUser().getNickName()+ "签到成功");
    }

    /**
     * 统计签到天数
     * @return
     */
    @Override
    public Result signCount() {
        // 获取当前登录用户与当前日期
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();

        // 拼接key
        String key = USER_SIGN_KEY + userId + ":" + now.getYear() + now.getMonthValue();
        int dayOfMonth = now.getDayOfMonth();

        // 获取这个月的签到记录，返回一个十进制数字
        List<Long> result = stringRedisTemplate.opsForValue()
                .bitField(key, BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        // 循环遍历，获取最后1位
        long signCount = result.get(0);
        if (signCount == 0 || signCount == -1) {
            return Result.ok(0);
        }
        int count = 0;
        while (true){
            // 让这个数字与1进行位运算，得到最右边的1
            if ((signCount & 1) == 1) {
                signCount = signCount >> 1; // 循环位移，去掉最后一个1
                count++;
            }else {
                break;
            }
        }
        return Result.ok(count);
    }
}
