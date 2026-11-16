#!/usr/bin/env python3
"""
Build offline GridMaster snapshots for Look4Sat.

The public grids endpoint supplies percentages in one request per award. The
single-grid endpoint supplies the latest activators, so this script fetches
only grids that GridMaster says have been worked. Progress is append-only and
resumable; the shared-IP API limit is respected with a persistent token bucket.

Run from the repository root:
  python scripts/gridmaster_snapshot.py
  python scripts/gridmaster_snapshot.py --awards sat
"""
from __future__ import annotations

import argparse
import email.utils
import gzip
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

API_ROOT = "https://gridmaster.fr/api"
SOURCE_URL = "https://gridmaster.fr"
DEFAULT_AWARDS = ("sat", "sat/leo", "sat/meo", "sat/geo", "sat/ngs")
GRID_RE = re.compile(r"^[A-R]{2}[0-9]{2}$")
TOKEN_CAPACITY = 80.0
TOKEN_REFILL_SECONDS = 36.0
MAX_RESPONSE_BYTES = 4 * 1024 * 1024
USER_AGENT = "Look4Sat-GridMaster-Snapshot/1.0"


def award_slug(award: str) -> str:
    return award.replace("/", "_")


def normalize_grid(value: Any) -> str | None:
    grid = str(value or "").strip().upper()
    return grid if GRID_RE.fullmatch(grid) else None


def all_four_char_grids() -> list[str]:
    """Return all 18×18×10×10 = 32,400 valid Maidenhead 4-character squares."""
    letters = "ABCDEFGHIJKLMNOPQR"
    return [
        field_lon + field_lat + str(square_lon) + str(square_lat)
        for field_lon in letters
        for field_lat in letters
        for square_lon in range(10)
        for square_lat in range(10)
    ]


def parse_retry_after(value: str | None) -> float:
    if not value:
        return TOKEN_REFILL_SECONDS
    try:
        return max(1.0, float(value.strip()))
    except ValueError:
        pass
    try:
        when = email.utils.parsedate_to_datetime(value)
        if when.tzinfo is None:
            from datetime import timezone
            when = when.replace(tzinfo=timezone.utc)
        return max(1.0, when.timestamp() - time.time())
    except (TypeError, ValueError, OverflowError):
        return TOKEN_REFILL_SECONDS


class RequestBudget:
    """Persistent conservative token bucket shared by all awards in this run."""

    def __init__(self, state_file: Path) -> None:
        self.state_file = state_file
        self.state_file.parent.mkdir(parents=True, exist_ok=True)
        self.state = self._load()

    def _load(self) -> dict[str, float]:
        now = time.time()
        if not self.state_file.is_file():
            return {"tokens": TOKEN_CAPACITY, "updated_at": now, "blocked_until": 0.0}
        try:
            raw = json.loads(self.state_file.read_text(encoding="utf-8"))
            return {
                "tokens": min(TOKEN_CAPACITY, max(0.0, float(raw.get("tokens", 0.0)))),
                "updated_at": min(now, float(raw.get("updated_at", now))),
                "blocked_until": max(0.0, float(raw.get("blocked_until", 0.0))),
            }
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            # A damaged state file must not reset the local burst to a full bucket.
            return {"tokens": 0.0, "updated_at": now, "blocked_until": 0.0}

    def _save(self) -> None:
        temporary = self.state_file.with_suffix(self.state_file.suffix + ".tmp")
        temporary.write_text(json.dumps(self.state), encoding="utf-8")
        os.replace(temporary, self.state_file)

    def acquire(self) -> None:
        while True:
            now = time.time()
            elapsed = max(0.0, now - self.state["updated_at"])
            self.state["tokens"] = min(
                TOKEN_CAPACITY,
                self.state["tokens"] + elapsed / TOKEN_REFILL_SECONDS,
            )
            self.state["updated_at"] = now
            blocked_wait = max(0.0, self.state["blocked_until"] - now)
            token_wait = max(0.0, (1.0 - self.state["tokens"]) * TOKEN_REFILL_SECONDS)
            wait = max(blocked_wait, token_wait)
            if wait <= 0.0:
                self.state["tokens"] -= 1.0
                self._save()
                return
            self._save()
            print(
                f"API budget exhausted; waiting {wait:.0f}s "
                f"(about {wait / 60:.1f} min) before the next request.",
                flush=True,
            )
            time.sleep(wait)

    def rate_limited(self, retry_after: float) -> None:
        now = time.time()
        self.state["tokens"] = 0.0
        self.state["updated_at"] = now
        self.state["blocked_until"] = max(
            self.state["blocked_until"], now + retry_after
        )
        self._save()


