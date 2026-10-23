# Wake-word models

| File | What it is | License |
|---|---|---|
| `melspectrogram.onnx` | Turns raw 16 kHz audio into mel-spectrogram features | Apache-2.0, openWakeWord (David Scripka) |
| `embedding_model.onnx` | Turns those features into speech embeddings | Apache-2.0, Google speech embedding converted by openWakeWord |
| `hey_claude.onnx` | **Not included.** Your "Hey Claude" detector — train it, then drop it here | Yours |

The app looks for exactly `hey_claude.onnx` in this folder. Without it, Claude Copilot still works by
tapping the orb; it just can't wake on your voice. Training steps are in the repo README.
