import os
import re
import argparse
from datetime import datetime, timedelta
from collections import defaultdict

# Pricing (Cost per 1M tokens) - Estimates based on 2024/2025 landscape
# Prices in USD
PRICING = {
    # Gemini 3 Pro (Preview) - assuming similar to 1.5 Pro or higher
    "gemini-3-pro-preview": {"in": 2.50, "out": 10.00, "cached": 0.625}, 
    "gemini-2.0-flash": {"in": 0.10, "out": 0.40, "cached": 0.025},
    
    # Anthropic
    "claude-3-5-sonnet": {"in": 3.00, "out": 15.00, "cached": 0.30},
    "claude-sonnet-4.6": {"in": 3.00, "out": 15.00, "cached": 0.30}, # Assuming similar to 3.5 Sonnet
    "claude-3-opus": {"in": 15.00, "out": 75.00, "cached": 1.50},
    "claude-3-haiku": {"in": 0.25, "out": 1.25, "cached": 0.025},

    # OpenAI
    "gpt-4o": {"in": 2.50, "out": 10.00, "cached": 1.25},
    "gpt-4o-mini": {"in": 0.15, "out": 0.60, "cached": 0.075},
    
    # Legacy / Other
    "gpt-4": {"in": 30.00, "out": 60.00, "cached": 30.00},
    "gpt-3.5-turbo": {"in": 0.50, "out": 1.50, "cached": 0.50},
}

# Fallback pricing (Average high-end model)
DEFAULT_PRICING = {"in": 2.50, "out": 10.00, "cached": 1.25}

def parse_size(size_str):
    """Parses size strings like '917.2k', '1.2m', '500'."""
    size_str = size_str.lower().strip()
    multiplier = 1
    if size_str.endswith('k'):
        multiplier = 1000
        size_str = size_str[:-1]
    elif size_str.endswith('m'):
        multiplier = 1000000
        size_str = size_str[:-1]
    elif size_str.endswith('b'):
        multiplier = 1000000000
        size_str = size_str[:-1]
    
    try:
        return int(float(size_str) * multiplier)
    except ValueError:
        return 0

def parse_duration(dur_str):
    """Parses duration string like '4m 9s' into seconds."""
    total_seconds = 0
    # Match '4m'
    m = re.search(r'(\d+)m', dur_str)
    if m:
        total_seconds += int(m.group(1)) * 60
    # Match '9s'
    s = re.search(r'(\d+)s', dur_str)
    if s:
        total_seconds += int(s.group(1))
    
    # If no m/s but digits, assume seconds or check for h
    if total_seconds == 0 and dur_str.isdigit():
        total_seconds = int(dur_str)
        
    h = re.search(r'(\d+)h', dur_str)
    if h:
        total_seconds += int(h.group(1)) * 3600
        
    return total_seconds

def format_duration(seconds):
    """Formats seconds back to 'Hh Mm Ss'."""
    h = seconds // 3600
    m = (seconds % 3600) // 60
    s = seconds % 60
    parts = []
    if h > 0: parts.append(f"{h}h")
    if m > 0: parts.append(f"{m}m")
    if s > 0 or not parts: parts.append(f"{s}s")
    return " ".join(parts)

