#!/usr/bin/env python3
"""Summarize real MangaFlow CPU frame CSVs or PresentMon CSVs (Python 3.10+).

Examples:
  py -3 scripts/windows/summarize-performance.py capture-cpu-frames.csv
  py -3 scripts/windows/summarize-performance.py capture-presentmon.csv --refresh-hz 144
  py -3 scripts/windows/summarize-performance.py before.csv after.csv --refresh-hz 144

PresentMon definitions are explicitly mapped from the upstream console documentation:
https://github.com/GameTechDev/PresentMon/blob/main/README-ConsoleApplication.md
https://github.com/GameTechDev/PresentMon/blob/v2.3.0/README-ConsoleApplication.md
https://github.com/GameTechDev/PresentMon/blob/v1.9.2/README.md
Unknown columns are retained in the schema and never guessed into timing metrics.
"""

from __future__ import annotations

import argparse
from array import array
from collections import Counter
import csv
from dataclasses import dataclass, field
import json
import math
from pathlib import Path
import sys
from typing import Any


CPU_FIELDS = {
    "start_nanos", "cpu_render_ms", "callback_interval_ms", "interactive_interval",
    "input_dispatch_to_render_ms", "display_budget_ms",
}
MISSING = {"", "na", "n/a", "nan", "null", "none", "-", "--"}
# Each entry names a documented column, not a conversion between metric meanings.
PRESENT_FIELDS = {
    "msbetweenpresents": "Interval between observed presents; includes idle gaps",
    "msbetweendisplaychange": "Previous displayed frame duration",
    "displayedtime": "Current displayed frame duration",
    "frametime": "CPU frame start to next CPU frame start (PresentMon 2.x)",
    "msbetweenappstart": "CPU frame start to next CPU frame start",
    "msinpresentapi": "Time inside Present API",
    "msuntilrendercomplete": "Present to GPU completion",
    "msrenderpresentlatency": "Present to GPU completion",
    "msuntildisplayed": "Present to display (ETW visible)",
    "displaylatency": "CPU frame start to display (ETW visible)",
    "cpubusy": "PresentMon CPU work estimate; not the Compose callback",
    "mscpubusy": "PresentMon CPU work estimate; not the Compose callback",
    "cpuwait": "PresentMon CPU wait estimate",
    "mscpuwait": "PresentMon CPU wait estimate",
    "gpulatency": "CPU frame start to GPU work start",
    "msgpulatency": "CPU frame start to GPU work start",
    "gputime": "GPU work span",
    "msgputime": "GPU work span",
    "gpubusy": "GPU active work duration; not device utilization",
    "msgpubusy": "GPU active work duration; not device utilization",
    "gpuwait": "GPU idle time within this frame's work span",
    "msgpuwait": "GPU idle time within this frame's work span",
    "videobusy": "GPU video encode/decode active duration",
    "msgpuactive": "GPU active work duration (PresentMon 1.x)",
    "msgpuvideoactive": "GPU video encode/decode active duration (PresentMon 1.x)",
    "msuntilrenderstart": "Present to GPU work start; may be negative",
    "animationerror": "Signed CPU/display cadence difference",
    "msanimationerror": "Signed CPU/display cadence difference",
    "clicktophotonlatency": "Click to display, software-visible estimate",
    "msclicktophotonlatency": "Click to display, software-visible estimate",
    "allinputtophotonlatency": "Input to display, software-visible estimate",
    "msallinputtophotonlatency": "Input to display, software-visible estimate",
    "mssinceinput": "Earliest contributing input to Present; excludes display",
}
CADENCE_FIELDS = {
    "msbetweenpresents", "msbetweendisplaychange", "displayedtime",
    "frametime", "msbetweenappstart",
}
INPUT_FIELDS = {name for name in PRESENT_FIELDS if "photon" in name} | {"mssinceinput"}
DISPLAY_FIELDS = {
    "msbetweendisplaychange", "displayedtime", "msuntildisplayed", "displaylatency",
} | (INPUT_FIELDS - {"mssinceinput"})
SIGNED_FIELDS = {"msuntilrenderstart", "animationerror", "msanimationerror"}
TIME_COLUMNS = {"timeinseconds": 1.0, "cpustarttime": 0.001, "cpustartqpctime": 0.001}


