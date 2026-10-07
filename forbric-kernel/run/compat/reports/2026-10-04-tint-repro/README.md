# 复现用户的画面 + 机制定位(**含未完成项,明写**)

**一句话。** 用 `W7Harness` 在用户真实十二 mod 上把用户原话的两个画面**都复现出来了**,并逐字节指出它们是**两条不同的缺陷**:
① 物品栏/手持草方块图标顶面发灰 = 合并基底 `ItemColors/BlockColors.getColor` 的 Forge 委托键失配(已在 `ad9d5615` 修好,本次量到生效);
② 世界里放置的草方块的**泥土侧面发黑** = **Sodium 的 cutout 降级链断了一环**:Sodium 的
`SpriteContentsMixin` 两条 `@WrapOperation(method="<init>", at=@At(FIELD …originalImage… opcode=181))`
在合并基底上**一条都没附着**(合并基底把 `SpriteContents` 的方法体搬进了 5 参 Forge 构造器,4 参成了纯委托 stub,
Mixin 的不带描述符选择器绑到 stub),于是 `sodium$hasTransparentPixels` 恒 false,
`BlockRenderer.getDowngradedPass` 把**所有 CUTOUT 方块降到 SOLID pass**,而 `USE_FRAGMENT_DISCARD` 只加在 CUTOUT 上
⇒ 透明像素被当实色写下去 ⇒ 树叶/蒲公英/玻璃/草方块侧面 overlay 的透明处全部**黑**。

**状态(不藏)**: 复现、机制定位、读数、预登记**都已完成**;**修复本身本轮没落地**(预算耗尽),
只给出按内核既有约定(`MixinStubRebind` 的 `carrier-stubs.txt` 行,或 Indigo 那条专用适配器的形状)的修复设计,见
`evidence/byte-census.txt` §4。

---

## 1. 复现(装置 + 画面)

装置:`w7/harness/sweep_client.py`(客户端面,quick-play 进 `W7Client`,`strict`,`-Dforbric.clientSmokeScreenshots=100`),
舞台 `/private/tmp/tint-stage`、MC 根 `/private/tmp/tint-mc`、热 remap 缓存 `/private/tmp/tint-remap`、
集合 `/private/tmp/repro-corpus`(用户十二 mod),场景由 `evidence/make-scene.py` 写进世界夹具。

两臂:

| 臂 | 内核 | sha256 |
|---|---|---|
| 控制 | 已发布 `0.3.4-beta` 客户端 jar(安装副本) | `bf56012d7bf6f025dba3628e43c303bf54970ce81142fa41b3833fb1dd8e21f4` |
| 修复 | 当前树编译并冻结 `/private/tmp/tint-kernel.jar`(含 `ad9d5615`) | `b50c95b09506f11079f84da8a0ac21f9fec0067951747a45e06998d854abca21` |

复现到的**用户原话两个画面**:

- `evidence/frame-control__cutout-specimens.png` —— 七根标本柱一帧全收:
  **蒲公英 = 黑底十字**(应透出天空)、**树叶 = 绿底黑齿**、**玻璃 = 不透明**、
  **草方块 = 顶部绿 fringe + 其余整片黑**(用户说的"下面的土是黑色的"),
  旁边的 **stone / dirt / oak_planks 完全正常** —— 因为它们在 SOLID pass 里,SOLID 才是它们的正确 pass。
- `evidence/frame-control__grass-vs-solid.png` —— grass / stone / dirt / oak_planks 四柱对照:
  草方块侧面黑,其余三柱纹理正常。**同帧对照**,排除"光照/贴图/透视图"这些解释。
- `evidence/frame-control__floating-specimens.png` —— 单块浮空标本 + 背后纯天空:
  蒲公英的透明处**不是天空而是黑**,证明黑是**被画上去的**,不是没画。
- `evidence/frame-control__icon-grey.png` vs `evidence/frame-fix__icon-green.png` ——
  同一确定性场景,物品栏 slot 0 与第一人称手持的草方块:控制臂**灰顶**,修复臂**绿顶**(症状 ①,已由 `ad9d5615` 修好)。

## 2. 预登记与读数

判据与阈值**运行前**写在 `evidence/preregistration.md`(P1..P5、(B1)..(E))。回填读数(逐字,`evidence/pixel-readings.txt`):

| ROI | 控制 `bf56012d` | 修复 `b50c95b0` | 判决 |
|---|---|---|---|
| hotbar slot 0(图标) | `grey_tex` **0.2225**、`grass_green` 0.170 | `grey_tex` **0.0058**、`grass_green` **0.3708** | **(B1) 达成** |
| 第一人称手持块 | `grey_tex` **0.7197**、`grass_green` 0.0016 | `grey_tex` 0、`grass_green` **0.6544** | **(B1) 达成** |
| 世界区域(排除 HUD/手持) | 逐字节相同 | 逐字节相同 | **(B4) 达成** |

