#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
重新生成番茄网页正文的字体映射表（fontmap.json）。

背景
----
番茄网页版把正文里的高频字换成私有区码位（U+E000 起），再用一套自定义字体
渲染回真字。直接抓到的文本是乱码。

该字体是"思源黑体 SC 的子集"，字形名保留了源字体编号，但番茄对汉字轮廓做过
缩放，所以不能按编号或轮廓点直接对齐。本脚本改用「渲染成位图 + 模板匹配」：
把 362 个加密字形逐个和 2 万多个候选字比对，取最相似的那个。

用法
----
    python tools/gen-fontmap.py                      # 自动下载字体并生成
    python tools/gen-fontmap.py --fq a.otf --base b.otf
    python tools/gen-fontmap.py --out plugin/src/main/resources/fontmap.json

依赖：pip install fonttools pillow numpy
"""

import argparse
import json
import os
import sys
import urllib.request

FQ_URL = "https://lf6-awef.bytetos.com/obj/awesome-font/c/dc027189e0ba4cd.otf"
BASE_URLS = [
    "https://fastly.jsdelivr.net/gh/adobe-fonts/source-han-sans@release/OTF/SimplifiedChinese/SourceHanSansSC-Normal.otf",
    "https://cdn.jsdelivr.net/gh/adobe-fonts/source-han-sans@release/OTF/SimplifiedChinese/SourceHanSansSC-Normal.otf",
]

SIZE = 32
FONT_PX = 96


def download(url, path):
    if os.path.exists(path) and os.path.getsize(path) > 100_000:
        print(f"  已存在，跳过下载：{path}")
        return path
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    print(f"  下载 {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=240) as r, open(path, "wb") as f:
        f.write(r.read())
    print(f"  已保存 {path}（{os.path.getsize(path)} 字节）")
    return path


def fetch_base(path):
    last = None
    for url in BASE_URLS:
        try:
            return download(url, path)
        except Exception as e:  # noqa: BLE001
            last = e
    raise SystemExit(f"基准字体下载失败：{last}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fq", default="fonts/fq.otf", help="番茄混淆字体")
    ap.add_argument("--base", default="fonts/shs.otf", help="思源黑体基准字体")
    ap.add_argument("--out", default="plugin/src/main/resources/fontmap.json")
    ap.add_argument("--score", type=float, default=0.90, help="低于该相似度会打印提示")
    args = ap.parse_args()

    try:
        from PIL import Image, ImageDraw, ImageFont
        import numpy as np
    except ImportError:
        raise SystemExit("缺少依赖：pip install fonttools pillow numpy")
    from fontTools.ttLib import TTFont

    print("[1/4] 准备字体文件")
    if not os.path.exists(args.fq):
        download(FQ_URL, args.fq)
    if not os.path.exists(args.base):
        fetch_base(args.base)

    def render(font, ch):
        img = Image.new("L", (200, 200), 255)
        ImageDraw.Draw(img).text((40, 40), ch, font=font, fill=0)
        bb = img.getbbox()
        if not bb or bb[2] - bb[0] < 3 or bb[3] - bb[1] < 3:
            return None
        a = np.asarray(img.crop(bb).resize((SIZE, SIZE), Image.LANCZOS), dtype=np.float32)
        v = (255.0 - a) / 255.0
        n = np.linalg.norm(v)
        return (v / n).ravel() if n > 1e-6 else None

    print("[2/4] 渲染候选字（CJK + ASCII + 标点）")
    fq_font = ImageFont.truetype(args.fq, FONT_PX)
    base_font = ImageFont.truetype(args.base, FONT_PX)
    cps = (
        list(range(0x20, 0x7F))
        + list(range(0x4E00, 0x9FA6))
        + list(range(0x3000, 0x3040))
        + list(range(0xFF01, 0xFF61))
    )
    vecs, used = [], []
    for cp in cps:
        v = render(base_font, chr(cp))
        if v is not None:
            vecs.append(v)
            used.append(cp)
    if not vecs:
        raise SystemExit("基准字体没有渲染出任何候选字")
    M = np.vstack(vecs)
    print(f"  候选 {len(used)} 个")

    print("[3/4] 渲染加密字形并匹配")
    pua = sorted(TTFont(args.fq).getBestCmap())
    tvecs, tcp = [], []
    for cp in pua:
        v = render(fq_font, chr(cp))
        if v is not None:
            tvecs.append(v)
            tcp.append(cp)
    if not tvecs:
        raise SystemExit("混淆字体没有渲染出任何字形")
    T = np.vstack(tvecs)
    sim = T @ M.T
    best = sim.argmax(axis=1)
    score = sim.max(axis=1)

    mapping, low = {}, []
    for i, cp in enumerate(tcp):
        ch = chr(used[best[i]])
        mapping[cp] = ch
        if float(score[i]) < args.score:
            low.append((f"{cp:04X}", ch, round(float(score[i]), 3)))

    print(f"  完成 {len(mapping)} 个字，低分 {len(low)} 个")
    for x in low[:20]:
        print("    低分：", x)

    print("[4/4] 写出映射表")
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    data = {f"{k:04X}": v for k, v in sorted(mapping.items())}
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print(f"  {args.out}（{len(data)} 条，{os.path.getsize(args.out)} 字节）")
    print("完成。修改后需要重新执行 build-offline.sh 打包。")


if __name__ == "__main__":
    sys.exit(main())
