-- 1、参数列表
-- 1-1参数列表 优惠劵id
local voucherId = ARGV[1]
-- 1-2参数列表 用户id
local userId = ARGV[2]

-- 2、数据key
--2-1参数列表 库存key
local stockKey = "seckill:stock:" .. voucherId
--2-2参数列表 订单key
local orderKey = "seckill:order:" .. userId

-- 3、脚本逻辑
-- 3-1 判断库存是否充足
if (tonumber(redis.call("get", stockKey)) <= 0) then
    -- 库存不足
    return 1
end

-- 3-2 判断用户是否重复下单
if (redis.call("sismember", orderKey, userId) == 1) then
    -- 用户重复下单
    return 2
end

-- 3-3 扣减库存
redis.call("incrby", stockKey, -1)
-- 3-4 下单成功
redis.call("sadd", orderKey, userId)
return 0