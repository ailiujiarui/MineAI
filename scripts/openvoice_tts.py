import argparse
import os
import sys
from pathlib import Path


def find_openvoice_root(project_root: Path) -> Path:
    candidate = project_root / ".local" / "OpenVoice"
    if candidate.exists():
        return candidate
    return None


def ensure_repo_on_path(openvoice_root: Path):
    if openvoice_root is None:
        raise RuntimeError(
            "OpenVoice repository not found at .local/OpenVoice. "
            "Clone the official repo or adjust the script to your local install."
        )
    sys.path.insert(0, str(openvoice_root))


def find_hf_snapshot(model_name: str) -> Path:
    cache_root = Path.home() / ".cache" / "huggingface" / "hub" / f"models--{model_name.replace('/', '--')}" / "snapshots"
    if not cache_root.exists():
        return None
    snapshots = [entry for entry in cache_root.iterdir() if entry.is_dir()]
    return snapshots[0] if snapshots else None


def main():
    parser = argparse.ArgumentParser(description="Generate local TTS with OpenVoice V2.")
    parser.add_argument("--text", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--reference-audio", dest="reference_audio", default="")
    parser.add_argument("--voice-name", dest="voice_name", default="default")
    parser.add_argument("--language", default="EN_V2")
    parser.add_argument("--speed", type=float, default=1.0)
    parser.add_argument("--device", default="cuda:0")
    args = parser.parse_args()

    project_root = Path(__file__).resolve().parents[1]
    openvoice_root = find_openvoice_root(project_root)
    ensure_repo_on_path(openvoice_root)

    checkpoints_root = Path(os.environ.get("OPENVOICE_CHECKPOINTS", openvoice_root / "checkpoints_v2"))
    melo_models_root = Path(os.environ.get("OPENVOICE_MELO_MODELS", project_root / ".local" / "MeloModels"))
    converter_dir = checkpoints_root / "converter"
    speaker_se_dir = checkpoints_root / "base_speakers" / "ses"
    melo_dir = melo_models_root / args.language
    melo_config_path = melo_dir / "config.json"
    melo_ckpt_path = melo_dir / "checkpoint.pth"
    source_wav = Path(args.output).with_suffix(".tmp.wav")

    if not converter_dir.exists():
        raise RuntimeError(
            f"OpenVoice V2 checkpoints not found at {converter_dir}. "
            "Download checkpoints_v2 and set OPENVOICE_CHECKPOINTS if needed."
        )

    if not args.reference_audio:
        raise RuntimeError("reference_audio is required for voice cloning.")

    if not melo_config_path.exists() or not melo_ckpt_path.exists():
        raise RuntimeError(
            f"MeloTTS base model files not found for {args.language} at {melo_dir}. "
            "Download config.json and checkpoint.pth or set OPENVOICE_MELO_MODELS."
        )

    os.environ.setdefault("HF_HUB_OFFLINE", "1")
    os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")

    try:
        import torch
        from melo.api import TTS
        import melo.text.english_bert as english_bert
        import melo.text.chinese_bert as chinese_bert
        import melo.text.chinese_mix as chinese_mix
        from openvoice.api import ToneColorConverter, OpenVoiceBaseClass
        from transformers import AutoTokenizer
    except Exception as error:
        raise RuntimeError(
            "OpenVoice dependencies are missing. Install OpenVoice + MeloTTS first."
        ) from error

    english_bert_snapshot = find_hf_snapshot("bert-base-uncased")
    if english_bert_snapshot is not None:
        english_bert.model_id = str(english_bert_snapshot)
        english_bert.tokenizer = AutoTokenizer.from_pretrained(str(english_bert_snapshot), local_files_only=True)
        english_bert.model = None

    chinese_bert_snapshot = find_hf_snapshot("bert-base-chinese")
    if chinese_bert_snapshot is not None:
        chinese_bert.get_bert_feature.__defaults__ = (None, str(chinese_bert_snapshot),)

    multilingual_snapshot = find_hf_snapshot("bert-base-multilingual-uncased")
    if multilingual_snapshot is not None:
        chinese_mix.model_id = str(multilingual_snapshot)
        chinese_mix.tokenizer = AutoTokenizer.from_pretrained(str(multilingual_snapshot), local_files_only=True)

    class LocalToneColorConverter(ToneColorConverter):
        def __init__(self, config_path, device='cpu'):
            OpenVoiceBaseClass.__init__(self, config_path, device=device)
            self.watermark_model = None
            self.version = getattr(self.hps, '_version_', "v1")

    device = args.device
    if "cuda" in device and not torch.cuda.is_available():
        device = "cpu"

    converter = LocalToneColorConverter(str(converter_dir / "config.json"), device=device)
    converter.load_ckpt(str(converter_dir / "checkpoint.pth"))

    target_se = converter.extract_se([args.reference_audio])

    model = TTS(
        language=args.language,
        device=device,
        config_path=str(melo_config_path),
        ckpt_path=str(melo_ckpt_path)
    )
    speaker_ids = model.hps.data.spk2id
    if args.voice_name not in speaker_ids:
        available = ", ".join(sorted(speaker_ids.keys()))
        raise RuntimeError(f"Unknown OpenVoice base speaker '{args.voice_name}'. Available: {available}")

    speaker_id = speaker_ids[args.voice_name]
    speaker_key = args.voice_name.lower().replace("_", "-")
    source_se_path = speaker_se_dir / f"{speaker_key}.pth"
    if not source_se_path.exists():
        raise RuntimeError(f"Missing OpenVoice source speaker embedding at {source_se_path}")
    source_se = torch.load(str(source_se_path), map_location=device)

    model.tts_to_file(args.text, speaker_id, str(source_wav), speed=args.speed)

    converter.convert(
        audio_src_path=str(source_wav),
        src_se=source_se,
        tgt_se=target_se,
        output_path=args.output,
        message="@game-ai"
    )

    try:
        source_wav.unlink(missing_ok=True)
    except Exception:
        pass


if __name__ == "__main__":
    main()
