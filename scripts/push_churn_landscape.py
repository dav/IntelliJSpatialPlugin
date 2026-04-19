#!/usr/bin/env python3
"""
Build a churn landscape timeline from a git repo and push it into the running
IntelliJ IDE via the JetBrains MCP server.

The IDE side (Spatial plugin) owns the squarified-treemap layout and the
time-scrub slider; this script only supplies per-file numbers per time bucket.

Usage:
  scripts/push_churn_landscape.py /path/to/repo
  scripts/push_churn_landscape.py /path/to/repo --frames 8 --top 50
  scripts/push_churn_landscape.py /path/to/repo --since 2025-01-01
  scripts/push_churn_landscape.py /path/to/repo --narrate "Tour the project's hot files."

Env / flags:
  --port      MCP HTTP port (default: 64342, matches Settings → Tools → MCP Server)
  --frames    Number of equal-width time buckets (default: 6)
  --top       Keep the top K files by total churn across all frames (default: 40)
  --since     ISO date for the timeline start (default: first commit)
  --until     ISO date for the timeline end   (default: HEAD)
  --narrate   Optional sentence to speak via spatial_narrate after pushing
"""

import argparse
import json
import re
import subprocess
import sys
import urllib.request
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path

RENAME_RE = re.compile(r"^(?P<prefix>.*?)\{(?P<old>[^{}]*) => (?P<new>[^{}]*)\}(?P<suffix>.*)$")


def run(cmd: list[str], cwd: Path) -> str:
    return subprocess.check_output(cmd, cwd=str(cwd), text=True, errors="replace")


def normalize_path(raw: str) -> str | None:
    """Resolve git's `{old => new}` rename syntax to the new path."""
    m = RENAME_RE.match(raw)
    if not m:
        return raw
    pre, _old, new, suf = m.group("prefix"), m.group("old"), m.group("new"), m.group("suffix")
    return f"{pre}{new}{suf}".replace("//", "/").lstrip("/")


def parse_iso_date(s: str) -> datetime:
    return datetime.fromisoformat(s).replace(tzinfo=timezone.utc) if "T" not in s and "+" not in s \
        else datetime.fromisoformat(s)


def commit_dates_range(repo: Path) -> tuple[datetime, datetime]:
    first = run(["git", "log", "--reverse", "--pretty=format:%cI", "--max-count=1"], repo).strip()
    last = run(["git", "log", "--pretty=format:%cI", "--max-count=1"], repo).strip()
    if not first or not last:
        sys.exit("no commits in repo")
    return datetime.fromisoformat(first), datetime.fromisoformat(last)


def churn_for_window(repo: Path, since: datetime, until: datetime) -> dict[str, int]:
    """{path → insertions+deletions} over a single time window."""
    out = run([
        "git", "log",
        "--no-merges",
        f"--since={since.isoformat()}",
        f"--until={until.isoformat()}",
        "--numstat",
        "--pretty=tformat:",
    ], repo)
    totals: dict[str, int] = defaultdict(int)
    for line in out.splitlines():
        line = line.strip()
        if not line:
            continue
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        ins, dele, raw_path = parts
        if ins == "-" or dele == "-":
            continue  # binary file
        path = normalize_path(raw_path)
        if not path:
            continue
        try:
            totals[path] += int(ins) + int(dele)
        except ValueError:
            continue
    return totals


def loc_at_head(repo: Path) -> dict[str, int]:
    """{path → line count at HEAD} for every text file."""
    listing = run(["git", "ls-files"], repo).splitlines()
    locs: dict[str, int] = {}
    for path in listing:
        try:
            content = run(["git", "show", f"HEAD:{path}"], repo)
        except subprocess.CalledProcessError:
            continue
        # Skip likely-binary files (heuristic: NUL bytes in first KB).
        if "\0" in content[:1024]:
            continue
        locs[path] = max(1, content.count("\n"))
    return locs


