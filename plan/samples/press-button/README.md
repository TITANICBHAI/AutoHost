# Press Button (Python)
Config: `target_package`, `button_label` (+ optional variants for other languages). Add profiles for separate accounts/instances; schedule it every N minutes.
Respects the target service's rules and the user's own authorization. `min_interval_minutes` is enforced by the task itself, not just the scheduler.
Test target: the fixture app (`app.autohost.fixture`, label `Continue`).
