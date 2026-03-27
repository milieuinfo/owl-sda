#!/usr/bin/env python3

import json
import argparse
from pathlib import Path
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches


def plot_benchmark(benchmark_dir):
    """
    Plot benchmark metrics including duration, shapes processed, and violations.
    
    Args:
        benchmark_dir: Path to the benchmark directory containing benchmark-summary.json
    """
    # Convert to absolute path, relative to OWL-SDA root if needed
    benchmark_path = Path(benchmark_dir)
    if not benchmark_path.is_absolute():
        # If relative path, make it relative to the OWL-SDA root directory
        script_dir = Path(__file__).parent  # scripts/ directory
        owlsda_root = script_dir.parent      # OWL-SDA/ directory
        # Convert "../examples/..." to "examples/..."
        rel_path = benchmark_path
        if str(rel_path).startswith("../"):
            rel_path = Path(str(rel_path)[3:])
        benchmark_path = (owlsda_root / rel_path).resolve()
    
    benchmark_file = benchmark_path / "benchmark-summary.json"
    
    if not benchmark_file.exists():
        raise FileNotFoundError(f"Benchmark file not found: {benchmark_file}")
    
    # Read the JSON file
    with open(benchmark_file, 'r') as f:
        data = json.load(f)
    
    # Detect and remove aggregated final 'REVIEW' entry (if present)
    final_review_idx = None
    for idx, item in enumerate(data):
        if item.get('stage', '') == 'REVIEW':
            # check if other REVIEW_* entries exist
            if any(other.get('stage', '').startswith('REVIEW') and other.get('stage', '') != 'REVIEW' for other in data):
                final_review_idx = idx
                break

    final_review_workers_sum = None
    if final_review_idx is not None:
        final_tokens = data[final_review_idx].get('tokens', {})
        fw = final_tokens.get('workers', {}) or {}
        if isinstance(fw, dict):
            final_review_workers_sum = sum(fw.values())
        proc_data = [item for i, item in enumerate(data) if i != final_review_idx]
    else:
        proc_data = data

    # Extract metrics from processed data (without aggregated REVIEW row)
    iterations = list(range(len(proc_data)))
    durations_sec = [item['durationMs'] / 1000.0 for item in proc_data]
    shapes_processed = [item['shapesProcessed'] for item in proc_data]

    # Compute aggregated token counts per iteration, using final review workers if needed
    workers_tokens = []
    reviewer_tokens = []
    supervisor_tokens = []
    for item in proc_data:
        tokens = item.get('tokens', {})
        workers = tokens.get('workers', {}) or {}
        if isinstance(workers, dict):
            workers_sum = sum(workers.values())
        else:
            workers_sum = 0
        # If this is a REVIEW* stage, always use the final aggregated REVIEW workers value when available
        st = item.get('stage', '')
        if st.startswith('REVIEW') and final_review_workers_sum is not None:
            workers_sum = final_review_workers_sum
        workers_tokens.append(workers_sum)
        reviewer_tokens.append(tokens.get('reviewer', 0) or 0)
        supervisor_tokens.append(tokens.get('supervisor', 0) or 0)

    # Group stages for background coloring (group all REVIEW_* under 'REVIEW')
    stage_groups = []
    for item in proc_data:
        st = item.get('stage', '')
        if st.startswith('REVIEW'):
            stage_groups.append('REVIEW')
        else:
            # normalize FINALIZING -> FINALISING for display
            if st == 'FINALIZING':
                stage_groups.append('FINALISING')
            else:
                stage_groups.append(st)
    
    # Create figure with subplots for better visibility due to different scales
    fig, axes = plt.subplots(3, 1, figsize=(12, 10))
    
    # Plot 1: Duration
    axes[0].plot(iterations, durations_sec, marker='o', linestyle='-', color='#1f77b4', linewidth=2)
    axes[0].set_ylabel('Duration (seconds)', fontsize=11, fontweight='bold')
    # Title removed per user request
    axes[0].grid(True, alpha=0.3)
    axes[0].set_xlim(-0.5, len(iterations) - 0.5)
    
    # Plot 2: Shapes Processed
    axes[1].plot(iterations, shapes_processed, marker='s', linestyle='-', color='#ff7f0e', linewidth=2)
    axes[1].set_ylabel('Shapes Processed', fontsize=11, fontweight='bold')
    axes[1].grid(True, alpha=0.3)
    axes[1].set_xlim(-0.5, len(iterations) - 0.5)
    axes[1].set_ylim(bottom=0)
    
    # Plot 3: Token counts (workers aggregated, reviewer, supervisor)
    axes[2].plot(iterations, workers_tokens, marker='o', linestyle='-', color='#ff7f0e', linewidth=2, label='Workers (sum)')
    axes[2].plot(iterations, reviewer_tokens, marker='s', linestyle='--', color='#2ca02c', linewidth=2, label='Reviewer')
    axes[2].plot(iterations, supervisor_tokens, marker='^', linestyle='-.', color='#9467bd', linewidth=2, label='Supervisor')
    axes[2].set_ylabel('Tokens', fontsize=11, fontweight='bold')
    axes[2].set_xlabel('Iteration', fontsize=11, fontweight='bold')
    axes[2].grid(True, alpha=0.3)
    axes[2].set_xlim(-0.5, len(iterations) - 0.5)
    axes[2].set_ylim(bottom=0)
    axes[2].legend(loc='upper left')

    # Add colored background spans per contiguous stage group across all subplots
    # Use more distinct colors for the stage groups
    group_colors = {
        'GENERATE': '#c6dbef',   # light blue
        'FINALISING': '#fdd0a2', # light orange (British spelling for legend)
        'REVIEW': '#e5f5e0'      # light green
    }
    # Find contiguous segments of same group
    start_idx = 0
    for i in range(1, len(stage_groups) + 1):
        end_idx = i - 1
        if i == len(stage_groups) or stage_groups[i] != stage_groups[start_idx]:
            grp = stage_groups[start_idx]
            color = group_colors.get(grp, '#ffffff')
            span_start = start_idx - 0.5
            span_end = end_idx + 0.5
            for ax in axes:
                ax.axvspan(span_start, span_end, color=color, alpha=0.4, zorder=0)
            start_idx = i
    # Add a legend for the background stage colors
    patches = []
    for name, col in group_colors.items():
        patches.append(mpatches.Patch(color=col, alpha=0.4, label=name))
    # Place the background legend above the subplots
    fig.subplots_adjust(top=0.88)
    fig.legend(handles=patches, loc='upper center', ncol=len(patches), bbox_to_anchor=(0.5, 0.97))
    
    plt.tight_layout()
    
    # Configure PDF settings for embedded fonts
    plt.rcParams['pdf.fonttype'] = 42  # TrueType fonts
    plt.rcParams['ps.fonttype'] = 42   # TrueType fonts for PS/EPS
    
    # Save the plot in multiple formats
    base_path = benchmark_path / "benchmark_plot"
    
    # PNG format
    png_file = base_path.with_suffix('.png')
    plt.savefig(png_file, dpi=150, bbox_inches='tight')
    print(f"Plot saved to: {png_file}")
    
    # SVG format
    svg_file = base_path.with_suffix('.svg')
    plt.savefig(svg_file, format='svg', bbox_inches='tight')
    print(f"Plot saved to: {svg_file}")
    
    # PDF format with embedded fonts
    pdf_file = base_path.with_suffix('.pdf')
    plt.savefig(pdf_file, format='pdf', bbox_inches='tight')
    print(f"Plot saved to: {pdf_file}")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(
        description='Plot benchmark metrics from benchmark-summary.json'
    )
    parser.add_argument(
        '--benchmark-dir',
        type=str,
        default='../examples/project-1/benchmark_paper',
        help='Path to benchmark directory (default: ../examples/project-2/benchmark)'
    )
    parser.add_argument(
        '--show',
        action='store_true',
        help='Display the plot interactively (default: just save to file)'
    )
    
    args = parser.parse_args()
    
    # Temporarily disable interactive display if not requested
    import matplotlib
    if not args.show:
        matplotlib.use('Agg')  # Non-interactive backend
    
    plot_benchmark(args.benchmark_dir)
    
    if args.show:
        import matplotlib.pyplot as plt
        plt.show()
