"""autohost CLI (P2-23 v0, extended in P5-12) —
push · run · logs · ui dump/try · doctor · status · pull-run · screenshot ·
enable-shell · grant-secure-settings · docs --pack · mcp.

Exit codes: 0 ok · 1 error · 127 doctor found major issues.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time

from autohost_cli.client import DevApiClient, DevApiError


def cmd_status(client: DevApiClient, _args: argparse.Namespace) -> int:
    print(json.dumps(client.system_info(), indent=2))
    return 0


def cmd_push(client: DevApiClient, args: argparse.Namespace) -> int:
    result = client.push(args.dir)
    print("installed:", result.get("taskId", "?"), "version", result.get("version", "?"))
    return 0


def cmd_run(client: DevApiClient, args: argparse.Namespace) -> int:
    result = client.run(args.task, args.profile)
    run_id = result.get("runId", "?")
    print("started run", run_id)
    if not args.follow:
        return 0
    since = 0
    deadline = time.time() + args.timeout
    while time.time() < deadline:
        status = client.status(run_id)
        tail = client.logs(run_id, since_seq=since)
        for event in tail.get("events", []):
            since = max(since, int(event.get("seq", 0)))
            stage = event.get("stage") or "-"
            print(f"{event.get('ts', 0)} {str(event.get('level', 'info')).upper():5s} [{stage}] {event.get('message', '')}")
        if status.get("status") in {"SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED", "SKIPPED"}:
            print("run finished:", status.get("status"), status.get("reasonCode") or "")
            return 0 if status.get("status") == "SUCCEEDED" else 1
        time.sleep(1.0)
    print("timed out following run", run_id, file=sys.stderr)
    return 1


def cmd_logs(client: DevApiClient, args: argparse.Namespace) -> int:
    result = client.logs(args.run_id)
    for event in result.get("events", []):
        stage = event.get("stage") or "-"
        print(f"{event.get('ts', 0)} {str(event.get('level', 'info')).upper():5s} [{stage}] {event.get('message', '')}")
    return 0


def cmd_ui(client: DevApiClient, args: argparse.Namespace) -> int:
    if args.ui_command == "dump":
        print(json.dumps(client.ui_dump(compact=not args.full), indent=2))
        return 0
    selector = json.loads(args.selector or '{"text": ""}')
    print(json.dumps(client.ui_try(selector), indent=2))
    return 0


def cmd_doctor(client: DevApiClient, _args: argparse.Namespace) -> int:
    report = client.doctor()
    groups = report.get("groups", report)
    print(json.dumps(groups, indent=2))
    needs_fix = groups.get("needs_fix") if isinstance(groups, dict) else None
    if needs_fix:
        print("doctor: found major issues", file=sys.stderr)
        return 127
    return 0


def cmd_pull_run(client: DevApiClient, args: argparse.Namespace) -> int:
    """P5-12: fetch the .aarun failure bundle for offline debugging (plan §13.3 step 5)."""
    path = client.save_bundle(client.export_run(args.run_id), args.out)
    print("saved", path)
    return 0


def cmd_screenshot(client: DevApiClient, args: argparse.Namespace) -> int:
    result = client.screenshot(args.tag)
    print(json.dumps(result, indent=2))
    return 0


def cmd_enable_shell(_client: DevApiClient, args: argparse.Namespace) -> int:
    """P5-12: PC helper for the Android 10 shell tier (plan §13.3 step 10). Prints, never executes."""
    port = args.port
    print("Enable the ADB-over-TCP shell tier (PC-assisted, per boot):")
    print(f"  1. adb tcpip {port}")
    print(f"  2. adb connect <phone-ip>:{port}   # find the IP on the phone: Settings → About → Status")
    print("  3. Approve the host key prompt on the phone if one appears.")
    print("  4. In AutoHost: Settings → Developer → Shell tier = ADB-over-TCP.")
    print("The tier resets at reboot; repeat after each boot (Android 10 has no wireless debugging).")
    return 0


def cmd_grant_secure_settings(_client: DevApiClient, _args: argparse.Namespace) -> int:
    """P5-12: one-time WRITE_SECURE_SETTINGS grant that enables boot self-heal (plan §13.2)."""
    print("One-time grant (no root needed) — run exactly this on the PC while the phone is connected:")
    print("  adb shell pm grant app.autohost android.permission.WRITE_SECURE_SETTINGS")
    print("Then on the phone: Setup → turn on 'Re-enable accessibility after reboot'.")
    print("What it changes: AutoHost may write ENABLED_ACCESSIBILITY_SERVICES / secure-settings IME keys")
    print("so boot self-heal can restore the accessibility service without manual steps.")
    return 0


def cmd_docs(client: DevApiClient, args: argparse.Namespace) -> int:
    """P5-16: write the Skill-format agent context pack (plan §13.3 step 8)."""
    from autohost_cli.pack import write_pack

    device_info: dict = {}
    try:
        device_info = client.system_info()
    except DevApiError:
        print("note: device unreachable; writing the pack without live availability", file=sys.stderr)
    path = write_pack(args.out, device_info=device_info)
    print("agent pack written to", path)
    return 0


def cmd_mcp(client: DevApiClient, _args: argparse.Namespace) -> int:
    from autohost_cli.mcp_server import main as mcp_main

    return mcp_main(["--port", str(_args.port), "--token", _args.token or ""])


def cmd_ui_web(client: DevApiClient, args: argparse.Namespace) -> int:
    """P11-06: the PC web inspector (S27) — localhost page + token-safe proxy."""
    from autohost_cli.webui import serve

    return serve(client, port=args.web_port, devapi_port=args.port)


def cmd_bridge(_client: DevApiClient, args: argparse.Namespace) -> int:
    """P11-07 (ADR-081): the PC-side reverse listener + localhost HTTP proxy."""
    from autohost_cli.bridge import run as bridge_run

    return bridge_run(args.listen, args.http, args.token or "")


def cmd_repo(client: DevApiClient, args: argparse.Namespace) -> int:
    """P11-03 (part 2): parse an index and show/install entries."""
    from autohost_cli import repo as repo_mod

    raw = repo_mod.fetch(args.index)
    parsed = repo_mod.parse(raw.decode("utf-8"))
    if not parsed.ok:
        for issue in parsed.issues:
            print(f"issue {issue.describe()}", file=sys.stderr)
        return 1
    index = parsed.index
    print(f"repo: {index.name or args.index} ({len(index.entries)} entries, updated {index.updated_at or 'unknown'})")
    for issue in parsed.issues:
        print(f"  note {issue.describe()}")
    # Installed versions come from the device (tasks.list), so the verdicts are real ones.
    installed: dict[str, str] = {}
    try:
        for row in client.tasks_list():
            installed[row.get("id", "")] = row.get("version", "")
    except DevApiError as exc:
        print(f"  note: cannot read installed tasks ({exc.code}) — verdicts assume nothing installed", file=sys.stderr)
    info = client.system_info() if args.host_api is None else {"api": args.host_api}
    host_api = int(info.get("api") or 0) or 8  # unknown host api → permissive (same floor as StudioController.HOST_API)
    exit_code = 0
    for entry in index.entries:
        verdict, message = repo_mod.select(entry, installed.get(entry.id), host_api)
        print(f"  {entry.id} {entry.version}  [{verdict}]" + (f" {message}" if message else ""))
        if args.install and entry.id in args.install:
            if verdict in {"INSTALL", "UPGRADE"}:
                try:
                    installed_id = repo_mod.install_entry(entry, client)
                    print(f"    installed {installed_id}")
                except Exception as exc:
                    print(f"    install failed: {exc}", file=sys.stderr)
                    exit_code = 1
            else:
                print(f"    skipped ({verdict})")
    return exit_code


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="autohost", description="AutoHost Dev API CLI")
    parser.add_argument("--port", type=int, default=8080, help="Dev API port (default 8080)")
    parser.add_argument("--token", default=os.environ.get("AUTOHOST_TOKEN"), help="Dev API token (or AUTOHOST_TOKEN env)")
    sub = parser.add_subparsers(dest="command", required=True)

    status = sub.add_parser("status", help="device + host info")
    status.set_defaults(fn=cmd_status)

    push = sub.add_parser("push", help="install a task bundle from a directory")
    push.add_argument("dir")
    push.set_defaults(fn=cmd_push)

    run = sub.add_parser("run", help="start a run")
    run.add_argument("task")
    run.add_argument("--profile")
    run.add_argument("-f", "--follow", action="store_true")
    run.add_argument("--timeout", type=float, default=120.0)
    run.set_defaults(fn=cmd_run)

    logs = sub.add_parser("logs", help="tail a run's events")
    logs.add_argument("--run", dest="run_id", required=True)
    logs.set_defaults(fn=cmd_logs)

    ui = sub.add_parser("ui", help="inspect the screen")
    ui.add_argument("ui_command", choices=["dump", "try"])
    ui.add_argument("--full", action="store_true")
    ui.add_argument("--selector", help="selector JSON for 'try'")
    ui.set_defaults(fn=cmd_ui)

    doctor = sub.add_parser("doctor", help="run health checks")
    doctor.set_defaults(fn=cmd_doctor)

    pull = sub.add_parser("pull-run", help="download a run's .aarun failure bundle")
    pull.add_argument("run_id")
    pull.add_argument("--out", default=".")
    pull.set_defaults(fn=cmd_pull_run)

    shot = sub.add_parser("screenshot", help="capture a screenshot")
    shot.add_argument("--tag", default=None)
    shot.set_defaults(fn=cmd_screenshot)

    shell = sub.add_parser("enable-shell", help="print the ADB-over-TCP pairing steps")
    shell.add_argument("--port", type=int, default=5555)
    shell.set_defaults(fn=cmd_enable_shell)

    grant = sub.add_parser("grant-secure-settings", help="print the one-time WRITE_SECURE_SETTINGS grant")
    grant.set_defaults(fn=cmd_grant_secure_settings)

    docs = sub.add_parser("docs", help="write the Skill-format agent pack (docs --pack DIR)")
    docs.add_argument("--pack", dest="out", required=True)
    docs.set_defaults(fn=cmd_docs)

    mcp = sub.add_parser("mcp", help="run the MCP stdio server for coding agents")
    mcp.set_defaults(fn=cmd_mcp)

    # P11-06: `autohost ui web` — the browser inspector (S27).
    ui_web = sub.add_parser("ui-web", help="serve the PC web inspector (autohost ui web)")
    ui_web.add_argument("--web-port", type=int, default=8099)
    ui_web.set_defaults(fn=cmd_ui_web)

    # P11-07: `autohost bridge` — the reverse-WS listener + localhost proxy (ADR-081).
    bridge = sub.add_parser("bridge", help="accept the phone's reverse connection and proxy the Dev API")
    bridge.add_argument("--listen", type=int, default=8090)
    bridge.add_argument("--http", type=int, default=8081)
    bridge.add_argument("--token", default=os.environ.get("AUTOHOST_TOKEN"), help="the phone's dev api token (required)")
    bridge.set_defaults(fn=cmd_bridge)

    # P11-03 (part 2): `autohost repo` — parse an index.json and install entries.
    repo = sub.add_parser("repo", help="list/install tasks from a repository index")
    repo.add_argument("--index", required=True, help="index.json URL (https or file)")
    repo.add_argument("--install", nargs="*", default=None, help="task ids to install (default: list only)")
    repo.add_argument("--host-api", type=int, default=None, help="override the host api level for min_host_api checks")
    repo.set_defaults(fn=cmd_repo)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    client = DevApiClient(port=args.port, token=args.token)
    try:
        return args.fn(client, args)
    except DevApiError as exc:
        print(f"error [{exc.code}]: {exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
