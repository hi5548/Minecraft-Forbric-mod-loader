# 0.3.6-beta —— 发布说明（三档）

**版本**：`0.3.6-beta`（安装器 `forbric-kernel-installer-0.3.6-beta`）
**来源**：`main` 尖端 `13e4ff3f`（= Create 客户端 mod 加载窗口补发 `ClientModLoader.loading` `de13d8ee`
+ `MixinStubRebind.delegation()` 认改名 delegate `4a0a5302` + 被改写的 `getFactories()` 标 `SYNTHETIC`
`caf331e1` + 1.21.1 carrier-stub 表落地 `72fe8d7e` + 全量重导 95 行并清掉 49 条旧行 `232f8de3`/`13e4ff3f`）。
此前已发布 `0.3.5-beta`（内核 `a56bf626…`，源 `b1b90566`）。
**内核**（客户端 boot jar，带游戏侧）sha256：

```
16bcd60242c54890fda728e294ad41935619ff4b499f359f539d6e44dae45b74
```

**构建方式（偏离，必须写在明面上）**：`/Volumes/ORICO` 未挂载、`energy-4.1.0-named.jar` 在本机不存在，
game-side 无法用 staged 属性重建。故按 `2026-10-04` / `2026-10-06` 车道记录的同一装配，但**在干净 worktree 里做**：

1. `git worktree add --detach /private/tmp/rel036/wt 13e4ff3f`（**不在主工作树里构建**——另有 stub-table 车道在
   同时改主树，本车道要的正是"尖端 + 干净条目集"）；
2. `./gradlew --offline -q jar`（boot 半，734 条目，0 条 `X N.class` 陈旧重名条目）；
3. runtime 半**逐字节**取自已发布 `0.3.5-beta` 的 `META-INF/jars/forbric-kernel-runtime.jar`
   （`git diff --stat b1b90566 13e4ff3f -- forbric-kernel/src/runtime` 为空 ⇒ 游戏侧与本版无关）；
4. `zip -X` 注入，合计 735 条目；boot 半非目录条目集合与已发布 0.3.5 boot jar **逐个名字相同**
   （added 0 / removed 0）。
   证据：`evidence/build-provenance.txt`。

**两臂 gate 运行**：`W7Harness` 客户端面（`w7/harness/sweep_client.py`），**一遍即过、无重跑**，
两臂都 `--kernel-jar` 钉住上面这只 jar（未重建、未替换）；JDK 21.0.7，`strict` 策略，`mixinFit=default`，
热 remap 缓存 `/private/tmp/rel036/remap`，stage `/private/tmp/rel036/stage`，MC 根 `/private/tmp/rel036/mc`，
世界夹具 `corpus/client-world/W7Client`。预登记（运行前写下）在 `evidence/preregistration.md`。

| | 集合 | corpus | 逐字读数 |
|---|---|---|---|
| arm A | 用户真实 12（subject `modmenu-11.0.5.jar`，+11 闭包） | `/private/tmp/rel036/corpus12` | `run=PASS exit=0 world=true frames=1 stopped=true killed=false strict=TRUE confirmed_required=0 loaded=true na=false`，11 只依赖全 `OK`，`catalog_failures=[]`，0 份 crash-report，31s |
| arm B | 同一 12 + Create 6.0.10（subject `create-1.21.1-6.0.10.jar`，+12 闭包） | `/private/tmp/rel036/corpus13` | `run=PASS exit=0 world=true frames=1 stopped=true killed=false strict=TRUE confirmed_required=0 loaded=true na=false mod=OK cause=None`，12 只依赖全 `OK`，`catalog_failures=[]`，0 份 crash-report，37s |

两臂都 `joined world via quick-play: W7Client` → `client-ready after 200 world tick(s)` → `clean disconnect observed`；
`[Forbric/Seed] seeded NeoForge LoadingModList with 64 mod(s)`（arm A）/ `67 mod(s)`（arm B）。
完整行与 `results.jsonl` 逐字在 `evidence/gate-reading.txt`。