def finite_number(value: Any) -> float | None:
    if value is None or str(value).strip().casefold() in MISSING:
        return None
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    return result if math.isfinite(result) else None


@dataclass
class Runs:
    eligible: int = 0
    count: int = 0
    consecutive_runs: int = 0
    longest: int = 0
    current: int = 0

    def observe(self, over: bool | None) -> None:
        if over is not None:
            self.eligible += 1
        if over:
            self.count += 1
            self.current += 1
            if self.current == 2:
                self.consecutive_runs += 1
            self.longest = max(self.longest, self.current)
        else:
            self.current = 0

    def result(self) -> dict[str, Any]:
        return {
            "eligible_rows": self.eligible,
            "over_threshold_rows": self.count if self.eligible else None,
            "consecutive_runs_at_least_two": self.consecutive_runs if self.eligible else None,
            "longest_run": self.longest if self.eligible else None,
        }


@dataclass
class Series:
    source: str
    meaning: str
    positive_only: bool = False
    allow_negative: bool = False
    budget_multiplier: float | None = None
    values: array = field(default_factory=lambda: array("d"))
    excluded: int = 0
    missing: int = 0
    invalid: int = 0
    zero_unobserved: int = 0
    runs: Runs = field(default_factory=Runs)

    def add(self, token: Any, *, eligible: bool = True, budget: float | None = None) -> None:
        value = finite_number(token)
        if not eligible:
            self.excluded += 1
        elif token is None or str(token).strip().casefold() in MISSING:
            self.missing += 1
        elif value is None or (value < 0 and not self.allow_negative):
            self.invalid += 1
        elif self.positive_only and value == 0:
            self.zero_unobserved += 1
        else:
            self.values.append(value)
            threshold = budget * self.budget_multiplier if budget and self.budget_multiplier else None
            self.runs.observe(value > threshold if threshold is not None else None)
            return
        self.runs.observe(None)

    def result(self) -> dict[str, Any]:
        ordered = sorted(self.values)

        def percentile(fraction: float) -> float | None:
            return ordered[math.ceil(len(ordered) * fraction) - 1] if ordered else None

        result = {
            "source_column": self.source, "meaning": self.meaning, "unit": "ms",
            "count": len(ordered), "p50": percentile(0.50),
            "p95": percentile(0.95), "p99": percentile(0.99),
            "min": ordered[0] if ordered else None, "max": ordered[-1] if ordered else None,
            "missing_rows": self.missing, "invalid_rows": self.invalid,
            "excluded_rows": self.excluded, "zero_without_observation_rows": self.zero_unobserved,
        }
        if self.budget_multiplier is not None:
            result["budget_threshold"] = {"multiplier": self.budget_multiplier, **self.runs.result()}
        return result


