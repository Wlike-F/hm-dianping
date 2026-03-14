package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import cn.hutool.core.bean.BeanUtil;
import com.hmdp.utils.RedisConstants;

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

    /**
     * 查询是否关注
     *
     * @param followUserId
     * @return
     */
    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        if (userId == null){
            return Result.fail("请先登录!");
        }
        int count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    /**
     * 关注或取关
     * @param followUserId
     * @param isFollow
     * @return
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        if (userId == null){
            return Result.fail("请先登录!");
        }
        String key = RedisConstants.FOLLOWS_KEY + userId;
        // 判断是关注还是取关
        if (isFollow){
            // 关注
            Follow follow = new Follow().setUserId(userId).setFollowUserId(followUserId);
            boolean save = save(follow); // 关注成功
            if (save) {
                // 把关注用户的id，放入redis的set集合 sadd userId followerUserId
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
            return save ? Result.ok() : Result.fail("关注失败!");
        }else {
            // 取关
            boolean remove = remove(new LambdaQueryWrapper<Follow>().eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, followUserId)); // 取关成功
            if (remove) {
                // 把关注用户的id从Redis集合中移除
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
            return remove ? Result.ok() : Result.fail("取关失败!");
        }
    }

    /**
     * 查询共同关注
     * @param id
     * @return
     */
    @Override
    public Result followCommons(Long id) {
        Long userId = UserHolder.getUser().getId();
        if (userId == null){
            return Result.fail("请先登录!");
        }

        String key = RedisConstants.FOLLOWS_KEY + userId;
        String key2 = RedisConstants.FOLLOWS_KEY + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty()){
            // 无交集
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
