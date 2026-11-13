"""Makes the small meaning model of the store (ADR 0078 §5) as `doc-vectors.bin` for DocVectors.

Source: sentence-transformers/static-similarity-mrl-multilingual-v1 (Apache-2.0), fixed revision below. Kept: the
first 256 of 1024 values (the model is trained for this cut, Matryoshka), 8 bit per value with one scale per word
piece, and only word pieces in Latin script. Result about 15 MB.

    python3 -m venv venv && ./venv/bin/pip install numpy safetensors
    ./venv/bin/python make.py doc-vectors.bin
"""
import hashlib, json, re, struct, sys, urllib.request
import numpy as np
from safetensors.numpy import load_file

REPO = "sentence-transformers/static-similarity-mrl-multilingual-v1"
REVISION = "b68f4122911bcffcd6e1f695f2d99cd6788972d8"
FILES = {
    "0_StaticEmbedding/tokenizer.json": None,
    "0_StaticEmbedding/model.safetensors": None,
}
DIM = 256
LATIN = re.compile(r"^[a-z0-9ß.,;:!?'\"()\-/&%€$+*=#@_]+$")


def fetch(path):
    local = path.replace("/", "_")
    try:
        open(local, "rb").close()
    except FileNotFoundError:
        url = f"https://huggingface.co/{REPO}/resolve/{REVISION}/{path}"
        print("download", url)
        urllib.request.urlretrieve(url, local)
    return local


def main(out):
    vocab = json.load(open(fetch("0_StaticEmbedding/tokenizer.json")))["model"]["vocab"]
    weights = load_file(fetch("0_StaticEmbedding/model.safetensors"))["embedding.weight"][:, :DIM]
    keep = [(w, i) for w, i in vocab.items() if LATIN.match(w[2:] if w.startswith("##") else w)]
    keep.sort(key=lambda x: x[1])
    rows = weights[[i for _, i in keep]].astype(np.float32)
    scales = np.abs(rows).max(axis=1) / 127.0
    scales[scales == 0] = 1e-12
    values = np.clip(np.round(rows / scales[:, None]), -127, 127).astype(np.int8)
    with open(out, "wb") as f:
        f.write(b"ADV1" + struct.pack("<II", DIM, len(keep)))
        for w, _ in keep:
            b = w.encode("utf-8")
            f.write(struct.pack("<B", len(b)) + b)
        f.write(scales.astype("<f4").tobytes())
        f.write(values.tobytes())
    data = open(out, "rb").read()
    print(out, len(keep), "pieces", len(data), "bytes", "sha256", hashlib.sha256(data).hexdigest())


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "doc-vectors.bin")
