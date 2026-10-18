-- 整窗替换前检查所有键类型；Redis脚本原子执行不等于运行错误时自动回滚。
local function typed(key, expected)
  local kind = redis.call('TYPE', key).ok
  return kind == 'none' or kind == expected
end
if not typed(KEYS[1], 'hash') or not typed(KEYS[2], 'string') or not typed(KEYS[3], 'zset') or not typed(KEYS[4], 'set') then return -1 end
if redis.call('GET', KEYS[2]) ~= ARGV[1] then return -1 end
local identity = redis.call('HGET', KEYS[1], 'identity')
if identity and identity ~= ARGV[2] then return -1 end
local snapshot = cjson.decode(ARGV[4])
local previous = tonumber(redis.call('HGET', KEYS[1], 'end') or '-1')
if previous > snapshot['end'] then
  redis.call('PEXPIRE', KEYS[2], ARGV[6])
  return 0
end
if previous == snapshot['end'] and redis.call('HGET', KEYS[1], 'digest') ~= ARGV[3] then return -1 end
local users = redis.call('SMEMBERS', KEYS[4])
for _, user in ipairs(users) do if not typed(ARGV[7] .. user, 'hash') then return -1 end end
for user, _ in pairs(snapshot['users']) do if not typed(ARGV[7] .. user, 'hash') then return -1 end end
local now = redis.call('TIME')
local expires = snapshot['end'] + tonumber(ARGV[5])
local fresh = expires > tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
redis.call('DEL', KEYS[3], KEYS[4])
for _, user in ipairs(users) do redis.call('DEL', ARGV[7] .. user) end
if fresh then
  for item, score in pairs(snapshot['hot']) do redis.call('ZADD', KEYS[3], score, item) end
  for user, categories in pairs(snapshot['users']) do
    local key = ARGV[7] .. user
    redis.call('DEL', key)
    for category, count in pairs(categories) do redis.call('HSET', key, category, count) end
    redis.call('PEXPIREAT', key, expires)
    redis.call('SADD', KEYS[4], user)
  end
  redis.call('PEXPIREAT', KEYS[3], expires)
  redis.call('PEXPIREAT', KEYS[4], expires)
end
redis.call('HSET', KEYS[1], 'identity', ARGV[2], 'end', tostring(snapshot['end']), 'digest', ARGV[3])
redis.call('PEXPIRE', KEYS[2], ARGV[6])
return 1
