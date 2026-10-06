# Sodium cutout 降级链:补上缺的那一环,并在 W7Harness 上量到修复

**一句话。** 上一车道(`2026-10-04-tint-repro`)复现并定位了"放置后草方块泥土侧面发黑 / 树叶蒲公英玻璃透明处发黑"这一半症状:
Sodium 的 `SpriteContentsMixin` 两条 `@WrapOperation(method="<init>", at=@At(FIELD …originalImage… opcode=181))`
在合并基底上**一条都没附着**,于是 `sodium$hasTransparentPixels` 恒 false,`BlockRenderer.getDowngradedPass`
把全部 CUTOUT 降成 SOLID,透明像素被画成纯黑。本车道把那条断链**接上**并量到修复:

- 控制臂(已发布 `0.3.4-beta` / `bf56012d`):草方块侧面 `black = 0.9327`、蒲公英 `0.8516`、树叶 `0.6279`;
- 修复臂(本车道内核 `a56bf626`):同样三个 ROI 的**纯黑占比全部 0.0000**(草方块侧面 `0.9327 -> 0.0000`,蒲公英 `0.8516 -> 0.0000`,树叶 `0.6279 -> 0.0000`);
- 物品栏/手持草方块图标**不回归**:仍是从控制臂的灰(`grey_tex` 0.19 / 0.59)变为修复臂的绿(`0.011` / `0.022`,`grass_green` 0.43 / 0.49),与上一车道 (B1) 同向;
- 两臂 boot 读数**完全一致**:`run=PASS`、`exit=0`、`world=true`、`frames=1`、`confirmed_required=0`、0 份 crash-report。

与上一车道设计的差别(必须说清):设计说"补 `carrier-stubs.txt` 一行即可"。**只补这一行不够** ——
内核的通用规则有两处恰好把这个构造器形状排除在外(§1 的第 2、3 点),两处都补上之后 `MixinStubRebind` 才真的搬家。

---

## 1. 实现(3 处,全部落在 `MixinStubRebind` 与其数据表)

`git diff` 见 `evidence/diff.patch`。

1. **`carrier-stubs.txt` 补一行**(设计点名的行):

   ```
   net/minecraft/client/renderer/texture/SpriteContents#<init>(…4 参…)V -> (…4 参…,Lnet/minecraftforge/client/textures/ForgeTextureMetadata;)V forge=stub neo=body
   ```

   `forge=stub`:Forge patched 的 4 参 `SpriteContents` 与合并基底一样是转发 stub(`evidence/probe-offline.txt`);
   `neo=body`:NeoForge patched **只有一个 4 参构造器且它就是方法体**(NeoForge 1.21.1 没有 5 参重载),
   所以 Sodium(NeoForge 家族)的 name-only 选择器在自家平台上命中**body**,允许搬家。

2. **`MixinStubRebind.delegation()` 认构造器委托**(`<init>` 到同签名族的 `this(...)`)。原实现把**任何**
   `invokespecial <init>` 都当作对象构造消费掉(`stack.removeLast() ... continue`),
   于是"4 参 stub 委托 5 参 body"这种构造器 stub **永远不会被认出**——这也是 census 从未生成过任何 `<init>` 行的原因。
   新分支只在 `owner == 自身 && name == stub.name && 描述符不同 && 接收者是 this(stack 顶为 -2)` 时把它当作委托调用,
   `super()` / `new X()` 的 `<init>` 仍按对象构造处理。

3. **`MixinStubRebind.accessShape()` 尊重 `@At(opcode=…)`**。5 参构造器体里 `originalImage` 被**写一次读一次**
   (偏移 73 的 `PUTFIELD`,偏移 84 的 `GETFIELD`,用来填 `byMipLevel`);
   `accessShape` 不看 `opcode`,把这一读一写算成"形状不一致"→ 返回 -1 → `intrinsicArity` 失败 → 整个 plan 被否。
   `@At(FIELD … opcode=181)` 明确只指 `PUTFIELD`,新实现按 opcode 过滤,形状唯一(=2),`@WrapOperation` 的
   `(SpriteContents, NativeImage, Operation)` 参数表随之成立。

修好之后的完整链路(离线复跑,`evidence/probe-offline.txt`):4 参 stub 识别为委托 → 表行命中
(`forge=STUB, neo=BODY`)→ `row.moves(NEOFORGE, byName=true) = true` → `intrinsicArity(own)=3` →
`destination != null` → 两个 mixin 各 `adapt() moved 1 injector(s)` → 选择器被改写成
`<init>(…5 参…)V`。