- **(B1) 达成**:图标灰→绿,两臂方向与预登记一致。
- **(B4) 达成,且是本车道最重要的一条读数**:同一确定性场景下,**修复臂与控制臂的世界像素逐字节相同** ——
  也就是说"放置后泥土发黑"这半个症状,**`ad9d5615` 修不了它**,世界那一半是另一个缺陷。
  (这也解释了上一车道 `2026-10-04-tint/` §5.4 的"未复现":他们的帧里根本没有物品栏图标,且世界那一半与他们的修复无关。)
- **(B2)/(B3)** 用的是控制臂 vs 屏幕取样证明:**控制臂**草方块侧面 ROI `black` 占比 **0.9327**、
  蒲公英格 **0.8516**、树叶格 **0.6279**(逐字见 `evidence/pixel-readings.txt` 末段)(逐字见 `evidence/pixel-readings.txt` 与 §3 的逐像素剖面);
  修复臂这一半**不存在**(见 (B4))——即 (B2)/(B3) 目前**只复现、未被修好**,如实记为**未达成**。
- **(D) 不回归**:两臂 `run=PASS`、`world=true`、`frames=1`、`confirmed_required=0`、0 份 crash-report(`evidence/run-readings.txt`)。

逐像素剖面(控制臂,`frame-control__cutout-specimens.png`,x=1180 竖切草方块柱):

```
y=400 (155,190,254)  y=420 (48,62,30)   <- 天空,然后是 tinted overlay 的绿 fringe
y=440 (41,54,26)
y=460..720 (0,0,0)                      <- 草方块侧面其余部分:纯 0
y=740 (119,119,119)                     <- hotbar UI
```
同帧 dirt 柱是 (78,55,37) 一类棕色、stone 柱是 (150,150,150) 一类灰 —— **只有草方块侧面是 0**。

## 3. 机制(逐字节)

完整普查在 `evidence/byte-census.txt`;三行版本:

1. Sodium 的地形片元着色器 `block_layer_opaque.fsh` 的 `discard` 被 `#ifdef USE_FRAGMENT_DISCARD` 包着,
   而该宏只由 `ChunkShaderOptions.constants()` 在 `pass.supportsFragmentDiscard()` 时加 —— 只有 CUTOUT 那个
   `TerrainRenderPass(cutoutMipped(), false, true)` 加了。**SOLID program 里没有 discard。**
2. `BlockRenderer.getDowngradedPass(sprite, pass)` 里,当 `sprite.contents() instanceof SpriteContentsExtension`
   且 `sodium$hasTransparentPixels() == false` 时,**把 CUTOUT 换成 SOLID**。
3. 那两个旗标只有 Sodium 的 `features.textures.scan.SpriteContentsMixin` / `features.textures.mipmaps.SpriteContentsMixin`
   会写,且都写在同一个锚点上:
   `@WrapOperation(method="<init>", at=@At(value="FIELD", target="…SpriteContents;originalImage:…", opcode=181))`。

而合并基底的 `SpriteContents` 把 body 放在 **5 参** Forge 构造器(唯一一处 `PUTFIELD originalImage` 在偏移 73),
**4 参**(vanilla 签名)只剩 `aload_0 … aconst_null … invokespecial <init>5参 … return`。
Mixin 的不带描述符选择器绑**第一个声明**的同名方法 = 4 参 stub ⇒ 两条 injector 在 4 参里都找不到那个 `PUTFIELD` ⇒ 不附着。

运行期(`evidence/probe-output.txt`,探针在**被测物之外**):`TYPE_BY_BLOCK size=309`、`getChunkRenderType(GRASS_BLOCK)` 身份等于
`RenderType.cutoutMipped()`、material `pass=CUTOUT cutoff=HALF mipped=true` —— 全部正常;
`ItemBlockRenderTypes` 这条路是好的,坏的只有 Sodium 那两处 injector 没附着。与内核自己的
`load-report.txt` 逐字对上(`evidence/load-report-sodium.txt`):
"`sodium$beforeGenerateMipLevels — A required MixinExtras injector has no attachment`"、
"`2/3 anchors resolve, missing: @At(FIELD) SpriteContents.originalImage in <init>`"。

**两个症状因此分属两条路径**:图标走 vanilla `ItemRenderer`(`ItemColors.getColor` 直取,不经 Sodium 的 pass),
世界走 Sodium chunk pass;前者是 `getColor` 的键失配(`ad9d5615`),后者是 cutout 降级链断了(未修)。

## 4. 修复设计(按内核既有约定;**本轮未落地**)

内核里**已经**有一条正好描述这个形状的通用规则:

