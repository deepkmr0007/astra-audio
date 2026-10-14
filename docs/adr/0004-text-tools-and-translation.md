# ADR 0004 — Deterministic text tools; no generative model in v1.0

**Status:** accepted

* Summaries are **extractive** (TextRank over TF-IDF) and return verbatim sentences with offsets, so they
  cannot invent facts. Action items, classification, proofreading, formatting, comparison and analysis
  are rule-based and label their output as EXTRACTED or SUGGESTED.
* Rewrite / simplify / expand need an on-device LLM (e.g. llama.cpp + a licensed small model). Linking
  llama.cpp next to whisper.cpp needs a shared ggml build and careful memory management; it was not
  completed and is listed in UNFINISHED.md rather than faked or routed to an online AI.
* Translation: speech→English via Whisper's built-in translate task (offline). Text→text translation
  (e.g. English→Hindi) needs a separate offline MT model and is listed as unfinished. Google ML Kit
  translation was rejected: its model downloads and telemetry happen inside a closed SDK, outside the
  consent gateway.