def read_metadata(path: Path) -> dict[str, Any]:
    samples: list[dict[str, Any]] = []
    with path.open(encoding="utf-8-sig") as source:
        for number, line in enumerate(source, 1):
            if not line.strip():
                continue
            try:
                sample = json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(f"{path}:{number}: invalid JSON; capture may still be writing") from error
            if not isinstance(sample, dict) or sample.get("schema") != 1:
                raise ValueError(f"{path}:{number}: unsupported diagnostic schema")
            samples.append(sample)
    if not samples:
        raise ValueError(f"{path}: no resource samples")
    first, last = samples[0], samples[-1]
    context_fields = (
        "application_version", "java_version", "process_id", "renderer", "refresh_hz_awt",
        "dpi_scale_x", "dpi_scale_y", "window_width", "window_height", "window_visible", "render_hook",
    )
    context = {name: sorted({json.dumps(row.get(name), ensure_ascii=False) for row in samples}) for name in context_fields}
    context = {name: [json.loads(value) for value in values] for name, values in context.items()}
    counter_fields = (
        "gc_collections", "gc_collection_time_ms", "image_requests", "memory_hits", "disk_hits",
        "tile_decode_completed", "tile_decode_cancelled", "tile_reader_reuse",
    )
    deltas = {}
    for name in counter_fields:
        start, end = finite_number(first.get(name)), finite_number(last.get(name))
        observed = [value for row in samples if (value := finite_number(row.get(name))) is not None]
        reset = any(new < old for old, new in zip(observed, observed[1:]))
        deltas[name] = end - start if start is not None and end is not None and not reset and len(context["process_id"]) == 1 else None
    peaks = {}
    for name in ("process_cpu_fraction", "heap_bytes", "process_resident_bytes", "active_prefetch",
                 "active_preparations", "active_decodes", "active_background_pages", "active_enhancements",
                 "active_realtime_renderers", "tile_decode_active", "tile_retained_encoded_bytes", "edt_pending_ms"):
        values = [value for row in samples if (value := finite_number(row.get(name))) is not None]
        peaks[name] = max(values) if values else None
    overflow_values = [value for row in samples if (value := finite_number(row.get("capture_queue_overflow"))) is not None]
    latest_fields = (
        "cpu_render_ms", "interactive_callback_interval_ms", "input_dispatch_to_cpu_render_ms",
        "awt_event_age_ms", "edt_dispatch_delay_ms", "coil_decode_p95_ms", "tile_decode_p50_ms",
        "tile_decode_p95_ms", "tile_decode_p99_ms", "mpv_decoder", "mpv_video_output",
        "mpv_decoder_drops", "mpv_output_drops", "mpv_sampled_at_monotonic_ms",
    )
    return {
        "file": str(path.resolve()), "schema": 1, "samples": len(samples),
        "first_timestamp": first.get("timestamp"), "last_timestamp": last.get("timestamp"),
        "observed_context": context, "sampled_peaks": peaks, "first_to_last_counter_deltas": deltas,
        "capture_queue_overflow": max(overflow_values) if overflow_values else None,
        "last_sample_only": {name: last.get(name) for name in latest_fields},
        "notes": [
            "JSON histogram percentiles are overlapping rolling samples. They are never averaged or pooled.",
            "Sampled resource peaks may miss spikes between samples. GC collection time is not a pause histogram.",
            "Counter deltas exclude work before the first JSON sample; null means unavailable or reset.",
            "MPV drops are the last native snapshot, not automatically a capture-wide drop count.",
        ],
    }


def attach_metadata(report: dict[str, Any], path: Path) -> None:
    for suffix in ("-cpu-frames.csv", "-presentmon.csv"):
        if path.name.endswith(suffix):
            metadata_path = path.with_name(path.name[:-len(suffix)] + ".jsonl")
            if metadata_path.is_file():
                try:
                    report["resources"] = read_metadata(metadata_path)
                except (OSError, ValueError) as error:
                    report["warnings"].append(str(error))
            break
    resources = report.get("resources")
    if resources:
        identities = resources["observed_context"]["process_id"]
        selected_processes = {group["identity"].get("ProcessID") for group in report["groups"]}
        if len(identities) != 1 or identities[0] is None or (
            report["kind"] == "presentmon" and selected_processes != {str(identities[0])}
        ):
            report["warnings"].append("The adjacent JSONL process identity does not match this CSV's selected process. Resource metadata was omitted; no CPU/GPU alignment is claimed.")
            del report["resources"]
            return
        if resources["capture_queue_overflow"]:
            report["warnings"].append("CPU capture queue overflow: the raw CSV distribution is incomplete.")
        if True not in resources["observed_context"]["render_hook"]:
            report["warnings"].append("No installed CPU render hook was observed in the JSON samples.")