**安装**：`java -jar forbric-kernel-installer-0.3.6-beta.jar --dir /Applications/.minecraft`（exit 0）；
用户 12 只 mod 逐字节不变、实例文件未被改动、装上的内核 = 本次 gate 的 `16bcd602…`。
逐条证据 `evidence/install-verification.txt`。

---

## 一档 · 实测通过（在真客户端、世界深度跑过）

1. **用户真实 12-mod 组合仍一遍进世界**（arm A）：`run=PASS / world=true / frames=1 / strict=TRUE /
   confirmed_required=0`，11 只依赖全部 `OK`、`catalog_failures` 空、0 份 crash-report。
   **更强的一条**：arm A 的 `load-report.txt` 与 `0.3.5-beta` 发布 gate 的同一文件**逐字节相同**
   （19427 B，md5 `68ae1f74f2606768561176ebe6ef005e`）—— 本轮四处内核改动没有在十二 mod 集合上挪动任何一行。
2. **Create 6.0.10 现在能与这 12 只共存并 `strict` 进世界**（arm B）：`mod=OK`、`cause=None`、
   `catalog_failures=[]`、12 只闭包全 `OK`、0 份 crash-report。
   `grep -c "create failed during client setup"` = **0**、`grep -c "Render layers can only be set"` = **0**。
   `load-report.txt` 的聚合句与 arm A 一致（`9 个 mod 有一部分没有跑起来`），**Create 不在其中**；
   arm A→arm B 的 `load-report` diff 只增加信息级行（3 条 Create 的 entrypoint-closure + 3 条
   entrypoint-reachable arbitration 记录，外加 1 条 fabric-data-generation injector 与 1 条 flywheel 记录），
   **没有增加任何一个"没有跑起来"的 owner**（证据 `evidence/load-report-comparison.txt`）。
   ⇒ `2026-10-06-bughunt-create` 预登记的**证伪条件成立**：那条预登记预测"今天不可加载、`strict=false`"，
   本版在同一集合、同一 subject、同一装置上读出 `strict=TRUE`。
3. **fabric-particles-v1 的接口 mixin 拒绝消失**（两臂）：`grep -c "@Mixin target type mismatch"` = **0**、
   `grep -c "ParticleEngine is not an interface"` = **0**（控制侧：用户本机安装的 `0.3.5-beta` 与
   `2026-10-06-bughunt-create` 控制臂各 2 行）；
   `grep -c "SYNTHETIC default method over ParticleEngine"` = **2**（main + Render thread），即跑的就是修好的字节。
4. **1.21.1 表的新钉行确实发射**（两臂）：`now targets` 共 **6** 条，控制（`0.3.5-beta`）为 2 条
   （两条 Sodium `SpriteContents.<init>`）。新增 4 条正是本版新钉的行落在 fabric-api 上：
   两条 `net.minecraft.client.Options.load(Z)V`（`client.keybinding.GameOptionsMixin` /
   `resource.loader.client.GameOptionsMixin`）、一条
   `net.minecraft.client.renderer.block.ModelBlockRenderer.tesselateBlock(…)`、一条
   `net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer.renderArmorPiece(…)V`。
   这是在真实集合上观测到"新钉表改变内核行为"的第一次（`0.3.5` gate 的 2 条 == 表中既有行）。
5. **Sodium cutout / 颜色修复未回归**（arm A）：
   `now targets … SpriteContents.<init>(…ForgeTextureMetadata;)V` 恰 **2**；
   `applies only partially … SpriteContents.originalImage` = **0**；
   `load-report.txt` 提到 sodium/SpriteContents 的行 = **0**；四条颜色 marker 各在（`getBlock` 2、`getItem` 2、
   两个 `@Shadow … is an IdMapper` 各 1）。
6. **像素不变量**（两臂，同一 corpus 夹具、同一分类器）：三个 ROI（草方块侧面 / 蒲公英 / 树叶）纯黑
   `(0,0,0)` 占比 = **0.0000**。见 §不作超档声明 里对"夹具不可比"的说明。
