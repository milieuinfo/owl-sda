#!/usr/bin/env python3

import json
import argparse
from pathlib import Path
import statistics as _stats
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches


def _token_total(token_entry):
    if isinstance(token_entry, dict):
        if 'total' in token_entry and token_entry['total'] is not None:
            return token_entry['total']
        return (token_entry.get('input') or 0) + (token_entry.get('output') or 0)
    return token_entry or 0


def _token_input(token_entry):
    if isinstance(token_entry, dict):
        return token_entry.get('input') or 0
    return 0


def _token_output(token_entry):
    if isinstance(token_entry, dict):
        return token_entry.get('output') or 0
    return 0


def _workers_tokens(workers, extractor):
    if not isinstance(workers, dict):
        return 0
    return sum(extractor(value) for value in workers.values())


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
    
    # Detect and remove the aggregated final REVIEW entry (if present).
    # Always skip the last occurrence of a stage that starts with 'REVIEW',
    # but reuse its aggregated workers token counts to fill REVIEW* iterations.
    final_review_idx = None
    for idx in range(len(data) - 1, -1, -1):
        if data[idx].get('stage', '').startswith('REVIEW'):
            final_review_idx = idx
            break

    final_review_workers_sum = None
    if final_review_idx is not None:
        final_tokens = data[final_review_idx].get('tokens', {})
        fw = final_tokens.get('workers', {}) or {}
        final_review_workers_sum = _workers_tokens(fw, _token_total)
        proc_data = [item for i, item in enumerate(data) if i != final_review_idx]
    else:
        proc_data = data

    # Extract metrics from processed data (without aggregated REVIEW row)
    iterations = list(range(len(proc_data)))
    durations_sec = [item['durationMs'] / 1000.0 for item in proc_data]
    shapes_processed = [item['shapesProcessed'] for item in proc_data]
    triplestore_sizes = [item.get('triplestoreSize', 0) for item in proc_data]

    # Compute aggregated token counts per iteration, using final review workers if needed
    workers_tokens = []
    reviewer_tokens = []
    supervisor_tokens = []
    workers_input_tokens = []
    workers_output_tokens = []
    reviewer_input_tokens = []
    reviewer_output_tokens = []
    supervisor_input_tokens = []
    supervisor_output_tokens = []
    # Forward-fill workers token sum so REVIEW iterations don't drop to 0
    last_workers_sum = None
    for item in proc_data:
        tokens = item.get('tokens', {})
        workers = tokens.get('workers', {}) or {}
        workers_sum = _workers_tokens(workers, _token_total)
        workers_input_sum = _workers_tokens(workers, _token_input)
        workers_output_sum = _workers_tokens(workers, _token_output)

        # If this is a REVIEW* stage, and an aggregated final REVIEW workers value exists, use it
        st = item.get('stage', '')
        if st.startswith('REVIEW') and final_review_workers_sum is not None:
            workers_sum = final_review_workers_sum

        # If workers_sum is zero/empty, reuse the last available non-zero workers sum
        if workers_sum == 0 and last_workers_sum is not None:
            workers_sum = last_workers_sum
        elif workers_sum > 0:
            last_workers_sum = workers_sum

        workers_tokens.append(workers_sum)
        workers_input_tokens.append(workers_input_sum)
        workers_output_tokens.append(workers_output_sum)

        reviewer = tokens.get('reviewer', 0)
        supervisor = tokens.get('supervisor', 0)
        reviewer_tokens.append(_token_total(reviewer))
        supervisor_tokens.append(_token_total(supervisor))
        reviewer_input_tokens.append(_token_input(reviewer))
        reviewer_output_tokens.append(_token_output(reviewer))
        supervisor_input_tokens.append(_token_input(supervisor))
        supervisor_output_tokens.append(_token_output(supervisor))

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
    fig, axes = plt.subplots(3, 1, figsize=(6, 10))
    
    # Plot 1: Duration
    axes[0].plot(iterations, durations_sec, marker='o', linestyle='-', color='#1f77b4', linewidth=2)
    axes[0].set_ylabel('Duration (seconds)', fontsize=11, fontweight='bold')
    # Title removed per user request
    axes[0].grid(True, alpha=0.3)
    axes[0].set_xlim(-0.5, len(iterations) - 0.5)
    
    # Plot 2: Shapes Processed (with violations as red line on secondary y-axis)
    ax_shapes = axes[1]
    ax_shapes.plot(iterations, shapes_processed, marker='s', linestyle='-', color='#ff7f0e', linewidth=2, label='Shapes Processed')
    ax_shapes.set_ylabel('Shapes Processed', fontsize=11, fontweight='bold')
    ax_shapes.grid(True, alpha=0.3)
    ax_shapes.set_xlim(-0.5, len(iterations) - 0.5)
    ax_shapes.set_ylim(bottom=0)
    # secondary y-axis for triples (triplestore size)
    ax_triples = ax_shapes.twinx()
    ax_triples.plot(iterations, triplestore_sizes, marker='o', linestyle='-', color='#2ca02c', linewidth=2, label='Triples')
    ax_triples.set_ylabel('Triples', color='#2ca02c', fontsize=11, fontweight='bold')
    ax_triples.tick_params(axis='y', colors='#2ca02c')
    # combined legend for shapes and triples
    h1, l1 = ax_shapes.get_legend_handles_labels()
    h2, l2 = ax_triples.get_legend_handles_labels()
    ax_shapes.legend(h1 + h2, l1 + l2, loc='upper left')
    
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

    # Force integer ticks/labels for iterations on x-axis
    if iterations:
        xticks = iterations
        xtick_labels = [str(int(x)) for x in xticks]
        for ax in axes:
            ax.set_xticks(xticks)
            ax.set_xticklabels(xtick_labels)

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

    # Compute and log useful statistics
    num_iterations = len(proc_data)
    total_time_sec = sum(durations_sec)
    mean_time_sec = _stats.mean(durations_sec) if durations_sec else 0
    median_time_sec = _stats.median(durations_sec) if durations_sec else 0
    min_time_sec = min(durations_sec) if durations_sec else 0
    max_time_sec = max(durations_sec) if durations_sec else 0

    total_shapes = sum(shapes_processed)
    avg_shapes = _stats.mean(shapes_processed) if shapes_processed else 0
    min_shapes = min(shapes_processed) if shapes_processed else 0
    max_shapes = max(shapes_processed) if shapes_processed else 0

    total_triples = sum(triplestore_sizes)
    final_triples = triplestore_sizes[-1] if triplestore_sizes else 0
    max_triples = max(triplestore_sizes) if triplestore_sizes else 0

    total_workers_tokens = sum(workers_tokens)
    total_reviewer_tokens = sum(reviewer_tokens)
    total_supervisor_tokens = sum(supervisor_tokens)
    total_workers_input_tokens = sum(workers_input_tokens)
    total_workers_output_tokens = sum(workers_output_tokens)
    total_reviewer_input_tokens = sum(reviewer_input_tokens)
    total_reviewer_output_tokens = sum(reviewer_output_tokens)
    total_supervisor_input_tokens = sum(supervisor_input_tokens)
    total_supervisor_output_tokens = sum(supervisor_output_tokens)

    shapes_per_sec_overall = total_shapes / total_time_sec if total_time_sec > 0 else 0

    stats_dict = {
        'num_iterations': num_iterations,
        'total_time_sec': total_time_sec,
        'mean_time_sec': mean_time_sec,
        'median_time_sec': median_time_sec,
        'min_time_sec': min_time_sec,
        'max_time_sec': max_time_sec,
        'total_shapes': total_shapes,
        'avg_shapes': avg_shapes,
        'min_shapes': min_shapes,
        'max_shapes': max_shapes,
        'total_triples': total_triples,
        'final_triples': final_triples,
        'max_triples': max_triples,
        'shapes_per_sec_overall': shapes_per_sec_overall,
        'total_workers_tokens': total_workers_tokens,
        'total_reviewer_tokens': total_reviewer_tokens,
        'total_supervisor_tokens': total_supervisor_tokens,
        'total_workers_input_tokens': total_workers_input_tokens,
        'total_workers_output_tokens': total_workers_output_tokens,
        'total_reviewer_input_tokens': total_reviewer_input_tokens,
        'total_reviewer_output_tokens': total_reviewer_output_tokens,
        'total_supervisor_input_tokens': total_supervisor_input_tokens,
        'total_supervisor_output_tokens': total_supervisor_output_tokens,
    }

    # Write stats to files
    stats_txt = benchmark_path / 'benchmark_stats.txt'
    with open(stats_txt, 'w') as f:
        f.write('Benchmark statistics\n')
        f.write('====================\n')
        for k, v in stats_dict.items():
            f.write(f"{k}: {v}\n")

    stats_json = benchmark_path / 'benchmark_stats.json'
    with open(stats_json, 'w') as f:
        json.dump(stats_dict, f, indent=2)

    print(f"Stats written to: {stats_txt}")
    print(f"Stats (JSON) written to: {stats_json}")
    
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

    # Close the figure to avoid memory leaks when processing multiple projects
    plt.close(fig)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(
        description='Plot benchmark metrics from benchmark-summary.json'
    )
    parser.add_argument(
        '--benchmark-dir',
        type=str,
        default='../examples/project-2/benchmark_paper',
        help='Path to benchmark directory (default: ../examples/project-2/benchmark)'
    )
    parser.add_argument(
        '--all',
        action='store_true',
        help='Process all examples/*/benchmark_paper directories'
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
    
    # If requested, process all `benchmark_paper` directories under `examples/`
    if args.all:
        script_dir = Path(__file__).parent
        owlsda_root = script_dir.parent
        examples_dir = owlsda_root / 'examples'
        found = []
        if examples_dir.exists():
            for proj in sorted(examples_dir.iterdir()):
                bp = proj / 'benchmark_paper'
                if bp.exists() and bp.is_dir():
                    print(f"Processing: {bp}")
                    plot_benchmark(str(bp))
                    found.append(bp)
        if not found:
            print(f"No benchmark_paper directories found under: {examples_dir}")
    else:
        plot_benchmark(args.benchmark_dir)
    
    if args.show:
        import matplotlib.pyplot as plt
        plt.show()