def summarize_csv(path: Path, refresh_hz: float | None, process_id: str | None, swapchain: str | None) -> dict[str, Any]:
    report: dict[str, Any] = {
        "file": str(path.resolve()), "percentile_method": "nearest rank over valid raw rows",
        "requested_refresh_hz_for_presentmon_thresholds": refresh_hz,
        "rows": 0, "malformed_rows": 0, "filtered_rows": 0, "warnings": [], "groups": [],
    }
    groups: dict[tuple[str, ...], dict[str, Any]] = {}

    def reset_runs() -> None:
        # An unidentified missing row cannot prove a consecutive run.
        for group in groups.values():
            group["not_displayed"].observe(None)
            for series in group["metrics"].values():
                series.runs.observe(None)

    with path.open(encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        if not reader.fieldnames:
            raise ValueError(f"{path}: no CSV header")
        headers = [name.strip() for name in reader.fieldnames]
        if len(set(name.casefold() for name in headers)) != len(headers):
            raise ValueError(f"{path}: duplicate or ambiguous CSV columns")
        reader.fieldnames = headers
        lookup = {name.casefold(): name for name in headers}
        report["schema_columns"] = headers
        is_cpu = CPU_FIELDS.issubset(headers)
        report["kind"] = "cpu_callback" if is_cpu else "presentmon"
        if is_cpu:
            if process_id or swapchain:
                raise ValueError("Process/swapchain filters apply only to PresentMon CSVs.")
        elif not {"processid", "swapchainaddress"}.issubset(lookup):
            raise ValueError(f"{path}: unsupported CSV; expected MangaFlow CPU fields or PresentMon ProcessID/SwapChainAddress")
        present_columns = [name for name in headers if name.casefold() in PRESENT_FIELDS]
        if not is_cpu and not present_columns:
            raise ValueError(f"{path}: no recognized PresentMon timing fields; no units will be guessed")
        if not is_cpu:
            capabilities = {
                "present_call_intervals": {"msbetweenpresents"},
                "displayed_frame_durations": {"displayedtime", "msbetweendisplaychange"},
                "gpu_active_work_duration": {"gpubusy", "msgpubusy", "msgpuactive"},
                "video_engine_active_duration": {"videobusy", "msgpuvideoactive"},
                "input_to_display_latency": INPUT_FIELDS - {"mssinceinput"},
            }
            report["unavailable_columns_for"] = [name for name, candidates in capabilities.items() if not candidates.intersection(lookup)]
        time_columns = [name for name in headers if name.casefold() in TIME_COLUMNS]
        time_column = time_columns[0] if len(time_columns) == 1 else None
        for row in reader:
            report["rows"] += 1
            if None in row or any(row.get(name) is None for name in headers):
                report["malformed_rows"] += 1
                reset_runs()
                continue
            if is_cpu:
                identity: dict[str, str] = {"stream": "Skiko CPU render callbacks"}
            else:
                pid_value, chain_value = row[lookup["processid"]].strip(), row[lookup["swapchainaddress"]].strip()
                if not (pid_value.isascii() and pid_value.isdecimal()) or not chain_value:
                    report["malformed_rows"] += 1
                    reset_runs()
                    continue
                if (process_id and pid_value != process_id) or (swapchain and chain_value.casefold() != swapchain.casefold()):
                    report["filtered_rows"] += 1
                    continue
                identity = {"ProcessID": pid_value, "SwapChainAddress": chain_value}
                for optional in ("application", "vidpnsourceid", "layerindex", "frametype"):
                    if optional in lookup:
                        identity[lookup[optional]] = row[lookup[optional]].strip()
            key = tuple(identity.values())
            if key not in groups:
                if is_cpu:
                    metrics = {
                        "cpu_render_ms": Series("cpu_render_ms", "CPU Skiko callback work; excludes GPU submission and Present", budget_multiplier=1.0),
                        "interactive_callback_interval_ms": Series("callback_interval_ms", "Callback intervals marked interactive by the app; not Present", positive_only=True, budget_multiplier=1.5),
                        "input_dispatch_to_render_ms": Series("input_dispatch_to_render_ms", "AWT dispatch to CPU render completion; not physical input-to-photon"),
                    }
                else:
                    metrics = {
                        name: Series(name, PRESENT_FIELDS[name.casefold()],
                            positive_only=name.casefold() in CADENCE_FIELDS | INPUT_FIELDS | DISPLAY_FIELDS,
                            allow_negative=name.casefold() in SIGNED_FIELDS,
                            budget_multiplier=1.5 if name.casefold() in CADENCE_FIELDS else None)
                        for name in present_columns
                    }
                groups[key] = {"identity": identity, "rows": 0, "metrics": metrics, "time_min": None,
                               "time_max": None, "states": {}, "not_displayed": Runs(), "budgets": set()}
            group = groups[key]
            group["rows"] += 1
            timestamp = finite_number(row.get("start_nanos" if is_cpu else time_column))
            if timestamp is not None:
                timestamp *= 1e-9 if is_cpu else TIME_COLUMNS[time_column.casefold()]
                group["time_min"] = timestamp if group["time_min"] is None else min(group["time_min"], timestamp)
                group["time_max"] = timestamp if group["time_max"] is None else max(group["time_max"], timestamp)
            if is_cpu:
                budget = finite_number(row["display_budget_ms"])
                budget = budget if budget is not None and budget > 0 else None
                if budget is not None:
                    group["budgets"].add(budget)
                interactive = row["interactive_interval"].strip().casefold()
                group["metrics"]["cpu_render_ms"].add(row["cpu_render_ms"], budget=budget)
                group["metrics"]["interactive_callback_interval_ms"].add(row["callback_interval_ms"], eligible=interactive == "true", budget=budget)
                group["metrics"]["input_dispatch_to_render_ms"].add(row["input_dispatch_to_render_ms"])
                if interactive not in {"true", "false"}:
                    group["states"].setdefault("invalid_interactive_flag", Counter())[interactive] += 1
            else:
                displayed: bool | None = None
                if "dropped" in lookup:
                    flag = row[lookup["dropped"]].strip()
                    displayed = True if flag == "0" else False if flag == "1" else None
                elif "displayedtime" in lookup:
                    token = row[lookup["displayedtime"]].strip()
                    # Only upstream's explicit NA denotes a not-displayed frame; an
                    # empty/malformed cell is unknown, not a fabricated dropped frame.
                    displayed = False if token.casefold() == "na" else True if finite_number(token) is not None else None
                group["not_displayed"].observe(not displayed if displayed is not None else None)
                for optional in ("presentruntime", "runtime", "presentmode", "syncinterval", "allowstearing"):
                    if optional in lookup:
                        group["states"].setdefault(lookup[optional], Counter())[row[lookup[optional]].strip()] += 1
                budget = 1000.0 / refresh_hz if refresh_hz else None
                runtime_column = lookup.get("presentruntime", lookup.get("runtime"))
                runtime_other = runtime_column is not None and row[runtime_column].strip().casefold() == "other"
                for name, series in group["metrics"].items():
                    eligible = (displayed is not False or name.casefold() not in DISPLAY_FIELDS) and not (
                        runtime_other and name.casefold() == "msinpresentapi")
                    series.add(row[name], eligible=eligible, budget=budget)
    if not groups:
        raise ValueError(f"{path}: no usable rows matched the schema and filters")
    for group in groups.values():
        item = {
            "identity": group["identity"], "rows": group["rows"],
            "observed_span_seconds": group["time_max"] - group["time_min"] if group["time_min"] is not None else None,
            "metrics": {name: series.result() for name, series in group["metrics"].items()},
            "states": {name: dict(counts) for name, counts in group["states"].items()},
        }
        if is_cpu:
            item["observed_display_budget_ms"] = sorted(group["budgets"])
        else:
            flags = group["not_displayed"]
            item["source_not_displayed_flags"] = {
                "known_rows": flags.eligible,
                "not_displayed_rows": flags.count if flags.eligible else None,
                "consecutive_not_displayed_runs_at_least_two": flags.consecutive_runs if flags.eligible else None,
                "longest_not_displayed_run": flags.longest if flags.eligible else None,
            }
            if group["identity"]["SwapChainAddress"].casefold() in {"0", "0x0", "0x0000000000000000"}:
                report["warnings"].append("A zero swapchain address cannot identify distinct OpenGL/GDI surfaces; this group may contain multiple windows/players. Runtime=Other Present API values are excluded; other timing is measured from kernel observation.")
        report["groups"].append(item)
    if not any(metric["count"] for group in report["groups"] for metric in group["metrics"].values()):
        raise ValueError(f"{path}: all recognized timing fields are unavailable or invalid")
    if is_cpu:
        report["warnings"].append("CPU callback work and interaction cadence are not GPU Present, scanout, or complete input latency.")
    else:
        report["warnings"].append("PresentMon rows may include idle time, video, and generated frames. Groups are not pooled; compare the same continuous scenario and surface.")
        report["warnings"].append("Intervals >1.5 display budgets are a threshold count, not an inferred number of dropped frames. Dropped/not-displayed counts use only explicit source flags.")
        if not refresh_hz:
            report["warnings"].append("No --refresh-hz supplied: budget counts are unavailable. No 60 Hz default or refresh-rate guess is made.")
        if not time_column:
            report["warnings"].append("No unambiguous timestamp with known units: capture span is unavailable (raw QPC frequency is not guessed).")
    if report["malformed_rows"]:
        report["warnings"].append(f"Skipped {report['malformed_rows']} malformed rows; inspect the raw file or finish the capture before analysis.")
    attach_metadata(report, path)
    return report


def compare(before: dict[str, Any], after: dict[str, Any]) -> dict[str, Any]:
    result: dict[str, Any] = {"warnings": [], "percentile_deltas_ms": None}
    if before["kind"] != after["kind"] or set(before["schema_columns"]) != set(after["schema_columns"]):
        result["warnings"].append("CSV schemas differ. Numerical deltas are withheld; recapture with the same metric schema/version.")
        return result
    if len(before["groups"]) != 1 or len(after["groups"]) != 1:
        result["warnings"].append("Multiple swapchain/display/frame-type groups are present. Select one matching group per run with --process-id/--swapchain and --compare-process-id/--compare-swapchain; groups are never selected by apparent FPS.")
        return result
    result["warnings"].append("Deltas are after minus before. Same hardware, driver, display, scenario, data/cache state and capture duration must be established separately; this script does not certify a controlled comparison.")
    for field_name in ("refresh_hz_awt", "dpi_scale_x", "dpi_scale_y", "window_width", "window_height", "window_visible", "renderer", "java_version"):
        prior = before.get("resources", {}).get("observed_context", {}).get(field_name)
        current = after.get("resources", {}).get("observed_context", {}).get(field_name)
        if prior != current:
            result["warnings"].append(f"Observed context differs for {field_name}: {prior} -> {current}.")
    old_group, new_group = before["groups"][0], after["groups"][0]
    prior_identity = {name.casefold(): value for name, value in old_group["identity"].items()}
    current_identity = {name.casefold(): value for name, value in new_group["identity"].items()}
    incompatible_identity = False
    for name in ("application", "frametype", "vidpnsourceid", "layerindex"):
        if prior_identity.get(name) != current_identity.get(name):
            incompatible_identity = True
            result["warnings"].append(f"Group identity differs for {name}: {prior_identity.get(name)} -> {current_identity.get(name)}.")
    if incompatible_identity:
        result["warnings"].append("Numerical deltas are withheld: application/generated-frame/display-layer groups do not match. PID and swapchain addresses may legitimately change between runs.")
        return result
    prior_states = {name: set(counts) for name, counts in old_group["states"].items()}
    current_states = {name: set(counts) for name, counts in new_group["states"].items()}
    if prior_states != current_states and before["kind"] == "presentmon":
        result["warnings"].append("PresentMon mode/runtime/sync values differ; inspect both reports before interpreting the deltas.")
    result["percentile_deltas_ms"] = {
        name: {key: new_metric[key] - old_group["metrics"][name][key]
               if new_metric[key] is not None and old_group["metrics"][name][key] is not None else None
               for key in ("p50", "p95", "p99")}
        for name, new_metric in new_group["metrics"].items()
    }
    return result


def number(value: Any) -> str:
    return "unavailable" if value is None else f"{value:.3f}" if isinstance(value, float) else str(value)


def print_text(result: dict[str, Any]) -> None:
    print("Percentiles: nearest rank from valid individual CSV rows. All timing values are milliseconds.")
    for report in result["captures"]:
        print(f"\n{report['file']}\n  Source: {report['kind']}; rows: {report['rows']}; filtered: {report['filtered_rows']}")
        for group in report["groups"]:
            print(f"\n  Group: {json.dumps(group['identity'], ensure_ascii=False)}")
            print(f"  Rows: {group['rows']}; observed timestamp span: {number(group['observed_span_seconds'])} s")
            for name, metric in group["metrics"].items():
                print(f"    {name}: n={metric['count']} P50={number(metric['p50'])} P95={number(metric['p95'])} P99={number(metric['p99'])} max={number(metric['max'])}")
                print(f"      {metric['meaning']} (column {metric['source_column']})")
                omitted = {key: metric[key] for key in ("missing_rows", "invalid_rows", "excluded_rows", "zero_without_observation_rows") if metric[key]}
                if omitted:
                    print(f"      Omitted: {json.dumps(omitted)}")
                threshold = metric.get("budget_threshold")
                if threshold:
                    print(f"      >{threshold['multiplier']} budget: {number(threshold['over_threshold_rows'])}/{threshold['eligible_rows']} eligible; runs >=2: {number(threshold['consecutive_runs_at_least_two'])}; longest: {number(threshold['longest_run'])}")
            if group.get("states"):
                print(f"  Runtime/mode states: {json.dumps(group['states'], ensure_ascii=False)}")
            if "source_not_displayed_flags" in group:
                print(f"  Explicit source not-displayed flags: {json.dumps(group['source_not_displayed_flags'])}")
        resources = report.get("resources")
        if resources:
            print(f"\n  Adjacent JSONL: {resources['samples']} samples, {resources['first_timestamp']} .. {resources['last_timestamp']}")
            print(f"  Observed context: {json.dumps(resources['observed_context'], ensure_ascii=False)}")
            print(f"  Sampled peaks (units in names): {json.dumps(resources['sampled_peaks'])}")
            print(f"  First-to-last counters: {json.dumps(resources['first_to_last_counter_deltas'])}")
            print(f"  Last rolling/native sample only (not capture-wide percentiles): {json.dumps(resources['last_sample_only'], ensure_ascii=False)}")
            for note in resources["notes"]:
                print(f"  Note: {note}")
        for warning in report["warnings"]:
            print(f"  Warning: {warning}")
        if report.get("unavailable_columns_for"):
            print(f"  Unavailable source columns: {', '.join(report['unavailable_columns_for'])}")
    comparison = result.get("comparison")
    if comparison:
        print("\nComparison (after - before):")
        for warning in comparison["warnings"]:
            print(f"  Warning: {warning}")
        for name, deltas in (comparison["percentile_deltas_ms"] or {}).items():
            print(f"  {name}: P50={number(deltas['p50'])}, P95={number(deltas['p95'])}, P99={number(deltas['p99'])} ms")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("files", nargs="+", type=Path, help="One raw CSV, or BEFORE.csv AFTER.csv (same schema)")
    parser.add_argument("--refresh-hz", type=float, help="Actual fixed display mode for PresentMon budget thresholds; never inferred")
    parser.add_argument("--process-id", help="Keep only this PresentMon PID in the first input")
    parser.add_argument("--swapchain", help="Keep only this PresentMon SwapChainAddress in the first input")
    parser.add_argument("--compare-process-id", help="Keep only this PresentMon PID in the second input")
    parser.add_argument("--compare-swapchain", help="Keep only this PresentMon SwapChainAddress in the second input")
    parser.add_argument("--json", action="store_true", help="Write a machine-readable report to stdout; unavailable values are null")
    arguments = parser.parse_args()
    if len(arguments.files) not in (1, 2):
        parser.error("Provide one CSV, or exactly two CSVs for before/after comparison.")
    if arguments.refresh_hz is not None and (not math.isfinite(arguments.refresh_hz) or arguments.refresh_hz <= 0):
        parser.error("--refresh-hz must be a finite positive actual display rate.")
    if len(arguments.files) == 1 and (arguments.compare_process_id or arguments.compare_swapchain):
        parser.error("--compare-* filters require a second input file.")
    for pid in (arguments.process_id, arguments.compare_process_id):
        if pid is not None and (not (pid.isascii() and pid.isdecimal()) or int(pid) <= 0):
            parser.error("Process IDs must be positive decimal integers.")
    try:
        captures = [summarize_csv(arguments.files[0], arguments.refresh_hz, arguments.process_id, arguments.swapchain)]
        if len(arguments.files) == 2:
            captures.append(summarize_csv(arguments.files[1], arguments.refresh_hz, arguments.compare_process_id, arguments.compare_swapchain))
        result: dict[str, Any] = {"report_schema": 1, "captures": captures}
        if len(captures) == 2:
            result["comparison"] = compare(*captures)
        if arguments.json:
            print(json.dumps(result, indent=2, ensure_ascii=False, allow_nan=False))
        else:
            print_text(result)
        return 0
    except (OSError, ValueError, csv.Error) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
