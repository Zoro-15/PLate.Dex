import os
import sys

def main():
    log_file = "build.log"
    out_file = "build_error_summary.txt"

    if not os.path.exists(log_file):
        with open(out_file, "w", encoding="utf-8") as f:
            f.write("build.log file not found.")
        print("build.log not found.")
        return

    with open(log_file, "r", encoding="utf-8", errors="replace") as f:
        lines = f.readlines()

    keywords = [
        "Caused by:",
        "FAILURE: Build failed",
        "BUILD FAILED",
        "MergingException",
        "Aapt2Exception",
        "Compilation error",
        "e: ",
        "error:",
        "Exception:"
    ]

    # Find line indices matching any keyword
    matching_indices = []
    for i, line in enumerate(lines):
        line_lower = line.lower()
        if any(kw.lower() in line_lower for kw in keywords):
            # Exclude trivial info/debug lines if needed
            if "info" in line_lower and "error" not in line_lower:
                continue
            matching_indices.append(i)

    if not matching_indices:
        # Fallback to last 20 lines if no specific keyword matched
        start = max(0, len(lines) - 20)
        snippet = "".join(lines[start:])
        summary = f"--- [No exact keyword match - Tail of build.log] ---\n{snippet}"
    else:
        # Merge overlapping context ranges (2 lines above, 2 lines below)
        ranges = []
        for idx in matching_indices:
            start = max(0, idx - 2)
            end = min(len(lines), idx + 3) # exclusive
            if ranges and start <= ranges[-1][1]:
                ranges[-1] = (ranges[-1][0], max(ranges[-1][1], end))
            else:
                ranges.append((start, end))

        sections = []
        for start, end in ranges:
            header = f"\n=== [Lines {start + 1} - {end}] ==="
            block = "".join(lines[start:end])
            sections.append(f"{header}\n{block}")
        summary = "\n".join(sections)

    with open(out_file, "w", encoding="utf-8") as f:
        f.write(summary)

    print("\n" + "="*40 + " BUILD ERROR SUMMARY " + "="*40)
    print(summary)
    print("="*101 + "\n")

if __name__ == "__main__":
    main()
