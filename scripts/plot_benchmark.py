#!/usr/bin/env python3

import json
import argparse
from pathlib import Path
import matplotlib.pyplot as plt


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
    
    # Extract metrics
    iterations = list(range(len(data)))
    durations_sec = [item['durationMs'] / 1000.0 for item in data]
    shapes_processed = [item['shapesProcessed'] for item in data]
    violations = [item['currentViolations'] for item in data]
    
    # Create figure with subplots for better visibility due to different scales
    fig, axes = plt.subplots(3, 1, figsize=(12, 10))
    
    # Plot 1: Duration
    axes[0].plot(iterations, durations_sec, marker='o', linestyle='-', color='#1f77b4', linewidth=2)
    axes[0].set_ylabel('Duration (seconds)', fontsize=11, fontweight='bold')
    axes[0].set_title('Benchmark Metrics per Iteration', fontsize=13, fontweight='bold')
    axes[0].grid(True, alpha=0.3)
    axes[0].set_xlim(-0.5, len(iterations) - 0.5)
    
    # Plot 2: Shapes Processed
    axes[1].plot(iterations, shapes_processed, marker='s', linestyle='-', color='#ff7f0e', linewidth=2)
    axes[1].set_ylabel('Shapes Processed', fontsize=11, fontweight='bold')
    axes[1].grid(True, alpha=0.3)
    axes[1].set_xlim(-0.5, len(iterations) - 0.5)
    axes[1].set_ylim(bottom=0)
    
    # Plot 3: Violations
    axes[2].plot(iterations, violations, marker='^', linestyle='-', color='#2ca02c', linewidth=2)
    axes[2].set_ylabel('Violations', fontsize=11, fontweight='bold')
    axes[2].set_xlabel('Iteration', fontsize=11, fontweight='bold')
    axes[2].grid(True, alpha=0.3)
    axes[2].set_xlim(-0.5, len(iterations) - 0.5)
    axes[2].set_ylim(bottom=0)
    
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
        default='../examples/project-1/benchmark',
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