```
MixinStubRebind: "Moves a mod's injector off a merge-added delegating stub onto the method that carries the body …
  Mixin binds a selector without a descriptor to the FIRST declared method of that name … a carrier that widened a
  vanilla method usually kept vanilla's signature in place as a stub and put the body in the new overload after it."
触发面: 只沿 carrier-stubs.txt 的行,且 NeoForge/Forge 家族只在"其 carrier 自己的 patched class 里
        vanilla 签名就是 body(或 name-only 选择器只匹配 widened overload)"时才搬。
```

`SpriteContents.<init>` 这一对**不在** `carrier-stubs.txt`(表里没有该行),所以 Sodium(NeoForge 家族)的选择器没被搬家。
修复即**补这一行**(stub = 4 参 `(ResourceLocation, FrameSize, NativeImage, ResourceMetadata)V`,
delegate = 5 参 `(…, ForgeTextureMetadata)V`,`neo=`/`forge=` 按两个 patched jar 的事实填),
或写一条 Sodium 专用适配器(形状照 `FabricSectionCompilerMixinAdapter` 对 Indigo `SectionCompiler.compile` 的搬家),
把两个 guest mixin 的 `method` 从 `<init>` 改成 5 参描述符。两条都守住同一条:锚点搬到装 body 的那次调用上。

**未做**:没有写进内核、没有重新跑读数、没有"修复臂 P3/P4 达成"的证据。下一车道按上述任一实现后,
判据即 `preregistration.md` §3/§4 的 (B2)/(B3)。

## 5. 环境与偏离(明写)

- **`/Volumes/ORICO` 未挂载**:`w7/reports/**` 里指向它的软链与 `p0/stage-1.21.1` 不可用。
  本车道复用上一车道留在内部盘/`/private/tmp` 的等效物:舞台 `/private/tmp/tint-stage`(merged base + 两个 runtime,
  与 `/Applications/.minecraft/.forbric-build/out` 同源)、MC 根 `/private/tmp/tint-mc`、热 remap 缓存 `/private/tmp/tint-remap`、
  集合 `/private/tmp/tint-usermods`(用户十二 mod)。
- **本车道自己造的夹具**:`/private/tmp/repro-corpus`(由 `tint-usermods` 拷贝 + 场景写块 + playerdata 写物品/坐标)。
  写入工具是本车道自己写的纯 stdlib 实现(`evidence/nbt.py`、`evidence/region.py`),已做过 NBT 往返逐字节相等与
  全 region 再解码校验;第一版曾因"palette≥2 却不写 `data`"造成 `Recoverable errors when loading section [-2,5,3]:
  Missing values for non-zero storage`,已修(见 `region.py` 注释)。
- **探针**:`evidence/Probe.java` 走 `-javaagent`,在**内核/模组之外**运行,只做反射读值,随截图一并留证。
- **未做**:未改用户安装实例、未动 `w7/reports/**`、未在任何内核源码里留下诊断代码。

## 6. 证据清单(`evidence/`)

| 文件 | 内容 |
|---|---|
| `preregistration.md` | 预登记:场景、像素分类、P1..P5 期望、(B1)..(E) 判据、机制先验 |
| `byte-census.txt` | 逐字节普查:降级链、两个 mixin 注解、`SpriteContents` 两个构造器、`load-report` 与探针输出、修复形状 |
| `frame-control__cutout-specimens.png` | **主复现帧**:草方块侧面黑 + 蒲公英黑底 + 树叶黑齿 + 玻璃不透明 + stone/dirt/planks 正常 |
| `frame-control__grass-vs-solid.png` | 同帧对照:grass 黑 / stone,dirt,planks 正常 |
| `frame-control__floating-specimens.png` | 浮空标本 + 纯天空背后:黑是被画上去的 |
| `frame-control__icon-grey.png` / `frame-fix__icon-green.png` | 症状 ①:图标灰 → 绿(同一确定性场景) |
| `frame-control__scene-v1.png` / `frame-fix__scene-v1.png` | (B4) 的原始对:世界像素两臂相同 |
| `pixel-readings.txt` | 上述帧的完整采样输出(分类占比 + 模式 RGB) |
| `run-readings.txt` | 六次运行的 `results.jsonl` 行 + 冻结内核 sha |
| `probe-output.txt` | `-javaagent` 探针逐行输出(render type / material / 四张 map 的 size) |
| `load-report-sodium.txt` | 控制臂 `load-report.txt` 里 Sodium 的两条 finding 与本车道相关的行 |
| `sampler.py` | 纯 stdlib PNG 解码 + 五类像素分类 + ROI 采样 |
| `nbt.py` / `region.py` / `make-scene.py` | 夹具写入工具与场景脚本(可复核) |
| `Probe.java` | 运行期探针(装置在被测物之外) |
