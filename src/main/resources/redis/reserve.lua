-- Real-Redis version of SimulatedRedisStockGate.reserve (atomic: Redis runs a script as one step).
-- KEYS: for each line i: stock:{item}, hold:{item}:{user}, bought:{item}:{user}   (3 keys per line)
-- ARGV[1] = reservation id, ARGV[2] = ttl seconds, ARGV[3] = max units per user, ARGV[4] = now (unix ms),
-- ARGV[5..] = quantity per line
local lines = #KEYS / 3
for i = 0, lines - 1 do                                     -- check everything first: all lines or none
  local qty = tonumber(ARGV[5 + i])
  if redis.call('EXISTS', KEYS[i * 3 + 2]) == 1 then return {err = 'ALREADY_IN_CHECKOUT'} end
  if tonumber(redis.call('GET', KEYS[i * 3 + 3]) or '0') + qty > tonumber(ARGV[3]) then return {err = 'PURCHASE_LIMIT'} end
  if tonumber(redis.call('GET', KEYS[i * 3 + 1]) or '0') < qty then return {err = 'SOLD_OUT'} end
end
local lineData = {}
for i = 0, lines - 1 do
  local qty = tonumber(ARGV[5 + i])
  redis.call('DECRBY', KEYS[i * 3 + 1], qty)
  redis.call('SET', KEYS[i * 3 + 2], ARGV[1], 'NX', 'EX', ARGV[2])
  table.insert(lineData, KEYS[i * 3 + 1] .. '|' .. qty)
end
redis.call('HSET', 'resv:' .. ARGV[1], 'lines', table.concat(lineData, ','))
redis.call('ZADD', 'resv:expiry', tonumber(ARGV[4]) + tonumber(ARGV[2]) * 1000, ARGV[1])
return ARGV[1]
