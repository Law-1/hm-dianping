package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import cn.hutool.core.lang.UUID;
import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    //给ttl设置随机值防止缓存雪崩
    long totalTTLCode = LOGIN_CODE_TTL + RandomUtil.randomLong(0,10);
    long totalTTLUser = LOGIN_USER_TTL + RandomUtil.randomLong(0,600);


    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误！");
        }
        //2.生成验证码
        String code = RandomUtil.randomNumbers(6);
        //3.保存到session
//        session.setAttribute("code", code);
        //保存到redis中
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, totalTTLCode, TimeUnit.MINUTES);
        //4.发送验证码(模拟)
        log.debug("发送验证码成功，验证码：{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误！");
        }
        //2.校验验证码
//        Object cacheCode = session.getAttribute("code");
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)){
            return Result.fail("验证码错误");
        }
        //3.校验用户是否存在
        User user = query().eq("phone", phone).one();
        if (user == null){
            user = createUserWithPhone(phone);
        }
        //5.保存用户信息到session
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        //保存用户信息到redis中
        //随机生成token作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //将User对象转为HashMap存储      Hash结构可以将对象中的每个字段独立存储，可以针对单个字段做CRUD，并且占用内存更少
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true) //忽略null字段，不存入redis
                        .setFieldValueEditor((fieldName, fieldVal) -> fieldVal.toString()) //全部转为字符串，适配StringRedisTemplate
        );
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(tokenKey, totalTTLUser, TimeUnit.SECONDS);
        //返回token前端
        return Result.ok(token);
    }

    //当前用户今日签到功能
    @Override
    public Result sign() {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 获取日期
        LocalDateTime dateTime = LocalDateTime.now();
        // 拼接key
        String keySuffix = dateTime.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 获取今天是本月第几天
        int day = dateTime.getDayOfMonth();
        // 存入redis SETBIT key offset 0/1
        stringRedisTemplate.opsForValue().setBit(key, day, true);
        return Result.ok();
    }

    //当前用户本月今日连续签到统计功能
    @Override
    public Result signCount() {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 获取日期
        LocalDateTime dateTime = LocalDateTime.now();
        // 拼接key
        String keySuffix = dateTime.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 获取今天是本月第几天
        int day = dateTime.getDayOfMonth();
        // 获取本月至今天为止所有签到的记录, 返回的是一个十进制的数字   BITFIELD key get u(number) offset(偏移) u表示无符号位，i表示有符号位，eg:`u8` 8 位无符号整数（0~255）
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(day)).valueAt(0)
        );
        if (result == null || result.isEmpty()){
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0){
            return Result.ok(0);
        }
        // 循环遍历
        int count = 0;
        while (true){
            // 让这个数据与1做与运算，得到这个数字的最后一个bit位, 判断这个bit位是否为0
            if ((num & 1) == 0) {
                // 为0，未签到，结束
                break;
            }else {
                // 不为0，已签到，计数器+1
                count++;
            }
            //把数字右移一位
            num >>>= 1;
        }
        return Result.ok(count);
    }
/*
运算符         名字              规则                                                  正数效果                        负数效果
`<<`        左移              二进制整体往左移，右边补 0                          等价 ×2                   符号可能变，不推荐负数用
`>>`        带符号右移         二进制整体往右移，左边补 符号位（正数补 0，负数补 1）     等价 ÷2（向下取整）          仍然是负数，保持符号
`>>>`       无符号右移         二进制整体往右移，左边永远补 0                       和 `>>` 一样，等价 ÷2        负数直接变成很大的正数（坑！）
*/

    private User createUserWithPhone(String phone) {
        //创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        //保存用户
        save(user);
        return user;
    }
}
