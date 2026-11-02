# /// script
# requires-python = ">=3.10"
# dependencies = ["onnx==1.23.2"]
# ///
"""Extract Silero v6.2.3's 16 kHz branch for the app's sherpa-onnx VAD."""

import argparse
import hashlib
from pathlib import Path

import onnx
from onnx import helper


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()

    source = args.source.read_bytes()
    if hashlib.sha256(source).hexdigest() != (
        "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3"
    ):
        raise ValueError("Expected the upstream Silero VAD v6.2.3 model")

    model = onnx.load_model_from_string(source)
    selector = next(node for node in model.graph.node if node.name == "If_0")
    branch = next(attr.g for attr in selector.attribute if attr.name == "then_branch")
    # Keep input/state/sr and output/stateN in their original order: sherpa-onnx
    # identifies the model by this ABI, even though sr is now unused.
    outputs = list(model.graph.output)
    del model.graph.node[:]
    model.graph.node.extend(branch.node)
    model.graph.initializer.extend(branch.initializer)
    model.graph.value_info.extend(branch.value_info)
    for branch_output, output in zip(branch.output, outputs, strict=True):
        model.graph.node.append(
            helper.make_node("Identity", [branch_output.name], [output.name])
        )
    model.graph.name = "silero_vad_v6.2.3_16k"
    onnx.checker.check_model(model)
    onnx.save_model(model, args.destination)
    print(f"{len(source)} -> {args.destination.stat().st_size} bytes")
    print(f"SHA-256: {hashlib.sha256(args.destination.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
