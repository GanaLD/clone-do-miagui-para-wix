from pathlib import Path
import hashlib
import shutil
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
LIBS = ROOT / "app" / "libs"
ASSETS = ROOT / "app" / "src" / "main" / "assets" / "jarvis_voice"
AAR = LIBS / "sherpa-onnx-1.13.8.aar"
MODEL_DIR = ASSETS / "vits-piper-pt_BR-faber-medium"

AAR_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar"
AAR_SHA256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pt_BR-faber-medium.tar.bz2"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def download(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": "JARVIS-Android-Build/1.11"})
    with urllib.request.urlopen(request, timeout=180) as response, destination.open("wb") as out:
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


def model_ready() -> bool:
    return (
        (MODEL_DIR / "pt_BR-faber-medium.onnx").is_file()
        and (MODEL_DIR / "tokens.txt").is_file()
        and (MODEL_DIR / "espeak-ng-data").is_dir()
    )


def prepare_model() -> None:
    if model_ready():
        print(f"Bundled JARVIS model ready: {MODEL_DIR}")
        return

    ASSETS.mkdir(parents=True, exist_ok=True)
    if MODEL_DIR.exists():
        shutil.rmtree(MODEL_DIR)

    with tempfile.TemporaryDirectory(prefix="jarvis-voice-") as tmp:
        tmp_path = Path(tmp)
        archive = tmp_path / "voice.tar.bz2"
        extract_dir = tmp_path / "extract"
        extract_dir.mkdir()
        print("Downloading pt-BR male neural voice model (Faber medium)...")
        download(MODEL_URL, archive)
        print(f"Voice archive downloaded: {archive.stat().st_size} bytes, sha256={sha256(archive)}")
        with tarfile.open(archive, "r:bz2") as tar:
            tar.extractall(extract_dir, filter="data")

        candidates = list(extract_dir.rglob("pt_BR-faber-medium.onnx"))
        if not candidates:
            raise RuntimeError("Voice archive does not contain pt_BR-faber-medium.onnx")
        source_dir = candidates[0].parent
        if not (source_dir / "tokens.txt").is_file():
            raise RuntimeError("Voice archive does not contain tokens.txt")
        if not (source_dir / "espeak-ng-data").is_dir():
            raise RuntimeError("Voice archive does not contain espeak-ng-data")
        shutil.copytree(source_dir, MODEL_DIR)

    if not model_ready():
        raise RuntimeError("Bundled JARVIS model failed verification after extraction")
    total = sum(p.stat().st_size for p in MODEL_DIR.rglob("*") if p.is_file())
    print(f"Bundled JARVIS voice prepared: {total} bytes")


if __name__ == "__main__":
    prepare_aar()
    prepare_model()
    print("Bundled offline JARVIS voice assets are ready")
