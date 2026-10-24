package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FOLLOWS_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //获取登录的用户ID
        Long userId = UserHolder.getUser().getId();
        String key = FOLLOWS_KEY + userId;
        //判断是关注还是取关
        if (isFollow) {
            //关注，新增数据到
            Follow follow =new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                stringRedisTemplate.opsForSet().add(key, String.valueOf(followUserId));
            }
        }else {
            //取关，删除数据
            boolean isRemove = remove(
                    new QueryWrapper<Follow>()
                            .eq("follow_user_id", followUserId).eq("user_id", userId)
            );
            if (isRemove) {
                stringRedisTemplate.opsForSet().remove(key, String.valueOf(followUserId));
            }
        }

        return Result.ok();
    }

    @Override
    public Result isfollow(Long followUserId) {
        //获取登录的用户ID
        Long userId = UserHolder.getUser().getId();
        //查询是否关注
        Integer count = query().eq("follow_user_id", followUserId).eq("user_id", userId).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        //获取当前用户ID
        Long userId = UserHolder.getUser().getId();
        String key = FOLLOWS_KEY + userId;
        String key2 = FOLLOWS_KEY + id;
        //取两个用户关注的交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if ( intersect==null || intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //解析ID集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //获取用户集合
        List<User> users = userService.listByIds(ids);
        List<UserDTO> userDTOS = users.stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
