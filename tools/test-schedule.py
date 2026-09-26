#!/usr/bin/env python3
"""
Write a schedule whose events fire a few minutes from now.

For the on-device alarm checks in PLAN.md milestone M3. Blocks alternate
between two profiles so each firing visibly changes MEDIA and RING, and
leave NOTIFICATION and ALARM alone.

    python3 tools/test-schedule.py > test-schedule.json
    adb push test-schedule.json /sdcard/Download/
    # then Import in the app and pick it from Downloads

Times come from this computer's clock, so it and the phone must be in the
same time zone. Import reconciles immediately, which applies the *last*
block's levels (it is the most recent one, a week ago). With an even
--count the first firing is therefore a visible change.
"""

import argparse
import json
import sys
from datetime import datetime, timedelta

# Index matches datetime.weekday(). Not strftime("%A"), which is locale-dependent.
DAYS = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"]
MINUTES_PER_WEEK = 7 * 24 * 60

# Low enough to be under any device maximum, and ring stays above the floor of 1.
PROFILES = [
    {"name": "Test A", "slots": {"MEDIA": {"type": "raw", "level": 4}, "RING": {"type": "raw", "level": 2}}},
    {"name": "Test B", "slots": {"MEDIA": {"type": "raw", "level": 8}, "RING": {"type": "raw", "level": 4}}},
]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--start", type=int, default=3, help="minutes from now to the first event (default 3)")
    parser.add_argument("--every", type=int, default=2, help="minutes between events (default 2)")
    parser.add_argument("--count", type=int, default=6, help="number of events (default 6)")
    args = parser.parse_args()

    if args.start < 1 or args.every < 1 or args.count < 1:
        parser.error("--start, --every and --count must all be at least 1")
    if args.every * args.count > MINUTES_PER_WEEK:
        parser.error("events would wrap past a week and collide")

    first = datetime.now().replace(second=0, microsecond=0) + timedelta(minutes=args.start)
    times = [first + timedelta(minutes=args.every * i) for i in range(args.count)]

    schedule = {
        "version": 1,
        "enabled": True,
        "presets": {},
        "profiles": PROFILES,
        "blocks": [
            {
                "day": DAYS[t.weekday()],
                "start": t.strftime("%H:%M"),
                "durationMin": args.every,
                "target": {"type": "profile", "name": PROFILES[i % 2]["name"]},
            }
            for i, t in enumerate(times)
        ],
    }

    json.dump(schedule, sys.stdout, indent=2)
    print()
    print(
        f"{args.count} events, {DAYS[times[0].weekday()]} {times[0]:%H:%M} to "
        f"{DAYS[times[-1].weekday()]} {times[-1]:%H:%M}, every {args.every} min",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
