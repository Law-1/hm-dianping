-- 参数列表
-- 优惠券id
local voucherId = ARGV[1]
-- 用户ID
local userId = ARGV[2]
-- 订单Id
local orderId = ARGV[3]

--数据key
--库存key
local stockKey = 'seckill:stock:' .. voucherId
--订单key
local orderKey = 'seckill:order:' .. voucherId

--脚本业务
--判断库存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0) then
	return 1
end
--判断用户是否下过单
if (redis.call('sismember', orderKey, userId) == 1) then
	return 2
end
--扣库存
redis.call('incrby', stockKey, -1)
--下单
redis.call('sadd', orderKey, userId)
-- 发送消息到队列中
redis.call('xadd', 'stream.order', '*', 'voucherId', voucherId, 'userId', userId, 'id', orderId)
return 0