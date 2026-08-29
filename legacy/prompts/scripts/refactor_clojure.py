#!/usr/bin/env python3
"""
Clojure File Refactoring Script using AWS Bedrock

This script automates refactoring of Clojure files using Claude on AWS Bedrock.
It reads files, applies a refactoring prompt, and saves the results for review.

Usage:
    # Refactor a single file
    python prompts/scripts/refactor_clojure.py src/laser_show/views/root.clj

    # Refactor all .clj files in a directory
    python prompts/scripts/refactor_clojure.py src/laser_show/views/ --recursive

    # Refactor with custom output directory
    python prompts/scripts/refactor_clojure.py src/laser_show/views/ -o refactored/

    # Dry run (just show what would be processed)
    python prompts/scripts/refactor_clojure.py src/laser_show/views/ --dry-run

Environment Variables:
    AWS_PROFILE - AWS profile to use (optional, uses default if not set)
    AWS_REGION - AWS region (default: us-east-1)
"""

import argparse
import json
import os
import re
import sys
import time
from pathlib import Path
from typing import Optional

import boto3
from botocore.config import Config


# Configuration
DEFAULT_MODEL_ID = "anthropic.claude-opus-4-5-20251101-v1:0"
DEFAULT_REGION = "us-east-1"
MAX_TOKENS = 16384

# Path relative to this script's location
SCRIPT_DIR = Path(__file__).parent
PROMPT_TEMPLATE_PATH = SCRIPT_DIR.parent / "refactor-clojure-file.md"

# Rate limiting
REQUESTS_PER_MINUTE = 10
REQUEST_DELAY = 60.0 / REQUESTS_PER_MINUTE


def get_bedrock_client(region: str, profile: Optional[str] = None):
    """Create a Bedrock runtime client."""
    config = Config(
        retries={"max_attempts": 3, "mode": "adaptive"},
        read_timeout=300,
        connect_timeout=10,
    )

    session_kwargs = {}
    if profile:
        session_kwargs["profile_name"] = profile

    session = boto3.Session(**session_kwargs)
    return session.client(
        "bedrock-runtime",
        region_name=region,
        config=config,
    )


def load_prompt_template(template_path: str) -> str:
    """Load the prompt template from file."""
    with open(template_path, "r", encoding="utf-8") as f:
        return f.read()


def build_prompt(template: str, file_path: str, file_content: str) -> str:
    """Build the full prompt by substituting placeholders."""
    prompt = template.replace("{{FILE_PATH}}", file_path)
    prompt = prompt.replace("{{FILE_CONTENT}}", file_content)
    return prompt


def extract_code_from_response(response_text: str) -> Optional[str]:
    """Extract the Clojure code block from the LLM response."""
    # Look for the last clojure code block (the refactored code)
    pattern = r"```clojure\n(.*?)```"
    matches = re.findall(pattern, response_text, re.DOTALL)

    if matches:
        # Return the last code block (should be the refactored code)
        return matches[-1].strip()

    return None


def extract_changes_summary(response_text: str) -> str:
    """Extract the summary of changes from the response."""
    # Get text before the code block
    parts = response_text.split("```clojure")
    if parts:
        return parts[0].strip()
    return ""


def call_bedrock(
    client,
    model_id: str,
    prompt: str,
    max_tokens: int = MAX_TOKENS,
) -> str:
    """Call AWS Bedrock with the given prompt."""
    # Format for Claude on Bedrock
    body = {
        "anthropic_version": "bedrock-2023-05-31",
        "max_tokens": max_tokens,
        "messages": [
            {
                "role": "user",
                "content": prompt,
            }
        ],
    }

    response = client.invoke_model(
        modelId=model_id,
        contentType="application/json",
        accept="application/json",
        body=json.dumps(body),
    )

    response_body = json.loads(response["body"].read())
    return response_body["content"][0]["text"]