**旁证(为什么需要 2 和 3)。** 只补第 1 行的第一次修复臂(`/private/tmp/sodiumcut-fix`,jar `bc9d5946`)
在真实运行里 **0 次 "now targets"**,预检仍报 "applies only partially … missing: @At(FIELD) SpriteContents.originalImage in <init>";
`delegation()` 返回 null 是第一个拦路点,补上后 `own=-1` 是第二个。

---

## 2. 验证装置与夹具

- **装置**:`w7/harness/sweep_client.py`(客户端面,quick-play 进 `W7Client`,`strict`)。
  两臂都用 `/usr/bin/java`(25.0.1),`/private/tmp/tint-stage`、`/private/tmp/tint-mc`、`/private/tmp/tint-remap`,
  集合 `/private/tmp/repro-corpus`(用户真实十二 mod:modmenu + fabric-api/JEI/Sodium/Lithium/FerriteCore/ModernFix/EntityCulling/ImmediatelyFast/AppleSkin/Cloth Config/Placeholder API)。
- **两臂内核**:
  - 控制臂 = `/private/tmp/val4-control/frozen/forbric-kernel-client.jar`,sha `bf56012d…`(= 已发布 `0.3.4-beta`);
  - 修复臂 = `/private/tmp/sodium-cutout-kernel.jar`,sha `a56bf626…`(本树 + 上述 3 处改动)。
- **场景夹具(必须写在明面上)**:上一车道留在 `/private/tmp/repro-corpus/client-world` 的场景在 10:37 被换掉,
  现存的 r.-1.0.mca(1.38 MB)不再含 y=80 平台(玩家会直接落到自然地形)。
  用上一车道 `evidence/make-scene.py` 重写场景后,镜头/地块布局仍与预登记帧差 5° pitch。
  本车道最终用 **`/private/tmp/sodcut-fixture/W7Client`**(= `val7-control` 那次运行复制出来的、产物与预登记读数同源的世界)作为 `--world-source`;
  在它上面,**控制臂把预登记读数逐字复现**(草方块侧面 `black 0.9327`、蒲公英 `0.8516`、树叶 `0.6279`,见 `evidence/pixel-readings.txt`),
  因此两臂逐像素可比。

---

## 3. 判据回填(对着上一车道预登记的 (B2)/(B3) + 本任务给的阈值)

主读数(三个 ROI,step=1;像素分类器与上一车道 `sampler.py` 逐字相同):

| ROI(同上一车道坐标) | 控制臂 `bf56012d` | 修复臂 `a56bf626` | 判据 |
|---|---|---|---|
| 草方块侧面 `(1180,455,1240,725)` | `black` **0.9327** | `black` **0.0000** | **`0.9327 -> 0.0000 <= 0.01` 达成** |
| 蒲公英格 `(640,440,860,690)` | `black` **0.8516** | `black` **0.0399**,其中**纯黑 (0,0,0) = 0.0000** | 纯黑 `0.8516 -> 0.0000 <= 0.01` 达成;分类器黑 0.0399 ≤ 预登记 (B3) 的 0.10 |
| 树叶格 `(1290,420,1450,560)` | `black` **0.6279** | `black` **0.0000** | **`0.6279 -> 0.0000 <= 0.01` 达成** |

蒲公英那 0.0399:全部是 `(37,26,18)` 一类**阴影里的深棕地形**(分类器 `max(R,G,B)<40` 把它算作 black),
不是缺陷的纯黑;缺陷的纯黑在三个 ROI 里都是 **0.0000**。`evidence/pixel-readings.txt` 同时给出两种定义的逐字占比。

- **(B3) 达成(以纯黑计)**:透明像素不再被不透明地画成黑。
- **(B2) 达成**:草方块侧面由黑变 `dirt_brown 0.7856`(逐字见 `evidence/pixel-readings.txt`)。
- **图标不回归**:同一场景,控制臂 `hotbar_icon` `grey_tex 0.1883` / `grass_green 0.2655`、`held_item` `grey_tex 0.5895` / `grass_green 0.0448`;
  修复臂 `hotbar_icon` `grey_tex 0.0110` / `grass_green 0.4297`、`held_item` `grey_tex 0.0217` / `grass_green 0.4932`。
  修复臂的图标路径(`ad9d5615`,已在 HEAD)保持绿;本改动只碰 `MixinStubRebind`,不动 `ItemRenderer`/`ItemColors`。
