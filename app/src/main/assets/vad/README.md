# Silero VAD

- Version: snakers4/silero-vad v6.2.3
- Source: https://github.com/snakers4/silero-vad/blob/v6.2.3/src/silero_vad/data/silero_vad.onnx
- Upstream SHA-256: 1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3
- Bundled SHA-256: 7a45d7846d186e76a17a2ed32ff4c8326d49b7b66255604eded8f7c0bb308e23
- Bundled size: 1,311,632 bytes (upstream: 2,327,524 bytes)
- License: ../licenses/SileroVAD-LICENSE (MIT)

The bundled model contains only the upstream 16 kHz branch. The 8 kHz network
and the top-level sample-rate selector are removed; the 16 kHz weights and
operators are unchanged. Inputs (`input`, `state`, `sr`) and outputs (`output`,
`stateN`) retain their original order for sherpa-onnx compatibility. `sr` remains
an unused interface input; this model supports only 16 kHz audio.

Each compute call receives 64 context samples followed by 512 new samples.
Kotlin owns context buffering and endpoint decisions; sherpa-onnx owns recurrent
model state, reset on every recording session.

To reproduce from the upstream model, run from the repository root:

```sh
uv run scripts/prune_silero_vad.py /path/to/upstream/silero_vad.onnx app/src/main/assets/vad/silero-v6.2.3.onnx
```

The script requires the upstream SHA-256 above and validates the resulting ONNX
graph before saving it. It does not quantize or re-export the network.
