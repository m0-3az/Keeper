# MobileFaceNet model attribution

- Model: `mobilefacenet.tflite`
- Source: https://github.com/dangnh0611/MobileFaceNet_TF
- Upstream: https://github.com/sirius-ai/MobileFaceNet_TF (Apache-2.0)
- Vendored path: `presence/src/main/assets/mobilefacenet.tflite`
- License: Apache License 2.0 (see `third_party/MobileFaceNet-LICENSE`)
- Size: 5,102,424 bytes
- Git blob SHA-1: `8faa79f2f65aec66f14368129c40efa287158fec`
- SHA-256: `3b6c938672daa5e86a0c30bdf2afb46a9e25716bf81ae615679c1afb1fca44b2`
- Input: `1 x 112 x 112 x 3`
- Output: `embeddings`
- Approved for use in Keeper on 2026-10-28.
- Runtime: LiteRT 2.3.2 (16 KB page-size aligned)

The model is used only for on-device owner-presence matching. It never receives the vault key
and cannot decrypt anything.
