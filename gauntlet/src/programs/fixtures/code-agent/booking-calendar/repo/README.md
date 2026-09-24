# booking-calendar

Meeting-room bookings for one office: rooms with opening hours and a capacity,
single and weekly recurring bookings, and a free-slot finder.

```js
const { createCalendar } = require('./src');

const cal = createCalendar();
cal.rooms.add({ id: 'oak', name: 'Oak Room', capacity: 6, open: '08:00', close: '18:00' });

cal.book({ room: 'oak', date: '2024-10-01', start: '10:00', duration: '1h30m', title: 'Planning', attendees: 4 });
cal.freeSlots('oak', '2024-10-01');
// [{ start: '08:00', end: '10:00' }, { start: '11:30', end: '18:00' }]
```

## Rules

* Times are `HH:MM` (24-hour clock) on a `YYYY-MM-DD` date. Durations are minutes (`90`) or text
  (`'1h30m'`, `'45m'`, `'2h'`). **When no duration is given, a booking lasts 60 minutes.**
* A booking occupies the half-open interval `[start, end)`: a meeting from 10:00 to 11:00 and another
  from 11:00 to 12:00 do **not** overlap, so back-to-back bookings are allowed.
* A booking must start at or after the room opens, end at or before it closes, end after it starts,
  have no more attendees than the room's capacity, and not overlap another booking of the same room
  on the same date. Otherwise `book` throws a `BookingError` listing every problem.
* `book(input)` returns a new booking object `{ id, room, date, start, end, title, attendees }` with
  `start`/`end` as `HH:MM`. It never modifies `input`, and later changes to `input` never change the
  stored booking.
* `bookRecurring(template, weeks)` books the same slot on `weeks` consecutive weeks starting at
  `template.date`. It is all-or-nothing: if any week has a problem, nothing is booked. The template is
  not modified.
* `freeSlots(room, date, minMinutes = 30)` lists the free intervals of at least `minMinutes` within
  opening hours, in order.

## Development

```
node --test
```