def request_json(url: str, budget: RequestBudget) -> dict[str, Any]:
    for attempt in range(1, 7):
        budget.acquire()
        request = urllib.request.Request(
            url,
            headers={
                "Accept": "application/json",
                "Accept-Encoding": "gzip",
                "User-Agent": USER_AGENT,
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=25) as response:
                raw = response.read(MAX_RESPONSE_BYTES + 1)
                if len(raw) > MAX_RESPONSE_BYTES:
                    raise RuntimeError(f"Response is too large: {url}")
                if response.headers.get("Content-Encoding", "").lower() == "gzip":
                    raw = gzip.decompress(raw)
                if len(raw) > MAX_RESPONSE_BYTES:
                    raise RuntimeError(f"Decompressed response is too large: {url}")
                payload = json.loads(raw.decode("utf-8"))
                if not isinstance(payload, dict):
                    raise RuntimeError(f"Expected a JSON object: {url}")
                return payload
        except urllib.error.HTTPError as error:
            if error.code == 429:
                retry = parse_retry_after(error.headers.get("Retry-After"))
                budget.rate_limited(retry)
                print(
                    f"GridMaster returned 429; respecting Retry-After ({retry:.0f}s).",
                    flush=True,
                )
                continue
            if error.code in (408, 500, 502, 503, 504) and attempt < 6:
                delay = min(300.0, 5.0 * (2 ** (attempt - 1)))
                print(f"HTTP {error.code}; retrying in {delay:.0f}s.", flush=True)
                time.sleep(delay)
                continue
            raise RuntimeError(f"GridMaster HTTP {error.code}: {url}") from error
        except (urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError) as error:
            if attempt >= 6:
                raise RuntimeError(
                    f"Request failed after {attempt} attempts: {url}: {error}"
                ) from error
            delay = min(300.0, 5.0 * (2 ** (attempt - 1)))
            print(
                f"Network/JSON error ({error}); retrying in {delay:.0f}s.",
                flush=True,
            )
            time.sleep(delay)
    raise RuntimeError(f"Could not fetch {url}")


def fetch_award_index(
    award: str, budget: RequestBudget
) -> tuple[dict[str, int], dict[str, Any]]:
    query = urllib.parse.urlencode({"award": award, "format": "list"})
    payload = request_json(f"{API_ROOT}/grids.json?{query}", budget)
    award_info = payload.get("award")
    if isinstance(award_info, dict) and award_info.get("code") not in (None, award):
        raise RuntimeError(f"GridMaster returned the wrong award for {award}")
    updated = str(payload.get("updated", "")).strip()
    worked = payload.get("worked")
    if not updated or not isinstance(worked, list):
        raise RuntimeError(f"Invalid grids.json response for award {award}")
    percentages: dict[str, int] = {}
    for row in worked:
        if not isinstance(row, dict):
            continue
        grid = normalize_grid(row.get("grid"))
        if grid is None:
            continue
        try:
            percent = int(row.get("percent"))
        except (TypeError, ValueError):
            continue
        if 0 <= percent <= 100:
            percentages[grid] = percent
    if not percentages:
        raise RuntimeError(f"GridMaster returned no worked grids for award {award}")
    metadata = {
        "updated": updated,
        "operators": int(payload.get("operators", 0) or 0),
        "grid_count": len(percentages),
    }
    return percentages, metadata


def clean_operators(value: Any) -> list[dict[str, str | None]]:
    if not isinstance(value, list):
        return []
    operators: list[dict[str, str | None]] = []
    for row in value:
        if not isinstance(row, dict):
            continue
        call = str(row.get("call") or "").strip()
        if not call or call.lower() == "null":
            continue
        date = str(row.get("date") or "").strip()
        operators.append(
            {"call": call, "date": date if date and date.lower() != "null" else None}
        )
        if len(operators) == 6:
            break
    return operators


def read_progress(path: Path) -> dict[str, list[dict[str, str | None]]]:
    result: dict[str, list[dict[str, str | None]]] = {}
    if not path.is_file():
        return result
    with path.open("r", encoding="utf-8") as stream:
        for line in stream:
            try:
                row = json.loads(line)
                grid = normalize_grid(row.get("grid"))
                if grid is not None:
                    result[grid] = clean_operators(row.get("last"))
            except (json.JSONDecodeError, AttributeError, TypeError):
                # Ignore a truncated final line; the missing grid will be fetched again.
                continue
    return result


def write_snapshot(
    output_dir: Path,
    award: str,
    percentages: dict[str, int],
    metadata: dict[str, Any],
    latest_calls: dict[str, list[dict[str, str | None]]],
) -> Path:
    slug = award_slug(award)
    output_dir.mkdir(parents=True, exist_ok=True)
    target = output_dir / f"gridmaster_{slug}_snapshot.json.gz"
    payload = {
        "schema_version": 1,
        "source": SOURCE_URL,
        "award": award,
        "updated": metadata["updated"],
        "operators": metadata["operators"],
        "grid_count": len(all_four_char_grids()),
        "worked_grid_count": len(percentages),
        "percentages": dict(sorted(percentages.items())),
        # Unworked squares are known to have 0% and no recent activators. Store them
        # locally as checked-empty entries so the app does not query 27k blank grids.
        "latest_calls": {
            grid: latest_calls.get(grid, [])
            for grid in all_four_char_grids()
        },
    }
    raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    temporary = target.with_suffix(target.suffix + ".tmp")
    with temporary.open("wb") as raw_file:
        with gzip.GzipFile(
            fileobj=raw_file, mode="wb", compresslevel=9, mtime=0
        ) as compressed:
            compressed.write(raw)
    os.replace(temporary, target)
    return target


def main() -> int:
    parser = argparse.ArgumentParser(description="Build resumable offline GridMaster snapshots.")
    parser.add_argument(
        "--awards",
        default=",".join(DEFAULT_AWARDS),
        help="Comma-separated award codes (default: all five Look4Sat satellite awards).",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path(__file__).resolve().parents[1]
        / "feature/map/src/main/assets/gridmaster/snapshots",
        help="Destination for the generated .json.gz Android assets.",
    )
    parser.add_argument(
        "--state-dir",
        type=Path,
        default=Path.home() / ".cache" / "look4sat-gridmaster",
        help="Persistent progress and rate-limit state directory.",
    )
    parser.add_argument(
        "--plan-only",
        action="store_true",
        help="Fetch award indexes and print the request/time estimate without fetching grid details.",
    )
    args = parser.parse_args()

    awards = tuple(dict.fromkeys(part.strip() for part in args.awards.split(",") if part.strip()))
    unknown = sorted(set(awards) - set(DEFAULT_AWARDS))
    if not awards or unknown:
        parser.error(f"Unsupported award code(s): {', '.join(unknown) or '(none)'}")

    args.state_dir.mkdir(parents=True, exist_ok=True)
    budget = RequestBudget(args.state_dir / "request_budget.json")

    # Fetch the small award indexes first, then calculate the full job size.
    indexes: dict[str, tuple[dict[str, int], dict[str, Any]]] = {}
    for award in awards:
        print(f"Fetching worked-grid index for {award} ...", flush=True)
        indexes[award] = fetch_award_index(award, budget)
        percentages, metadata = indexes[award]
        print(
            f"  {metadata['updated']}: {len(percentages):,} worked grids "
            f"from {metadata['operators']:,} operators.",
            flush=True,
        )

    detail_requests = sum(len(indexes[award][0]) for award in awards)
    total_requests = detail_requests + len(awards)
    delayed_requests = max(0, total_requests - int(TOKEN_CAPACITY))
    estimated_hours = delayed_requests * TOKEN_REFILL_SECONDS / 3600.0
    print(
        f"\nPlan: {len(awards)} index requests + {detail_requests:,} grid-detail requests "
        f"= {total_requests:,} API requests.",
        flush=True,
    )
    print(
        f"Conservative rate-limit estimate: about {estimated_hours:.1f} hours "
        f"after an otherwise-full local burst of {int(TOKEN_CAPACITY)} requests. "
        "Other clients on the same public IP can extend this.",
        flush=True,
    )
    if args.plan_only:
        return 0

    for award in awards:
        percentages, metadata = indexes[award]
        slug = award_slug(award)
        progress_file = args.state_dir / f"{slug}_{metadata['updated']}.jsonl"
        completed = read_progress(progress_file)
        grids = sorted(percentages)
        missing = [grid for grid in grids if grid not in completed]
        print(
            f"\n[{award}] Resuming {len(completed):,}/{len(grids):,}; "
            f"{len(missing):,} grid details remain.",
            flush=True,
        )

        with progress_file.open("a", encoding="utf-8") as progress:
            for index, grid in enumerate(missing, start=1):
                query = urllib.parse.urlencode({"grid": grid, "award": award})
                detail = request_json(f"{API_ROOT}/grid.json?{query}", budget)
                returned_grid = normalize_grid(detail.get("grid"))
                if returned_grid != grid or not isinstance(detail.get("last"), list):
                    raise RuntimeError(f"Unexpected grid.json response for {award}/{grid}")
                operators = clean_operators(detail["last"])
                progress.write(
                    json.dumps(
                        {"grid": grid, "last": operators},
                        ensure_ascii=False,
                        separators=(",", ":"),
                    )
                    + "\n"
                )
                progress.flush()
                if index % 10 == 0:
                    os.fsync(progress.fileno())
                completed[grid] = operators
                if index == 1 or index % 25 == 0 or index == len(missing):
                    print(
                        f"  {grid}: {len(operators)} recent activators "
                        f"({index:,}/{len(missing):,} new grids this run).",
                        flush=True,
                    )

        if any(grid not in completed for grid in grids):
            raise RuntimeError(f"Snapshot for {award} is incomplete; rerun to resume.")
        target = write_snapshot(args.output_dir, award, percentages, metadata, completed)
        print(f"  Wrote {target} ({target.stat().st_size / 1024:.1f} KiB).", flush=True)

    print("\nAll requested GridMaster snapshots are complete.", flush=True)
    print("The app will use these as its first-install offline cache; online daily refresh remains enabled.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("\nInterrupted. Progress is saved; rerun the same command to resume.", file=sys.stderr)
        raise SystemExit(130)