def analyze_logs(log_dir):
    data = defaultdict(lambda: {
        "api_time_seconds": 0,
        "session_time_seconds": 0,
        "code_added": 0,
        "code_removed": 0,
        "models": defaultdict(lambda: {"in": 0, "out": 0, "cached": 0, "count": 0})
    })

    # Regex for summary block
    # API time spent:         4m 9s
    # Total session time:     4m 30s
    # Total code changes:     +490 -29
    # Breakdown by AI model:
    #  gemini-3-pro-preview    917.2k in, 12.2k out, 837.5k cached (Est. 1 Premium request)

    re_api_time = re.compile(r"API time spent:\s+(.+)")
    re_session_time = re.compile(r"Total session time:\s+(.+)")
    re_code_changes = re.compile(r"Total code changes:\s+\+(\d+)\s+-(\d+)")
    re_model_breakdown_start = re.compile(r"Breakdown by AI model:")
    # Improved regex to be more flexible with spaces and model names
    re_model_line = re.compile(r"^\s*(\S+)\s+([\d.]+[kmb]?) in,\s+([\d.]+[kmb]?) out,\s+([\d.]+[kmb]?) cached")

    files_found = 0
    
    for root, dirs, files in os.walk(log_dir):
        for file in files:
            if file.endswith(".copilot.log"):
                files_found += 1
                filepath = os.path.join(root, file)
                
                # Extract date from path: .../300logs/2026/02/28/...
                path_parts = os.path.normpath(filepath).split(os.sep)
                try:
                    # Find '300logs' index and take next 3 parts
                    if '300logs' in path_parts:
                        idx = path_parts.index('300logs')
                        # Check if we have enough parts
                        if idx + 3 < len(path_parts):
                             date_str = f"{path_parts[idx+1]}-{path_parts[idx+2]}-{path_parts[idx+3]}"
                        else:
                             # Maybe year/month only?
                             date_str = "Unknown"
                    else:
                        date_str = "Unknown"
                        
                    if date_str == "Unknown":
                        # Fallback to file modification time
                        mtime = os.path.getmtime(filepath)
                        date_str = datetime.fromtimestamp(mtime).strftime('%Y-%m-%d')
                except Exception:
                    # Fallback to file modification time
                    mtime = os.path.getmtime(filepath)
                    date_str = datetime.fromtimestamp(mtime).strftime('%Y-%m-%d')

                # Store file data for detail view
                file_data = {
                    "filename": file,
                    "api_time_seconds": 0,
                    "session_time_seconds": 0,
                    "code_added": 0,
                    "code_removed": 0,
                    "models": defaultdict(lambda: {"in": 0, "out": 0, "cached": 0, "count": 0})
                }

                try:
                    content = None
                    for enc in ['utf-8', 'utf-16', 'latin-1']:
                        try:
                            with open(filepath, 'r', encoding=enc) as f:
                                content = f.read()
                            break
                        except UnicodeError:
                            continue
                            
                    if content:
                        lines = content.splitlines()
                        
                        # Process from end of file backwards or just find the block
                        # The block is usually at the end.
                        
                        in_breakdown = False
                        
                        for line in lines:
                            line = line.strip()
                            
                            m_api = re_api_time.search(line)
                            if m_api:
                                dur = parse_duration(m_api.group(1))
                                data[date_str]["api_time_seconds"] += dur
                                file_data["api_time_seconds"] = dur
                                
                            m_session = re_session_time.search(line)
                            if m_session:
                                dur = parse_duration(m_session.group(1))
                                data[date_str]["session_time_seconds"] += dur
                                file_data["session_time_seconds"] = dur
                                
                            m_code = re_code_changes.search(line)
                            if m_code:
                                added = int(m_code.group(1))
                                removed = int(m_code.group(2))
                                data[date_str]["code_added"] += added
                                data[date_str]["code_removed"] += removed
                                file_data["code_added"] = added
                                file_data["code_removed"] = removed
                            
                            if re_model_breakdown_start.search(line):
                                in_breakdown = True
                                continue
                            
                            if in_breakdown:
                                m_model = re_model_line.search(line) 
                                if m_model:
                                    model_name = m_model.group(1)
                                    in_tokens = parse_size(m_model.group(2))
                                    out_tokens = parse_size(m_model.group(3))
                                    cached_tokens = parse_size(m_model.group(4))
                                    
                                    data[date_str]["models"][model_name]["in"] += in_tokens
                                    data[date_str]["models"][model_name]["out"] += out_tokens
                                    data[date_str]["models"][model_name]["cached"] += cached_tokens
                                    data[date_str]["models"][model_name]["count"] += 1
                                    
                                    file_data["models"][model_name]["in"] += in_tokens
                                    file_data["models"][model_name]["out"] += out_tokens
                                    file_data["models"][model_name]["cached"] += cached_tokens
                                    file_data["models"][model_name]["count"] += 1

                                elif line == "" or line.startswith("Total"): 
                                    pass
                                else:
                                    if in_breakdown and not line.startswith("Est."):
                                        pass

                        # Add file detail to data if needed
                        if "files" not in data[date_str]:
                            data[date_str]["files"] = []
                        data[date_str]["files"].append(file_data)

                except Exception as e:
                    print(f"Error reading {filepath}: {e}")

    return data

