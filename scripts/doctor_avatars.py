"""医生头像生成与上传（票 59，ADR-0023 C 端消费约定）。

两步（均幂等，可重跑）：
1. 生成：PIL 绘制虚构占位头像（渐变底 + 姓氏文字，无肖像权问题），
   落 scripts/assets/doctor-avatars/<拼音>.jpg；
2. 上传：把该目录图片上传至云端 MinIO，key 为 photos/2026-08-07/<拼音>.jpg，
   与 seed.sql doctors.photo_url 严格一致，传后 stat 验证。

后续如需写实头像：仅替换 scripts/assets/doctor-avatars/ 目录图片后重跑本脚本即可，
管线不变。不打印 .env 中任何凭据；连接参数仅本进程内使用。

用法：
    uv run python scripts/doctor_avatars.py            # 生成（跳过已存在）+ 上传（跳过已存在）+ 验证
    uv run python scripts/doctor_avatars.py --force    # 覆盖重新生成并强制重传
    uv run python scripts/doctor_avatars.py --upload-only
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

# 与 seed.sql doctors 表逐行对齐（id、文件名拼音、姓名、性别、出生年、职称）；
# 修改 seed 医生数据时须同步更新此处。
DOCTORS: list[tuple[int, str, str, str, int, str]] = [
    (1, "lin-zhiyuan", "林知远", "男", 1975, "主任医师"),
    (2, "zhou-anning", "周安宁", "女", 1982, "副主任医师"),
    (3, "chen-qinghe", "陈清禾", "女", 1988, "主治医师"),
    (4, "su-mingzhe", "苏明哲", "男", 1973, "主任医师"),
    (5, "li-wanqing", "李婉清", "女", 1985, "副主任医师"),
    (6, "zhao-qiming", "赵启明", "男", 1970, "主任医师"),
    (7, "wu-peishan", "吴佩珊", "女", 1990, "主治医师"),
    (8, "sun-lihang", "孙立航", "男", 1976, "主任医师"),
    (9, "zheng-yawen", "郑雅文", "女", 1983, "副主任医师"),
    (10, "ma-junjie", "马俊杰", "男", 1978, "主任医师"),
    (11, "he-jingyi", "何静怡", "女", 1986, "副主任医师"),
    (12, "huang-zhiyuan", "黄志远", "男", 1972, "主任医师"),
    (13, "liang-shuyao", "梁书瑶", "女", 1989, "主治医师"),
    (14, "feng-xuesong", "冯雪松", "男", 1974, "主任医师"),
    (15, "han-simin", "韩思敏", "女", 1987, "副主任医师"),
]

# seed.sql doctors.photo_url 的日期段（key 必须与 seed 严格一致）
OBJECT_DATE = "2026-08-07"
SIZE = 512
ASSET_DIR = Path(__file__).parent / "assets" / "doctor-avatars"

# 每人生成固定色相（按 id 均匀分布），保证可复现
_HUE_START = 140  # 医疗绿打底
_HUE_STEP = 24


def _font(size: int):
    for name in ("msyh.ttc", "msyhbd.ttc", "simhei.ttf", "simsun.ttc"):
        path = Path(os.environ.get("WINDIR", r"C:\Windows")) / "Fonts" / name
        if path.exists():
            try:
                from PIL import ImageFont

                return ImageFont.truetype(str(path), size)
            except Exception:
                continue
    return None


def generate_avatar(idx: int, pinyin: str, name: str, gender: str, birth_year: int, title: str) -> None:
    """绘制渐变底 + 姓氏文字占位头像；写 jpg 到 ASSET_DIR/<拼音>.jpg。"""
    from PIL import Image, ImageDraw

    hue = (_HUE_START + idx * _HUE_STEP) % 360
    top = _hsv(hue, 0.42, 0.95)
    bottom = _hsv((hue + 30) % 360, 0.55, 0.78)
    image = Image.new("RGB", (SIZE, SIZE), top)
    draw = ImageDraw.Draw(image)
    # 垂直渐变
    for y in range(SIZE):
        ratio = y / (SIZE - 1)
        color = tuple(round(top[i] + (bottom[i] - top[i]) * ratio) for i in range(3))
        draw.line([(0, y), (SIZE, y)], fill=color)
    surname = name[0]
    font = _font(220)
    if font is not None:
        box = draw.textbbox((0, 0), surname, font=font)
        draw.text(
            ((SIZE - (box[2] - box[0])) / 2 - box[0], (SIZE - (box[3] - box[1])) / 2 - box[1]),
            surname,
            font=font,
            fill=(255, 255, 255),
        )
    # 职称小字（医生人设信息）
    small = _font(44)
    if small is not None:
        box = draw.textbbox((0, 0), title, font=small)
        draw.text(
            ((SIZE - (box[2] - box[0])) / 2 - box[0], SIZE - 150 - box[1]),
            title,
            font=small,
            fill=(255, 255, 255),
        )
    asset_dir = ASSET_DIR
    asset_dir.mkdir(parents=True, exist_ok=True)
    image.save(asset_dir / f"{pinyin}.jpg", "JPEG", quality=90)


def _hsv(hue: float, sat: float, val: float) -> tuple[int, int, int]:
    import colorsys

    r, g, b = colorsys.hsv_to_rgb(hue / 360, sat, val)
    return round(r * 255), round(g * 255), round(b * 255)


def _env() -> dict[str, str]:
    env: dict[str, str] = {}
    env_file = Path(__file__).parent.parent / ".env"
    for line in env_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and "=" in line and not line.startswith("#"):
            key, _, value = line.partition("=")
            env[key.strip()] = value.strip()
    return env


def _minio_client(env: dict[str, str]):
    from minio import Minio

    endpoint = env["MINIO_ENDPOINT"]
    host = endpoint.replace("http://", "").replace("https://", "")
    return Minio(
        host,
        access_key=env["MINIO_ACCESS_KEY"],
        secret_key=env["MINIO_SECRET_KEY"],
        secure=endpoint.startswith("https"),
    )


def upload_all(force: bool) -> None:
    if not ASSET_DIR.exists():
        sys.exit("头像目录不存在，请先运行生成步骤")
    env = _env()
    if not env.get("MINIO_ENABLED", "").lower() in ("true", "1", "yes"):
        sys.exit("MINIO_ENABLED 未开启，拒绝上传")
    client = _minio_client(env)
    bucket = env.get("MINIO_BUCKET", "zhiyu-photos")
    uploaded = 0
    skipped = 0
    for _, pinyin, *_ in DOCTORS:
        asset = ASSET_DIR / f"{pinyin}.jpg"
        if not asset.exists():
            print(f"缺失本地头像，跳过: {asset.name}")
            continue
        key = f"photos/{OBJECT_DATE}/{pinyin}.jpg"
        if not force:
            try:
                client.stat_object(bucket, key)
                skipped += 1
                continue
            except Exception:
                pass
        client.fput_object(bucket, key, str(asset), content_type="image/jpeg")
        # 传后验证：stat 存在性
        client.stat_object(bucket, key)
        uploaded += 1
        print(f"上传并验证: {key}")
    print(f"上传 {uploaded} 张，跳过已存在 {skipped} 张")


def main() -> None:
    parser = argparse.ArgumentParser(description="医生头像生成与上传（票 59）")
    parser.add_argument("--force", action="store_true", help="覆盖重新生成并强制重传")
    parser.add_argument("--upload-only", action="store_true", help="只上传，不生成")
    args = parser.parse_args()

    if not args.upload_only:
        from PIL import Image  # noqa: F401 确保 pillow 可用（缺失时报错提示）

        for i, doctor in enumerate(DOCTORS):
            idx, pinyin, name, gender, birth_year, title = doctor
            asset = ASSET_DIR / f"{pinyin}.jpg"
            if asset.exists() and not args.force:
                continue
            generate_avatar(idx, pinyin, name, gender, birth_year, title)
            print(f"生成: {asset.name}")
    upload_all(args.force)


if __name__ == "__main__":
    main()
