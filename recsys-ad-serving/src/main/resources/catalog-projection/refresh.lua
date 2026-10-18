-- 首先校验所有类型，避免 WRONGTYPE 出现在一半修改之后；ARGV 不作为 Lua 源码拼接。
local generation = ARGV[1]
local versionType = redis.call('TYPE', KEYS[1]).ok
if versionType ~= 'none' and versionType ~= 'string' then return redis.error_reply('CATALOG_KEY_TYPE') end
local current = redis.call('GET', KEYS[1])
if current and string.sub(current, 1, 19) >= generation then return 1 end
for i = 2, #KEYS do
    local kind = redis.call('TYPE', KEYS[i]).ok
    if kind ~= 'none' and kind ~= 'zset' then return redis.error_reply('CATALOG_KEY_TYPE') end
end
local cursor = 4
local removes = tonumber(ARGV[3])
for i = 1, removes do
    redis.call('ZREM', KEYS[tonumber(ARGV[cursor])], ARGV[cursor + 1])
    cursor = cursor + 2
end
local adds = tonumber(ARGV[cursor])
cursor = cursor + 1
for i = 1, adds do
    redis.call('ZADD', KEYS[tonumber(ARGV[cursor])], ARGV[cursor + 2], ARGV[cursor + 1])
    cursor = cursor + 3
end
redis.call('SET', KEYS[1], generation .. ':' .. ARGV[2])
return 1