def build_frames(
    repo: Path,
    n_frames: int,
    since: datetime | None,
    until: datetime | None,
    top_k: int,
) -> list[dict]:
    first, last = commit_dates_range(repo)
    start = since or first
    end = until or last
    if end < start:
        sys.exit(f"end ({end}) must be on or after start ({start})")

    if end == start:
        # Single-second repos / pathological --since == --until. Pad so buckets have width.
        end = end + timedelta(seconds=1)
    bucket = (end - start) / n_frames
    if bucket < timedelta(seconds=1):
        print(
            f"warning: bucket width is {bucket} (less than 1s). git's --since/--until "
            f"rounds to seconds, so all frames will return identical commits and the "
            f"scrub slider will look frozen. Try a repo with more history, or fewer frames.",
            file=sys.stderr,
        )
    locs = loc_at_head(repo)

    # Pick a label format that distinguishes frames at the bucket's granularity.
    if bucket >= timedelta(days=2):
        label_fmt = "%Y-%m-%d"
    elif bucket >= timedelta(hours=1):
        label_fmt = "%Y-%m-%d %H:00"
    else:
        label_fmt = "%H:%M:%S"

    raw_frames: list[tuple[str, dict[str, int]]] = []
    for i in range(n_frames):
        win_start = start + bucket * i
        win_end = start + bucket * (i + 1)
        churn = churn_for_window(repo, win_start, win_end)
        label = f"{win_start.strftime(label_fmt)} → {win_end.strftime(label_fmt)}"
        raw_frames.append((label, churn))

    # Pick the top K paths by churn-summed-across-frames AND that still exist at HEAD.
    totals: dict[str, int] = defaultdict(int)
    for _label, frame in raw_frames:
        for p, v in frame.items():
            totals[p] += v
    eligible = [p for p in totals if p in locs]
    eligible.sort(key=lambda p: totals[p], reverse=True)
    keep = set(eligible[:top_k])
    if not keep:
        sys.exit("no eligible files (try --since/--until or a different repo)")

    frames_out = []
    for label, frame in raw_frames:
        entries = []
        for path in keep:
            entries.append({
                "path": path,
                "loc": float(locs[path]),
                "churn": float(frame.get(path, 0)),
            })
        frames_out.append({"label": label, "entries": entries})
    return frames_out


# ---------- MCP client over Streamable HTTP ----------

def mcp_call(port: int, name: str, arguments: dict) -> dict:
    url = f"http://127.0.0.1:{port}/stream"

    def post(payload: dict, session: str | None) -> tuple[dict | None, str | None]:
        data = json.dumps(payload).encode()
        req = urllib.request.Request(
            url, data=data, method="POST",
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json, text/event-stream",
                **({"Mcp-Session-Id": session} if session else {}),
            },
        )
        with urllib.request.urlopen(req, timeout=15) as resp:
            sess = resp.headers.get("Mcp-Session-Id") or resp.headers.get("mcp-session-id") or session
            body = resp.read().decode()
        if not body.strip():
            return None, sess
        # Streamable HTTP can return JSON or SSE-framed JSON. Take the last data: line if SSE.
        if body.startswith("event:") or "\ndata:" in body:
            for line in body.splitlines():
                if line.startswith("data:"):
                    payload = line[len("data:"):].strip()
                    if payload:
                        try:
                            return json.loads(payload), sess
                        except json.JSONDecodeError:
                            pass
            return None, sess
        return json.loads(body), sess

    init = {
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {
            "protocolVersion": "2025-03-26",
            "capabilities": {},
            "clientInfo": {"name": "push-churn-landscape", "version": "0"},
        },
    }
    _r, session = post(init, None)
    post({"jsonrpc": "2.0", "method": "notifications/initialized"}, session)
    call = {
        "jsonrpc": "2.0", "id": 2, "method": "tools/call",
        "params": {"name": name, "arguments": arguments},
    }
    response, _ = post(call, session)
    return response or {}


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("repo", type=Path, help="path to the git repo")
    ap.add_argument("--port", type=int, default=64342)
    ap.add_argument("--frames", type=int, default=6)
    ap.add_argument("--top", type=int, default=40)
    ap.add_argument("--since", type=str)
    ap.add_argument("--until", type=str)
    ap.add_argument("--floor-size", type=float, default=22.0)
    ap.add_argument("--max-height", type=float, default=7.0)
    ap.add_argument("--narrate", type=str, default=None)
    ap.add_argument("--dry-run", action="store_true", help="print the timeline JSON without pushing")
    args = ap.parse_args()

    repo = args.repo.resolve()
    if not (repo / ".git").exists() and not (repo / "HEAD").exists():
        sys.exit(f"{repo} doesn't look like a git repo")

    since = parse_iso_date(args.since) if args.since else None
    until = parse_iso_date(args.until) if args.until else None
    frames = build_frames(repo, args.frames, since, until, args.top)

    timeline = {"frames": frames, "floorSize": args.floor_size, "maxHeight": args.max_height}

    if args.dry_run:
        print(json.dumps(timeline, indent=2))
        return

    print(f"pushing {len(frames)} frames, {len(frames[0]['entries'])} files each → IDE on :{args.port}")
    result = mcp_call(args.port, "spatial_push_churn_landscape", timeline)
    print(json.dumps(result, indent=2))

    if args.narrate:
        mcp_call(args.port, "spatial_narrate", {"text": args.narrate})


if __name__ == "__main__":
    main()
