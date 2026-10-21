# -*- coding: utf-8 -*-
"""v3: 比較対象を2つにする。
 (1) バニラ -> +AMD-Faster
 (2) 全Mod入り(Sodium+ImmediatelyFast+EntityCulling+Lithium+FerriteCore+ModernFix) -> +AMD-Faster
(2) がプロジェクトの実際の目標。Mod が既に取っている利得は二重に数えない。
"""
exec(open('/tmp/fps/model2.py').read().split('S = [')[0])   # 定数と run() を流用

# 視錐台の生存率をコードの 21.19% ではなく FOV から独立に検算する。
# 水平FOV 70度なら、プレイヤー周囲のリングのうち楔に入る割合は 70/360 = 19.4%。
# HierarchicalFrustumCuller の 21.19% はこれと近い(セクションは距離もあるので少し甘い)。
print("検算: FOV70度の楔 = %.1f%% / コードの実測生存率 = %.1f%%" % (70/360*100, (1-0.7881)*100))
print()

def both(name, **w):
    n, r, b, o, wb, wo = run(name, **w)
    return n, r, b, o, wb, wo

SCEN = [
 ("A 平地を歩く (RD12 定常)", dict(
     sections_vis=700, ent_range=120, ent_vis=30, be=60, named=3, modr=20,
     particles=150, snd_started=6, snd_playing=10, blasts=0, items=20, orbs=0,
     rebuilt=3, light_reb=0, delta_reb=1, other=4.0)),
 ("B 村・洞窟 (エンティティ多)", dict(
     sections_vis=700, ent_range=400, ent_vis=90, be=350, named=12, modr=80,
     particles=600, snd_started=20, snd_playing=40, blasts=0, items=120, orbs=40,
     rebuilt=8, light_reb=4, delta_reb=2, other=4.5)),
 ("C TNT x100 + 大量パーティクル", dict(
     sections_vis=700, ent_range=700, ent_vis=140, be=200, named=8, modr=60,
     particles=4000, snd_started=120, snd_playing=90, blasts=100, items=600, orbs=200,
     rebuilt=120, light_reb=30, delta_reb=60, other=5.0)),
]

# 各項目について「全Mod入りが既に何%取っているか」。出典は各Modの公称機能。
ALREADY = {
 # Sodium: チャンクのドローバッチ・非同期メッシュ・セクションカリング
 'draw(terrain)': 0.90,
 # EntityCulling: エンティティ/ブロックエンティティの視錐台+遮蔽カリング
 'entity draw':   0.85, 'blockentity': 0.85, 'shadow': 0.60,
 # ImmediatelyFast: mod レンダラのバッチ化
 'mod renderer':  0.50,
 # Lithium: エンティティtick・爆発・衝突の最適化
 'entity tick':   0.60, 'explosion':   0.35, 'item tick': 0.30, 'orb merge': 0.30,
 # 名前プレートはどのModも殆ど触らない
 'nameplate':     0.10,
 # パーティクルの「Mod横断の合計予算」は誰もやっていない
 'particle':      0.15,
 # 音の適応的カットオフも誰もやっていない
 'sound':         0.00,
 'other':         0.00,
}

print(f"{'シナリオ':30s} {'バニラ':>8s} {'+AMD':>8s} {'Mod入り':>8s} {'Mod+AMD':>8s}  {'倍率(対Mod)':>10s}")
print("-"*90)
rows = []
for name, w in SCEN:
    n, r, b, o, wb, wo = both(name, **w)
    # Mod入りベースライン = 各項目について利得の ALREADY 分だけ先に取られている
    mod_base = sum(v[0] - (v[0]-v[1])*ALREADY.get(k, 0.0) for k, v in r.items())
    # Mod入りに AMD-Faster を足すと残りの利得が全部乗る
    mod_amd  = o
    rows.append((n, r, b, o, mod_base, mod_amd, wb, wo))
    print(f"{n:30s} {b:6.2f}ms {o:6.2f}ms {mod_base:6.2f}ms {mod_amd:6.2f}ms"
          f"   {mod_base/mod_amd:5.2f}x  ({1000/mod_base:4.0f}->{1000/mod_amd:4.0f} FPS)")

print()
print("=== Mod入り比較で AMD-Faster が実際に足すもの (シナリオB, ms) ===")
n, r, b, o, mb, ma, wb, wo = rows[1]
for k, (bb, oo) in sorted(r.items(), key=lambda kv: -(kv[1][0]-kv[1][1])):
    gain = bb - oo
    mine = gain * (1 - ALREADY.get(k, 0.0))
    if mine < 0.01: continue
    print(f"   {k:16s} 全利得 {gain:6.3f} のうち未開拓 {mine:6.3f}  (Modが{ALREADY.get(k,0)*100:3.0f}%先行)")

print()
print("=== 感度: 削減不能コスト other を振ったときのシナリオA ===")
for other in (2.0, 4.0, 6.0, 8.0):
    w = dict(SCEN[0][1]); w['other'] = other
    n, r, b, o, wb, wo = both(n, **w)
    print(f"   other={other:4.1f}ms : {b:5.2f} -> {o:5.2f} ms  ({b/o:.2f}x, {1000/b:4.0f}->{1000/o:4.0f} FPS)")