def refactor_file(
    client,
    model_id: str,
    template: str,
    file_path: Path,
    output_dir: Path,
    verbose: bool = False,
) -> bool:
    """Refactor a single Clojure file."""
    try:
        # Read the file
        with open(file_path, "r", encoding="utf-8") as f:
            content = f.read()

        if verbose:
            print(f"  Reading: {file_path} ({len(content)} chars)")

        # Build prompt
        prompt = build_prompt(template, str(file_path), content)

        if verbose:
            print(f"  Prompt size: {len(prompt)} chars")

        # Call Bedrock
        if verbose:
            print("  Calling Bedrock...")

        response = call_bedrock(client, model_id, prompt)

        # Extract code
        refactored_code = extract_code_from_response(response)

        if not refactored_code:
            print(f"  WARNING: Could not extract code from response for {file_path}")
            # Save the full response for debugging
            debug_path = output_dir / f"{file_path.stem}_debug.txt"
            with open(debug_path, "w", encoding="utf-8") as f:
                f.write(response)
            print(f"  Saved debug output to: {debug_path}")
            return False

        # Save refactored code
        # Preserve directory structure in output
        relative_path = file_path
        if file_path.is_absolute():
            try:
                relative_path = file_path.relative_to(Path.cwd())
            except ValueError:
                relative_path = Path(file_path.name)

        output_path = output_dir / relative_path
        output_path.parent.mkdir(parents=True, exist_ok=True)

        with open(output_path, "w", encoding="utf-8") as f:
            f.write(refactored_code)
            f.write("\n")  # Ensure trailing newline

        # Save changes summary
        summary = extract_changes_summary(response)
        if summary:
            summary_path = output_path.with_suffix(".changes.md")
            with open(summary_path, "w", encoding="utf-8") as f:
                f.write(f"# Changes for {file_path}\n\n")
                f.write(summary)

        if verbose:
            print(f"  Saved to: {output_path}")

        return True

    except Exception as e:
        print(f"  ERROR processing {file_path}: {e}")
        return False


def find_clojure_files(path: Path, recursive: bool = False) -> list[Path]:
    """Find all Clojure files in the given path."""
    if path.is_file():
        if path.suffix == ".clj":
            return [path]
        return []

    if recursive:
        return list(path.rglob("*.clj"))
    else:
        return list(path.glob("*.clj"))


def main():
    parser = argparse.ArgumentParser(
        description="Refactor Clojure files using AWS Bedrock Claude",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument(
        "path",
        type=Path,
        help="File or directory to refactor",
    )
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        default=Path("refactored"),
        help="Output directory for refactored files (default: refactored/)",
    )
    parser.add_argument(
        "-r",
        "--recursive",
        action="store_true",
        help="Recursively process directories",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Show what would be processed without making changes",
    )
    parser.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        help="Verbose output",
    )
    parser.add_argument(
        "--model",
        default=DEFAULT_MODEL_ID,
        help=f"Bedrock model ID (default: {DEFAULT_MODEL_ID})",
    )
    parser.add_argument(
        "--region",
        default=os.environ.get("AWS_REGION", DEFAULT_REGION),
        help=f"AWS region (default: {DEFAULT_REGION})",
    )
    parser.add_argument(
        "--profile",
        default=os.environ.get("AWS_PROFILE"),
        help="AWS profile to use",
    )
    parser.add_argument(
        "--template",
        type=Path,
        default=PROMPT_TEMPLATE_PATH,
        help=f"Path to prompt template (default: {PROMPT_TEMPLATE_PATH})",
    )

    args = parser.parse_args()

    # Validate inputs
    if not args.path.exists():
        print(f"Error: Path does not exist: {args.path}")
        sys.exit(1)

    if not args.template.exists():
        print(f"Error: Prompt template not found: {args.template}")
        sys.exit(1)

    # Find files to process
    files = find_clojure_files(args.path, args.recursive)

    if not files:
        print(f"No .clj files found in: {args.path}")
        sys.exit(0)

    print(f"Found {len(files)} Clojure file(s) to process")

    if args.dry_run:
        print("\nDry run - files that would be processed:")
        for f in files:
            print(f"  {f}")
        sys.exit(0)

    # Load template
    template = load_prompt_template(str(args.template))
    if args.verbose:
        print(f"Loaded template: {args.template}")

    # Create output directory
    args.output.mkdir(parents=True, exist_ok=True)
    print(f"Output directory: {args.output}")

    # Initialize Bedrock client
    print(f"Connecting to AWS Bedrock ({args.region})...")
    client = get_bedrock_client(args.region, args.profile)

    # Process files
    success_count = 0
    error_count = 0

    for i, file_path in enumerate(files, 1):
        print(f"\n[{i}/{len(files)}] Processing: {file_path}")

        success = refactor_file(
            client=client,
            model_id=args.model,
            template=template,
            file_path=file_path,
            output_dir=args.output,
            verbose=args.verbose,
        )

        if success:
            success_count += 1
        else:
            error_count += 1

        # Rate limiting between requests
        if i < len(files):
            if args.verbose:
                print(f"  Waiting {REQUEST_DELAY:.1f}s (rate limiting)...")
            time.sleep(REQUEST_DELAY)

    # Summary
    print(f"\n{'=' * 50}")
    print(f"Refactoring complete!")
    print(f"  Successful: {success_count}")
    print(f"  Errors: {error_count}")
    print(f"  Output directory: {args.output}")

    if success_count > 0:
        print(f"\nNext steps:")
        print(f"  1. Review changes in {args.output}/")
        print(f"  2. Compare with originals using diff or git diff --no-index")
        print(f"  3. Copy approved changes back to source")


if __name__ == "__main__":
    main()
