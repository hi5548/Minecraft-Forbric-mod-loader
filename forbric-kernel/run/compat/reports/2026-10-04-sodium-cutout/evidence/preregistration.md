# 预登记(运行前登记,禁止事后改判据)—— 2026-10-04-tint-repro

本文件在**任何**测量运行之前写成。判据一经写下,后续读数只能"达成/被证伪",不得改写。

## 0. 被测量的形状

- **内核两臂**:
  - 控制臂 = 已发布 `forbric-kernel-0.3.4-beta.jar`,sha256 `bf56012d7bf6f025dba3628e43c303bf54970ce81142fa41b3833fb1dd8e21f4`
    (安装副本 `/Applications/.minecraft/libraries/net/forbric/forbric-kernel/0.3.4-beta/`)。
  - 修复臂 = 当前树编译并冻结的 `/private/tmp/tint-kernel.jar`,sha256 `b50c95b09506f11079f84da8a0ac21f9fec0067951747a45e06998d854abca21`
    (含 `ad9d5615` 的 `restoreTheRawColourKeysAndRebindTheColourMixins`)。
- **集合**: 用户真实十二 mod(subject `modmenu-11.0.5.jar` fabric;closure = fabric-api 0.116.17 / JEI / Sodium 0.8.13 /
  Lithium / FerriteCore / ModernFix / EntityCulling / ImmediatelyFast / AppleSkin / Cloth Config / Placeholder API),
  `/private/tmp/repro-corpus`。
- **装置**: `W7Harness` 客户端面(`harness/sweep_client.py`),quick-play 进 `W7Client`,`-Dforbric.compatibilityPolicy=strict`,
  热 remap 缓存 `/private/tmp/tint-remap`,JDK 21,舞台 `/private/tmp/tint-stage`,MC 根 `/private/tmp/tint-mc`。

## 1. 场景(每次运行同一份夹具,写在夹具里而非运行参数里)

- 玩家: `Pos=[-27.0, 81.0, 51.0]`,`Rotation=[90.0, 0.0]`(朝 −X,平视),hotbar slot 0 = `minecraft:grass_block`。
- 天空平台 y=80(x −31..−26,z 47..56,草方块),平台以上 y=81..94 全部清空。
- 标本柱(各 2 高,x=−30):z=48 leaves / 49 grass_block / 50 iron_bars / 51 dandelion / 52 glass / 53 stone / 54 dirt;
  另有单块浮空标本(y=82,z=49 glass / 50 stone / 51 dandelion / 52 oak_leaves / 53 grass_block)。
- 场景由 `client-world/W7Client/region/r.-1.0.mca` 的 chunk (−2,3) 写块 + `playerdata/*.dat` 写物品/坐标构成,
  脚本见 `evidence/region.py`、`evidence/nbt.py`、`evidence/make-scene.py`。

## 2. 像素分类(判据的可观测词汇)

`evidence/sampler.py`,逐像素,对 RGB:

- `black` = `max(R,G,B) < 40`
- `grass_green` = `G >= R+12 and G >= B+12 and G >= 60`
- `dirt_brown` = `R > G >= B and R >= 60 and R-B >= 25`(且非 black)
- `grey_tex` = `|R-G| <= 8 and |G-B| <= 8 and 60 <= G <= 210`
- `sky` = `B > R and B > G and B >= 120`

## 3. 预登记的期望类(用户在原始报告里逐字说的两句,见原任务 context)

用户原话:`草方块在背包里面的草是灰色的,然后放出来下面的土是黑色的…可能还有一些类似的bug`

| # | 画面面 | 期望(修复后) | 控制臂(未修)预期 |
|---|---|---|---|
| **P1** | 物品栏/手持草方块图标的**顶面** | `grass_green`,且 `grey_tex` 占比 ≈ 0(采样 hotbar slot 0 与第一人称手持) | `grey_tex` 显著 > 0(未上色 → 灰) |
| **P2** | 世界里**放置的草方块顶面** | `grass_green` | 同样 `grass_green`(世界顶面走 Sodium 自带草色 provider) |
| **P3** | 世界里**放置的草方块侧面/泥土** | `dirt_brown`(不是 `black`) | `black`(用户所说的"下面的土是黑色的") |
| **P4** | 其它 cutout 方块(树叶、蒲公英、玻璃、铁栏杆)的**透明像素** | 透出背后(sky/相邻块),即不是 `black` | `black`(透明像素被不透明地画成黑) |
| **P5** | 相同确定性场景、两臂世界像素 | —— | 只接受:**同一场景下世界像素逐字节相同** 才算"该臂无差别";若不同,必须能指名是哪一类像素差 |

## 4. 判据(可证伪)

- **(B1) 图标**: 控制臂 hotbar slot 0 的 `grey_tex` 占比 ≥ 0.10 且修复臂 ≤ 0.02;
  且修复臂 `grass_green` ≥ 2×控制臂。**方向**:修复臂绿、控制臂灰。若两臂同绿或同灰 → 本条判为不可判,如实写。
- **(B2) 泥土黑**: 控制臂"草方块侧面"ROI 的 `black` 占比 ≥ 0.50;修复臂 ≤ 0.10 且 `dirt_brown` ≥ 0.10。
- **(B3) cutout 透明像素**: 控制臂蒲公英/树叶/玻璃 ROI 的 `black` 占比 ≥ 0.30;修复臂 ≤ 0.10。
- **(B4) 世界面不受 `ad9d5615` 影响**: 控制臂与修复臂的世界区域(排除 HUD/手持)像素逐字节相同 ⇒
  证明**世界那一半是另一个缺陷**,`ad9d5615` 修不了它(这正是上一车道"未复现"的真因)。
- **(C) 控制台落地点**: 修复臂出现内核的维修 marker 行;控制臂 0 次。
- **(D) 不回归**: 两臂 `run=PASS`、`world=true`、`confirmed_required=0`、`frames>=1`、0 份 crash-report。
- **(E) 证伪**: 若控制臂 P1/P3/P4 都不达(B)的阈值,则"症状未复现",本车道**不得**把"没看到坏"写成通过。

## 5. 机制先验(写在运行前,便于证伪)

若 P3/P4 复现,首选假设是 **Sodium 的 chunk 材质选择**,而非 vanilla 颜色键:

- Sodium `DefaultMaterials.forBlockState`/`forRenderLayer` 决定每 quad 的 alpha cutoff material bits;
  `TerrainRenderPass`(SOLID/CUTOUT/TRANSLUCENT)决定用哪个 program,而 `USE_FRAGMENT_DISCARD` 只加在 CUTOUT 上;
- `BlockRenderer.getDowngradedPass` 在 `sprite.contents().sodium$hasTransparentPixels()==false` 时把 CUTOUT 降到 SOLID;
- 该 flag 由 Sodium 的 `SpriteContentsMixin`(scan/mipmaps)在 `SpriteContents.<init>` 的
  `PUTFIELD originalImage` 处 `@WrapOperation` 里扫出来。
- 若该 injector 在合并基底上**未附着**,flag 恒为 false ⇒ 所有 cutout 方块降级进 SOLID ⇒ 透明像素画成黑。

**证伪该机制**: 若控制台上该 injector **有**附着,或旗标为 true,则本假设被推翻,必须另找。
