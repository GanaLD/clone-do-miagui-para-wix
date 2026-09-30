from pathlib import Path
import hashlib
import shutil
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
LIBS = ROOT / "app" / "libs"
ASSETS = ROOT / "app" / "src" / "main" / "assets"
JARVIS_ASSETS = ASSETS / "jarvis_voice"
HELENA_ASSETS = ASSETS / "helena_voice"
AAR = LIBS / "sherpa-onnx-1.13.8.aar"

JARVIS_MODEL_DIR = JARVIS_ASSETS / "vits-piper-pt_BR-faber-medium"
JARVIS_MODEL_FILE = "pt_BR-faber-medium.onnx"
JARVIS_MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pt_BR-faber-medium.tar.bz2"

HELENA_MODEL_DIR = HELENA_ASSETS / "vits-piper-pt_BR-dii-high"
HELENA_MODEL_FILE = "pt_BR-dii-high.onnx"
HELENA_MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pt_BR-dii-high.tar.bz2"

AAR_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar"
AAR_SHA256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def download(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": "JARVIS-Android-Build/1.12"})
    with urllib.request.urlopen(request, timeout=240) as response, destination.open("wb") as out:
        shutil.copyfileobj(response, out, length=1024 * 1024)


def prepare_aar() -> None:
    if AAR.is_file() and sha256(AAR) == AAR_SHA256:
        print(f"Sherpa AAR ready: {AAR} ({AAR.stat().st_size} bytes)")
        return
    if AAR.exists():
        AAR.unlink()
    print("Downloading sherpa-onnx Android runtime...")
    download(AAR_URL, AAR)
    digest = sha256(AAR)
    if digest != AAR_SHA256:
        AAR.unlink(missing_ok=True)
        raise RuntimeError(f"Unexpected sherpa AAR digest: {digest}")
    print(f"Sherpa AAR verified: {digest}")


def model_ready(model_dir: Path, model_file: str) -> bool:
    return (
        (model_dir / model_file).is_file()
        and (model_dir / "tokens.txt").is_file()
        and (model_dir / "espeak-ng-data").is_dir()
    )


def prepare_model(label: str, url: str, model_dir: Path, model_file: str) -> None:
    if model_ready(model_dir, model_file):
        print(f"Bundled {label} model ready: {model_dir}")
        return

    model_dir.parent.mkdir(parents=True, exist_ok=True)
    if model_dir.exists():
        shutil.rmtree(model_dir)

    with tempfile.TemporaryDirectory(prefix=f"{label.lower()}-voice-") as tmp:
        tmp_path = Path(tmp)
        archive = tmp_path / "voice.tar.bz2"
        extract_dir = tmp_path / "extract"
        extract_dir.mkdir()
        print(f"Downloading {label} neural voice model...")
        download(url, archive)
        print(f"{label} archive downloaded: {archive.stat().st_size} bytes, sha256={sha256(archive)}")
        with tarfile.open(archive, "r:bz2") as tar:
            tar.extractall(extract_dir, filter="data")

        candidates = list(extract_dir.rglob(model_file))
        if not candidates:
            raise RuntimeError(f"{label} voice archive does not contain {model_file}")
        source_dir = candidates[0].parent
        if not (source_dir / "tokens.txt").is_file():
            raise RuntimeError(f"{label} voice archive does not contain tokens.txt")
        if not (source_dir / "espeak-ng-data").is_dir():
            raise RuntimeError(f"{label} voice archive does not contain espeak-ng-data")
        shutil.copytree(source_dir, model_dir)

    if not model_ready(model_dir, model_file):
        raise RuntimeError(f"Bundled {label} model failed verification after extraction")
    total = sum(p.stat().st_size for p in model_dir.rglob("*") if p.is_file())
    print(f"Bundled {label} voice prepared: {total} bytes")


def write_voice_notice() -> None:
    notice = ASSETS / "VOICE_MODELS_NOTICE.txt"
    notice.write_text(
        "JARVIS voice\n"
        "Model: vits-piper-pt_BR-faber-medium\n"
        "Source: rhasspy/piper-voices / sherpa-onnx packaged model\n"
        "Dataset license reported by model card: CC0.\n\n"
        "HELENA voice\n"
        "Model: vits-piper-pt_BR-dii-high\n"
        "Source: OpenVoiceOS/TigreGotico, packaged by sherpa-onnx.\n"
        "Voice/model license: CC BY-NC-ND 4.0 as reported by the upstream model card.\n"
        "This prototype build is for non-commercial use unless a commercial license is obtained from the rights holder.\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    prepare_aar()
    prepare_model("JARVIS", JARVIS_MODEL_URL, JARVIS_MODEL_DIR, JARVIS_MODEL_FILE)
    prepare_model("HELENA", HELENA_MODEL_URL, HELENA_MODEL_DIR, HELENA_MODEL_FILE)
    write_voice_notice()
    print("Bundled offline JARVIS + HELENA voice assets are ready")
