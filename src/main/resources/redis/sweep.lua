-- Return units of reservations whose TTL passed (crash between reserve and MySQL commit).
-- ARGV[1] = now (unix ms). Each expired id is released with the same logic as release.lua.
local expired = redis.call('ZRANGEBYSCORE', 'resv:expiry', '-inf', ARGV[1], 'LIMIT', 0, 100)
for _, id in ipairs(expired) do
  local lines = redis.call('HGET', 'resv:' .. id, 'lines')
  if lines then
    for entry in string.gmatch(lines, '[^,]+') do
      local key, qty = string.match(entry, '([^|]+)|(%d+)')
      redis.call('INCRBY', key, tonumber(qty))
    end
    redis.call('DEL', 'resv:' .. id)
  end
  redis.call('ZREM', 'resv:expiry', id)
end
return #expired