7. **安装校验（headless）**：`--dir /Applications/.minecraft`，exit 0；用户 12 只 mod sha256 12/12 逐字节不变；
   `versions/` 目录不变；全局 `mods/`（129 jar）mtime 未变；`options.txt`/`saves/`/`config/`/`.forbric-kernel/`
   mtime 均未变；装上的内核 `libraries/net/forbric/forbric-kernel/0.3.6-beta/…jar` sha256 `16bcd602…`
   = gate artifact = 安装器内嵌内核。

## 二档 · 仅离线证明（字节证据 / 单测；本集合未在世界深度复跑）

- **1.21.1 carrier-stub 表 95 行**：`RowProbe` 逐行重读四个 staged jar，`PROBE rows=95 pass=95 fail=0`；
  `Census2 check` = `BOTH 95 / ABSENT 0 / STALE 0 / COLS 0`；`MixinStubRebindTest` 34 测试 0 failed、
  `CarrierStubCensusTest` 2 测试 0 failed（stub-table 车道，`2026-10-06-stub-table`）。
  **本 gate 只观测到其中 4 条锚点在真实集合上发射**（§一档 4）——另外 91 行在本次读数里没有观察者。
- **Create 的 in-world 行为**：本次只证明"加载完成、进世界、`strict`、0 崩溃"。机械动力的配方、渲染、
  物流方块（`PackageRenderer#renderBox` 一类 entrypoint-reachable 路径）**未做**游戏内行为验证。
- **访问器"真的能用"**：12 只 mod 里没有任何一只注册粒子工厂，分类证明在字节探针与单测里，
  不在本次 boot 里（同 `2026-10-06-particle-accessor` §6）。

## 三档 · 已记录损失（不假装可用）

- **`0.3.5-beta` 已记录的损失原样保留**：Indigo per-block 钩子（重锚撤回，区块内自定义几何可能渲染错误或不渲染）；
  Mod Menu 标题行替换（按既有剪枝机制带账退出）；`fabric-registry-sync-v0` 的三条 pin 维持失效
  （`fabric-rendering-v1` 的两条 client mixin 已重绑，marker 为证）。
- **启动日志仍见 `[Forbric/Load] 9 mod(s) 有一部分没有跑起来`**：本版**未修**（arm A 逐字节等于 `0.3.5-beta`
  的同一文件）。它不阻断 `strict`（不是 CONFIRMED required），但确实有 mod 未加载完。
- **fabric-particles-v1 的 `BlockDustParticleMixin` 仍被内核排除**（`load-report` 里 fabric-particles-v1 唯一那条，
  与 `0.3.5-beta` 逐字节相同）。
- **Create 带来 6 条信息级 arbitration 记录**（entrypoint closure/reachable 未证），不阻断加载，如实记录。

## 不作超档声明

- 二档各项只有字节/单测证据，**没有**在真客户端世界深度复跑。
- 一档各项的上限是"这一次运行观测到"，不是"每只 mod 的每项功能都试过"。
- **像素读数只作两臂之间的不变量**：本夹具是 corpus 夹具（ROI 框到的是草顶），
  `dirt_brown`/`grey_tex` 绝对值与 `2026-10-04` 那些周期（`/private/tmp/sodcut-fixture` 已随 `/private/tmp` 消失）
  **不可比**，故只声明"两臂皆无纯黑回归"。
- 本版未测更长的游戏内行为；未在**安装实例**上再启动一次（启动级读数以上面两臂 `strict` 为准，字节同一）。
- 两条 harness 行都带 `rule 3` 的机器负载提示（`337% / 105% CPU busy`，load ≈ 6.6）——本车道没有任何时限判据，
  如实记下。

---

## 发布结果回填（2026-10-07）

见 `evidence/release-result.txt`（tag / 附件 sha / 分支 / 安装校验摘录）。
