"""Bound a build's disk usage and lifetime; never delete files to recover space."""
import argparse
import os
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path

GIB = 1024 ** 3
MIN_FREE = 15 * GIB


def free_bytes(path):
    return shutil.disk_usage(path).free


def stop_group(process, grace: float = 15):
    # Kill the group even when its leader exited but left Gradle/tool children.
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        process.wait(timeout=grace)
    except subprocess.TimeoutExpired:
        pass
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait()


def run_guarded(command, path, *, minimum=MIN_FREE, interval: float = 5,
                timeout=None, tick=None, probe=free_bytes, grace: float = 15):
    """Return (exit code, reason). tick receives a fresh heartbeat every interval."""
    reason = None
    previous = {}
    process = None
    start = time.monotonic()

    def interrupted(signum, _frame):
        nonlocal reason
        reason = f"signal_{signum}"

    try:
        for sig in (signal.SIGTERM, signal.SIGINT):
            previous[sig] = signal.signal(sig, interrupted)
        while True:
            free = probe(path)
            if free < minimum:
                reason = "low_disk"
            if timeout is not None and time.monotonic() - start >= timeout:
                reason = reason or "time_limit"
            if tick:
                tick({"pid": os.getpid(), "child_pid": process.pid if process else None,
                      "free_bytes": free, "minimum_free_bytes": minimum,
                      "updated_at_epoch": time.time(), "reason": reason})
            if reason:
                if process:
                    stop_group(process, grace)
                code = 75 if reason == "low_disk" else 124 if reason == "time_limit" else 143
                return code, reason
            if process is None:
                process = subprocess.Popen(command, start_new_session=True)
            result = process.poll()
            if result is not None:
                stop_group(process, grace)
                return result, "exited"
            time.sleep(interval)
    finally:
        if process and process.poll() is None:
            stop_group(process, grace)
        for sig, handler in previous.items():
            signal.signal(sig, handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--path", type=Path, required=True)
    parser.add_argument("--timeout", type=float)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("a command is required after --")
    code, reason = run_guarded(command, args.path, timeout=args.timeout)
    if reason != "exited":
        print(f"Build stopped: {reason}; at least 15 GiB free is required. No files deleted.", file=sys.stderr)
    return code


if __name__ == "__main__":
    sys.exit(main())
