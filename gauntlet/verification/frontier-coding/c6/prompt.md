Write a JavaScript function `runExchange(events)` that simulates the order book of a single stock and returns every trade and the final state of the book.

`events` is an array (at most 5,000 items) processed in order. Each event is one of:
- `["LIMIT", id, side, price, qty]`
- `["ICEBERG", id, side, price, qty, peak]`
- `["MARKET", id, side, qty]`
- `["STOP", id, side, trigger, qty]`
- `["CANCEL", id]`
- `["AMEND", id, price, qty]`

`side` is `"BUY"` or `"SELL"`. `id`, `price`, `qty`, `peak` and `trigger` are integers from 1 to 1,000,000. The ids of LIMIT, ICEBERG, MARKET and STOP events are all different from each other; CANCEL and AMEND may name any id, including ids that were never used or whose orders are gone.

**Resting orders.** Orders waiting in the book are called resting orders. Each has a side, a price, a remaining quantity `rem`, a displayed quantity `disp` (at most `rem`) and a priority number. A resting LIMIT order always has `disp = rem`. A resting ICEBERG order shows only part of its quantity. Priority numbers come from one global counter that starts at 1 and increases by 1 every time a number is handed out; an order gets a new number whenever it starts resting and in the other situations stated below. The *best* resting buy order is the one with the highest price, ties broken by the smallest priority number; the best resting sell order is the one with the lowest price, ties broken by the smallest priority number.

**Matching.** An incoming order has a side, a quantity q and possibly a limit price P. While q > 0, look at the best resting order o of the opposite side. Stop if there is none, or if the incoming order has a limit price and o's price is worse for it (for an incoming buy: o's price > P; for an incoming sell: o's price < P). Otherwise a trade of x = min(q, o's `disp`) shares happens at o's price: q, o's `disp` and o's `rem` all decrease by x. If o's `rem` is now 0, o leaves the book. Otherwise, if o's `disp` is now 0 (this only happens to icebergs), o's `disp` is refilled to min(peak, `rem`) and o gets a new priority number (so it queues behind the other orders at its price). Then continue the loop. Every trade is recorded as `[buyId, sellId, price, qty]`, where `buyId` and `sellId` are the ids of the buying and the selling order (whichever of them was incoming). The *last trade price* is the price of the most recent trade; before the first trade there is none.

**Events.**
- `LIMIT`: the order matches as an incoming order with limit price `price` and quantity `qty`. If q > 0 remains, it rests with `rem = disp = q`.
- `ICEBERG`: the order first matches like a LIMIT order with its full quantity `qty`. If q > 0 remains, it rests as an iceberg with `rem = q` and `disp = min(peak, q)`.
- `MARKET`: the order matches with no limit price; any quantity left over is discarded.
- `STOP`: the order does not match and does not rest; it is stored as a dormant stop order (see below).
- `CANCEL`: if `id` is a resting order it is removed from the book; if it is a dormant stop order it is discarded; otherwise nothing happens.
- `AMEND`: applies only if `id` is a resting order that was submitted by a LIMIT event (or by an earlier AMEND of such an order); otherwise nothing happens. If `price` equals the order's current price and `qty` <= its current `rem`, the order stays where it is with `rem = disp = qty` and keeps its priority number. Otherwise the order is removed from the book and immediately re-enters as an incoming LIMIT order with the same id and side and the new `price` and `qty`: it matches as described above and any remainder rests with a new priority number.

**Stop orders.** A dormant buy stop can trigger when a last trade price exists and it is >= the stop's `trigger`; a dormant sell stop can trigger when a last trade price exists and it is <= its `trigger`. After each event has been completely processed (whatever its type), repeat the following: if at least one dormant stop can trigger, take the one that was submitted first, remove it from the dormant set and process it as a MARKET order with the same id, side and quantity; then check again (the last trade price may have changed). When no dormant stop can trigger, move on to the next event. Stop orders are never checked in the middle of matching an order. (So a stop order can trigger right after its own STOP event if the last trade price already satisfies it.)

**Result.** Return an object with exactly these keys:
- `trades`: all trades in the order they happened, each `[buyId, sellId, price, qty]`;
- `bids`: one `[price, total]` pair for every price at which at least one buy order rests at the end, highest price first, where `total` is the sum of `disp` of the resting buy orders at that price;
- `asks`: the same for resting sell orders, lowest price first;
- `stops`: the ids of the stop orders still dormant at the end, in the order they were submitted.

Examples:
{examples}

{trailer}
