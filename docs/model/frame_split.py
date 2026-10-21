# -*- coding: utf-8 -*-
"""v4: `other` という黒箱をやめ、実プロファイラの内訳で置き換える。

[SRC] Minecraft クライアントプロファイラ実測:
  rendering 77.58% of frame
    level rendering = 79.08% of rendering
      entities           34.82% of level rendering
      terrain            21.90% of level rendering
      other render types 36.44% of level rendering (半透明/cutout/雲/天候/パーティクル)
  GUI rendering 17.62% of frame
  ticking        5.33% of frame
  残り           ~15.7% (確保/GC、テクスチャアニメ、その他)
"""
LV = 0.7758 * 0.7908
SHARE = {
    'entities':   LV * 0.3482,
    'terrain':    LV * 0.2190,
    'rendertype': LV * 0.3644,
    'gui':        0.1762,
    'tick':       0.0533,
}
SHARE['misc'] = 1 - sum(SHARE.values())
assert abs(sum(SHARE.values()) - 1.0) < 1e-9

# 対象Modスタックが各区分で既に取っている割合(公称機能から)
MOD_TAKES = {'entities': 0.85, 'terrain': 0.90, 'rendertype': 0.50,
             'gui': 0.00, 'tick': 0.60, 'misc': 0.20}

# AMD-Faster の全利得と配線状態。
# 利得は各クラスが自分で文書化している「相対」削減率を使う。
# 絶対 ms は使わない -- 後述のとおり、クラスの javadoc の ms は
# 既に誤りと判明したドローコール単価(0.02 ms)を継承している。
CAP = {
    # 二つの別物を混ぜていたのを分けた。カリング(EntityCulling が既に取る)と
    # ドローコスト(誰も取っていない)は別の軸で、0.85 という一つの数にしたのが誤り。
    #   カリング 78.81% (HierarchicalFrustumCuller, CI緑のテストが保証)
    #   残った 21.2% の描画をインスタンス化 -> 公表ベンチ 平均FPS +155% (=2.55x)
    #     docs/notes/24。同ノート自身が「NVIDIA上の数字でそのまま期待しない」と警告。
    #     ここでは控えめに 2.0x として使う -> 残り 21.2% x 0.5 = 10.6%、合計 89.4% 削減。
    # 注意: インスタンス描画を実際に発行するにはレンダーバックエンドが要る。未構築。
    'entities':   (0.894, False, 'カリング 78.81% + インスタンス描画(note24, +155%を2.0xに減額)'),
    'terrain':    (0.62,  True,  'DrawCallMerger -62% + 光のみ/差分再構築'),
    # 雲 -95% / 天候 -98% / 半透明ソート -85% / BEジオメトリのベイク
    'rendertype': (0.85,  False, 'CloudMeshCache 95% / WeatherMeshCache 98% / TranslucentSortCache 85%'),
    # GuiBatcher: 200要素 -> 30ドロー(85%減)、HudTextureCache: 十数ドロー -> 1 blit
    'gui':        (0.85,  False, 'GuiBatcher 85% + HudUpdateScheduler + HudTextureCache'),
    'tick':       (0.55,  False, 'FastRandom 4-8x + ActivationRangeScheduler'),
    # AnimatedTextureScheduler -70% と確保削減。ただし GC の JVM フラグは mod から変えられない
    'misc':       (0.45,  False, 'AnimatedTextureScheduler 70% + 確保削減'),
}

# javadoc の絶対 ms を信じない理由の記録。
#   GuiBatcher        "200要素 x 0.02 ms = 4 ms/フレーム"
#   CloudMeshCache    "約 2 ms/フレーム"
#   WeatherMeshCache  "約 1.2 ms/フレーム"
#   AnimatedTextureScheduler "60枚 x 0.02 ms = 1.2 ms/フレーム"
#   TranslucentSortCache     "3000クアッド x 0.3 us = 0.9 ms/フレーム"
# これらを足すと 9.3 ms で、シナリオAのフレーム(7.72 ms)を既に超える。
# 0.02 ms/要素はドローコール1本 0.02 ms と同じ誤りで、GL実測(0.41us draw + 0.48us texbind)の
# 約10倍。プロファイラの比率(=実測)を優先し、javadoc からは相対削減率だけを使う。
JAVADOC_MS_INFLATED_BY = 10.0

def fps(ms):
    return 1000.0 / ms

SCEN = {'A 平地を歩く (RD12)': 7.72, 'B 村・洞窟': 16.87, 'C TNT x100+大量P': 59.89}

def frame_after(mod_ms, wired_only):
    total = 0.0
    for k, share in SHARE.items():
        full, wired, _ = CAP[k]
        gain = full if not (wired_only and not wired) else 0.0
        after_mod = 1.0 - full * MOD_TAKES[k]
        after_amd = after_mod * (1.0 - gain * (1.0 - MOD_TAKES[k]))
        total += mod_ms * share * after_amd
    return total

if __name__ == '__main__':
    print("=== フレーム内訳 (実プロファイラ準拠) ===")
    for k, v in SHARE.items():
        print("   %-11s %5.1f%%  Mod先行 %3.0f%%  AMD全利得 %3.0f%%  %s  %s"
              % (k, v * 100, MOD_TAKES[k] * 100, CAP[k][0] * 100,
                 '配線済' if CAP[k][1] else '未配線', CAP[k][2]))
    print()
    for label, wo in (("今の配線だけ(未配線の利得=0)", True), ("全部配線した場合", False)):
        print("=== %s ===" % label)
        print(f"{'シナリオ':22s} {'Mod入り':>9s} {'+AMD':>9s} {'倍率':>7s}")
        for n, ms in SCEN.items():
            a = frame_after(ms, wo)
            print(f"{n:22s} {fps(ms):7.0f}  {fps(a):8.0f}  {ms/a:6.2f}x")
        print()