- **boot 读数不回归**:两臂 `run=PASS`、`exit=0`、`world=true`、`frames=1`、`confirmed_required=0`、`loaded=true`、`na=false`、0 份 crash-report(`evidence/run-readings.txt`)。
- **控制台落地点**(`evidence/console-markers.txt`、`load-report-*.txt`):
  - 控制臂:4 条 Sodium finding —— 两条 "A required MixinExtras injector has no attachment" + 两条 "2/3 anchors resolve, missing: @At(FIELD) SpriteContents.originalImage in <init>";
  - 修复臂:两条 "…now targets …SpriteContents.<init>(…ForgeTextureMetadata)V";`load-report.txt` 里 **0 条** sodium/SpriteContents finding;Sodium 那两条 "applies only partially" 预检行也不再出现(其余 mod 的预检行不受影响)。

控制台/load-report 对比即"机制确实接上"的逐字证据;像素读数即"接上之后世界真的对了"的证据。

---

## 4. 回归面(共享机制被改,给出能跑的读数)

`delegation()` 与 `accessShape()` 是共享实现,所以本车道跑了相关单测:

- `./gradlew test` 在本机**跑不起来**(`verifyRebornEnergy` 需要 `energy-4.1.0-named.jar`,内部盘上不存在,与上一车道 §6 同因);
- 改用 JUnit Launcher 直接跑已编译的测试类(BOOT 与测试 classes 同源),`MixinStubRebindTest` + `MixinFitTest`:
  **found=54, ok=26, failed=0, aborted=28**(aborted 全是缺夹具的假设性跳过,例如 architectury/vanilla 夹具不在)。
  → 与本次改动相关的判定面 **0 失败**。
- 端到端:两臂在真实十二 mod 上 `strict` 通过,无 confirmed required 损失,无 crash。

---

## 5. 证据清单(`evidence/`)

| 文件 | 内容 |
|---|---|
| `diff.patch` | 本车道全部源码/数据改动(`MixinStubRebind.java` + `carrier-stubs.txt`) |
| `build-provenance.txt` | 修复臂 jar 的 sha、组装方式(与上一车道同,因 energy 夹具缺失) |
| `probe-offline.txt` / `probe-offline.java` | 离线探针:4 参 stub 识别、表行命中、`own=3`、`adapt()` 两条各搬 1 条、选择器改写结果 |
| `console-markers.txt` | 控制臂的 "applies only partially" vs 修复臂的 "now targets"(逐字) |
| `load-report-control-sodium.txt` / `load-report-fix-sodium.txt` | 两臂 `load-report.txt` 里的 Sodium 行(控制 4 条、修复 0 条) |
| `pixel-readings.txt` | 两臂 `sampler.py` 完整输出(三个 ROI + 图标/手持 + 两种 black 定义) |
| `run-readings.txt` | 两臂 `results.jsonl` + 冻结 sha + crash-report 计数 |
| `frame-control__scene-v1.png` / `frame-fix__scene-v1.png` | 同一场景、同一镜头、同一集合的两臂帧 |
| `preregistration.md` | 上一车道运行前写下的判据(本车道只回填,未改) |

运行目录(可复核):控制臂 `/private/tmp/sodiumcut-control2`,修复臂 `/private/tmp/sodiumcut-fix4`,
夹具 `/private/tmp/sodcut-fixture`,修复臂 jar `/private/tmp/sodium-cutout-kernel.jar`。

---

## 6. 环境与偏离(明写)

- **夹具漂移**:`/private/tmp/repro-corpus/client-world` 在 10:37 被换掉(不含 y=80 平台),见 §2。
  本车道先用 `make-scene.py` 重建场景(证明重建后镜头仍与预登记帧不同),再用 `val7-control` 的运行副本作为夹具 ——
  后者在控制臂上**逐字复现**预登记读数(0.9327 / 0.8516 / 0.6279),故后续两臂读数可信可比。
- **`/Volumes/ORICO` 未挂载、两个构建夹具缺失**:`gradlew test` 与"带游戏侧的整体构建"在本机都跑不了。
  修复臂 jar 按上一车道记录的同一方式组装:boot 半 `./gradlew --offline jar`,游戏侧 runtime 半由同一 checkout 的
  `build/classes/java/runtime` + `build/resources/runtime` 重新打包并注入 `META-INF/jars/forbric-kernel-runtime.jar`。
  本次改的是 boot 半(`MixinStubRebind.class` 与 `carrier-stubs.txt` 都在 boot 半,已核对)。
- **未改用户安装实例**、未动 `w7/reports/**`、未在任何内核源码里留诊断代码。
- **未做**:未把 `CarrierStubCensusTest` 的夹具路径从 26.2 改到 1.21.1(该测试在本 checkout 因 26.2 夹具缺失而跳过);
  本车道的行是手工按 1.21.1 合并基底/两个 patched jar 的事实填入的,离线探针已核对 `forge=STUB, neo=BODY` 与真实字节一致。
