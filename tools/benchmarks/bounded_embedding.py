"""Bi-encoder bounded choice: cosine(query, option label) over the offered menu.

Shared by the embedding evaluator backend and its matched-budget trainer. The
menu is scored independently per option, so presentation order cannot change a
choice. Prompts follow the EmbeddingGemma 1/2 model cards' retrieval format.
"""
import json
from pathlib import Path

QUERY_PREFIX = "task: search result | query: "
DOCUMENT_PREFIX = "title: none | text: "


def load_text_embedder(path, device="cuda", dtype=None):
    """Load a SentenceTransformer, dropping EmbeddingGemma 2 vision/audio towers."""
    import torch
    from sentence_transformers import SentenceTransformer
    config = json.loads((Path(path) / "config.json").read_text())
    config_kwargs = {key: None for key in ("vision_config", "audio_config") if key in config}
    model_kwargs = {"torch_dtype": dtype} if dtype is not None else {}
    model = SentenceTransformer(str(path), device=device, local_files_only=True,
                                config_kwargs=config_kwargs, model_kwargs=model_kwargs)
    if dtype is torch.float16:
        raise ValueError("EmbeddingGemma activations overflow float16; use bfloat16 or float32")
    return model


def features(model, texts):
    """Tokenized, device-placed inputs for a differentiable forward pass."""
    prepare = getattr(model, "preprocess", None) or model.tokenize
    batch = prepare(texts)
    return {k: v.to(model.device) if hasattr(v, "to") else v for k, v in batch.items()}


def embed(model, texts, truncate_dim=None):
    """Differentiable L2-normalized embeddings, optionally Matryoshka-truncated."""
    import torch
    vectors = model(features(model, texts))["sentence_embedding"]
    if truncate_dim:
        vectors = vectors[:, :truncate_dim]
    return torch.nn.functional.normalize(vectors.float(), dim=-1)


def menu_texts(turn, options=None, request_only=False):
    """request_only drops host status, so state-dependent controls are unknowable."""
    options = options if options is not None else turn["options"]
    query = json.loads(turn["query"])["request"] if request_only else turn["query"]
    return QUERY_PREFIX + query, [DOCUMENT_PREFIX + bound["label"] for bound in options.values()]
