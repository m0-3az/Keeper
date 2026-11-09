# MobileFaceNet model

Vendored and approved for use in Keeper on 2026-10-28.

- File: `mobilefacenet.tflite`
- Source: https://github.com/dangnh0611/MobileFaceNet_TF (Apache-2.0)
- Size: 5,102,424 bytes
- SHA-256: `3b6c938672daa5e86a0c30bdf2afb46a9e25716bf81ae615679c1afb1fca44b2`
- Input: `1 x 112 x 112 x 3` float32, RGB, normalized as `(value - 127.5) / 128`
- Output: `1 x 128` float32 embedding (`embeddings`)
- Runtime: LiteRT 2.3.2 (`org.tensorflow.lite.Interpreter`)

Attribution and the full license note live in `third_party/NOTICE-MobileFaceNet.md`.

The model runs only on-device for owner-presence matching. It never receives the vault key and
cannot decrypt anything. Thresholds are starting points and must be calibrated on a real device.

Do not replace the model without recording its source, license, SHA-256, and approval date in the
repository.
