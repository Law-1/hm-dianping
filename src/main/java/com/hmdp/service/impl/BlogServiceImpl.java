package com.hmdp.service.impl;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    
    @Resource
    private IFollowService followService;

    @Override
    public Result queryBlogById(Long id) {
        //查询Blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        //查询Blog相关的用户
        queryBlogUser(blog);
        //查询blog是否被点赞
        isBlogLike(blog);
        return Result.ok(blog);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        records.forEach(blog -> {
            // 查询blog相关用户
            this.queryBlogUser(blog);
            //查询blog是否被点赞
            this.isBlogLike(blog);
        });


        return Result.ok(records);
    }

/*    // 写法A（课程现在这个）
if (BooleanUtil.isFalse(isMember)){
        // false → 没点赞，去点赞
    }else{
        // true → 已点赞，取消点赞
    }

// 写法B（换成isTrue，逻辑反过来，更容易看懂）
if (BooleanUtil.isTrue(isMember)){
        // true → 已经点赞，取消点赞
    }else{
        // false / null → 没点赞，去点赞
    }*/

    @Override
    public Result likeBlog(Long id) {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        //判断当前登录用户是否点过赞
        String key = BLOG_LIKED_KEY + id;
//        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            //没点过赞
           //数据库点赞数+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //保存用户到redis的set集合中 zadd key value score
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }else {
            //点过赞
            //数据库点赞数-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            //移除redis的set集合中的用户
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        //查询top5用户id zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(BLOG_LIKED_KEY + id, 0, 4);
        if (top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //解析出其中的用户ID
        List<Long> ids = top5.stream()// ① 把集合变成【流】
                .map(Long::valueOf)// ② map：对里面每一个元素做转换
                .collect(Collectors.toList());// ③ 把转换后的结果收集成List   等价于lambda: map(str -> Long.valueOf(str))
        String idStr = StrUtil.join(",", ids);
        //根据用户ID查询用户信息  where id in (?,?,...) order by field (id,?,?,...)
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id"+ idStr + ")").list()
                //listByIds不能根据传进来的id顺序来排序，所以要自己写
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //返回
        return Result.ok(userDTOS);
    }

    
    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店笔记
        boolean isSuccess = save(blog);
        if (!isSuccess){
            return Result.fail("新增笔记失败");
        }
        //获取笔记作者所有粉丝    select * from tb_follow where follow_user_id （发笔记的用户） = user_id （粉丝用户）
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //推送笔记id给所有粉丝
        for (Follow follow :follows) {
            //获取粉丝id
            Long userId = follow.getUserId();
            //推送笔记
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    //分页查询笔记
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 5);
        if (typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        //解析数据：blogId、minTime、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0L;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple: typedTuples) {
            //笔记(blog)ID
//            String idStr = tuple.getValue();
//            ids.add(Long.valueOf(idStr));
            ids.add(Long.valueOf(tuple.getValue()));
            //分数(时间戳)
            long time = tuple.getScore().longValue();
            if (minTime == time){
                os++;
            }else {
                minTime = time;
                os = 1;
            }
        }
        //根据id查blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id" + idStr + ")").list();
        for (Blog blog: blogs) {
//           todo:  查出来的 Blog 实体**只有博客本身的数据（标题、内容、时间、user_id），没有用户昵称、头像，
//            也没有【当前登录用户有没有点赞】这个状态**，这两个信息不是存在 blog 表里的，所以必须循环补查。
            //查询Blog相关的用户
            queryBlogUser(blog);
            //查询blog是否被点赞
            isBlogLike(blog);
        }
        //封装并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);
        scrollResult.setList(blogs);
        return Result.ok(scrollResult);
    }


    public void queryBlogUser(Blog blog){
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    public void isBlogLike(Blog blog){
        //获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null){
            //用户未登录，无需查询是否点赞
            return;
        }
        Long userId = user.getId();
        //判断当前登录用户是否点过赞
        String key = BLOG_LIKED_KEY + blog.getId();
//        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }
}
