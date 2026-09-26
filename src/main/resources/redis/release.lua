-- Give a reservation's units back exactly once (MySQL step failed, replay, or swept after TTL).
-- ARGV[1] = reservation id. The hold keys expire on their own (TTL) or are deleted by the caller.
local lines = redis.call('HGET', 'resv:' .. ARGV[1], 'lines')
if not lines then return 0 end                                -- already confirmed, released or swept
for entry in string.gmatch(lines, '[^,]+') do
  local key, qty = string.match(entry, '([^|]+)|(%d+)')
  redis.call('INCRBY', key, tonumber(qty))
end
redis.call('DEL', 'resv:' .. ARGV[1])
redis.call('ZREM', 'resv:expiry', ARGV[1])
return 1
