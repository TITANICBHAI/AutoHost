# Web Login Scrape
The Gate 10-a reference: logs into a **fully local** page (`task:web/login.html` lives inside
this task's own bundle — the run needs no network grant at all) and scrapes the signed-in
status. The profile secret `demo_password` fills the form; the browser surface is the visible
in-app WebView (Close button + back = stop). `web/challenge.html` is the Gate 10-c fixture: if
the task opens it, the SDK raises `NeedsUser(SECURITY_CHALLENGE)` and the run pauses — the user
solves the wall, we never touch it. `profile_schema.secret_fields` keeps the password out of
backups; the SDK never echoes typed text back.