def calculate_cost(model_name, in_tokens, out_tokens, cached_tokens):
    pricing = PRICING.get(model_name, DEFAULT_PRICING)
    
    cost = (in_tokens / 1_000_000 * pricing["in"]) + \
           (out_tokens / 1_000_000 * pricing["out"]) + \
           (cached_tokens / 1_000_000 * pricing["cached"])
           
    return cost

def print_report(data, detail=False):
    total_cost_grand = 0
    total_in_grand = 0
    total_out_grand = 0
    
    print(f"{'Date':<12} | {'Tasks':<5} | {'Sess Time':<10} | {'API Time':<10} | {'Code +/-':<15} | {'Est. Cost':<10}")
    print("-" * 80)
    
    sorted_dates = sorted(data.keys())
    
    for date_str in sorted_dates:
        day_data = data[date_str]
        
        # Calculate daily cost
        daily_cost = 0
        task_count = 0
        daily_in = 0
        daily_out = 0
        
        for model, usage in day_data["models"].items():
            cost = calculate_cost(model, usage["in"], usage["out"], usage["cached"])
            daily_cost += cost
            daily_in += usage["in"]
            daily_out += usage["out"]
            task_count += usage["count"] 

        total_cost_grand += daily_cost
        total_in_grand += daily_in
        total_out_grand += daily_out
        
        print(f"{date_str:<12} | {task_count:<5} | {format_duration(day_data['session_time_seconds']):<10} | {format_duration(day_data['api_time_seconds']):<10} | +{day_data['code_added']}/-{day_data['code_removed']:<7} | ${daily_cost:.2f}")

        if detail and "files" in day_data:
             print("  Detailed Tasks:")
             for f in day_data["files"]:
                 f_cost = 0
                 for m, u in f["models"].items():
                     f_cost += calculate_cost(m, u["in"], u["out"], u["cached"])
                 
                 if f_cost > 0 or f["api_time_seconds"] > 0:
                     print(f"    {f['filename']:<50} | {format_duration(f['api_time_seconds']):<8} | ${f_cost:.2f}")
             print("")

    print("-" * 80)
    print(f"TOTAL ESTIMATED COST: ${total_cost_grand:.2f}")
    print(f"TOTAL TOKENS: {total_in_grand/1e6:.1f}M in, {total_out_grand/1e6:.1f}M out")
    
    print("\nDetailed Model Breakdown (Total):")
    model_totals = defaultdict(lambda: {"in": 0, "out": 0, "cached": 0, "cost": 0})
    
    for date_str in data:
        for model, usage in data[date_str]["models"].items():
            model_totals[model]["in"] += usage["in"]
            model_totals[model]["out"] += usage["out"]
            model_totals[model]["cached"] += usage["cached"]
            model_totals[model]["cost"] += calculate_cost(model, usage["in"], usage["out"], usage["cached"])

    for model, usage in sorted(model_totals.items(), key=lambda x: x[1]['cost'], reverse=True):
         print(f"  {model:<25} : ${usage['cost']:>6.2f}  ({usage['in']/1e6:.1f}M in, {usage['out']/1e6:.1f}M out, {usage['cached']/1e6:.1f}M cached)")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Count total token usage from copilot logs")
    parser.add_argument("log_dir", help="Directory containing logs (e.g., coworker/tasks/300logs)")
    parser.add_argument("--detail", "-d", action="store_true", help="Show detailed per-file breakdown")
    args = parser.parse_args()
    
    if not os.path.exists(args.log_dir):
        print(f"Error: Directory {args.log_dir} not found.")
        exit(1)
        
    data = analyze_logs(args.log_dir)
    print_report(data, detail=args.detail)
