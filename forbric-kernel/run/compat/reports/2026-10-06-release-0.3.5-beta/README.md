# 0.3.5-beta —— 发布说明（三档）

**版本**：`0.3.5-beta`（安装器 `forbric-kernel-installer-0.3.5-beta`）
**来源**：`main` 尖端 `b1b90566`（Sodium cutout 降级链修复 + 颜色两步一体 `ad9d5615` + 加载报告聚合面按状态拆句 `37ad693b`）；此前已发布 `0.3.4-beta`（内核 `bf56012d…`，源 `77577837`）。
**内核**（客户端 boot jar，带游戏侧）sha256：

```
a56bf626e983b1dda81cf1b15f5c7f485eaec9c13cb099f1d96ef1d136a6e721
```

**构建方式（偏离，必须写在明面上）**：`/Volumes/ORICO` 未挂载、`energy-4.1.0-named.jar` 在本机不存在，`gradlew jar` 的 game-side 无法用 staged 属性重建。故按 `2026-10-04-sodium-cutout` / `2026-10-04-tint` 车道记录的同一装配：
1. `./gradlew --offline -q jar`（boot 半；无 staged game jars 时不嵌 runtime，正是设计）；
2. runtime 半由同一 checkout 的 `build/classes/java/runtime` + `build/resources/runtime` 重打包；
3. 作为 `META-INF/jars/forbric-kernel-runtime.jar` 注入 boot 半。
三者同源（同一 checkout、同一编译），且本版三处改动全在 boot 半（`MixinStubRebind`、`carrier-stubs.txt`、`ForbricMergedBaseCompatTransformer`、加载报告聚合），runtime 半内容与本版无关。重跑 boot 半与尖端字节**逐条 CRC 相同**（`evidence/build-provenance.txt`）。

**gate 运行**：`W7Harness` 客户端面（`w7/harness/sweep_client.py`），`w7/reports/2026-10-06-release-0.3.5-usermods`（本机 `/private/tmp/rel035/gate`），**一遍即过、无重跑**，`--kernel-jar` 钉住上面这只 jar（未重建、未替换）。主体 `modmenu-11.0.5.jar`，其余 11 只作闭包；JDK 21，`strict` 策略，热 remap 缓存 `/private/tmp/tint-remap`，stage `/private/tmp/tint-stage`，MC 根 `/private/tmp/tint-mc`，世界夹具 `--world-source /private/tmp/sodcut-fixture`。逐字读数（证据 `evidence/gate-reading.txt`，完整行 `results.jsonl`）：

```
run=PASS  exit=0  world=true  frames=1  stopped=true  killed=false  strict=TRUE
confirmed_required=0  seconds=31  java=jdk-21  compatibility_policy=strict
joined world via quick-play: W7Client
[Forbric/Seed] seeded NeoForge LoadingModList with 64 mod(s)
```

12-mod 集合：modmenu, fabric-api, JEI, Sodium, Lithium, FerriteCore, ModernFix, EntityCulling, ImmediatelyFast, AppleSkin, Cloth Config, Placeholder API。

**安装**：下载 `forbric-kernel-installer-0.3.5-beta.zip`，解压，双击 `Forbric-Installer.command`（macOS 首次右键→打开）/ `Forbric-Installer.bat`；确认 **Game directory** → **Install** → 启动器里选 **`1.21.1-forbric`**。不需要任何 JVM 参数。

**发布结果**：见文末「发布结果回填」（tag / 附件 sha / 安装校验）。

---

## 一档 · 实测通过（在真客户端、世界深度跑过）

1. **用户真实 12-mod 组合**：`run=PASS / world=true / frames=1 / strict=TRUE / confirmed_required=0`，11 只依赖全部 `OK`、`catalog_failures` 空、0 份 crash-report。本版在 **`strict` 策略**下（比 0.3.4 发布 gate 的默认策略更严）一遍进世界。
2. **Sodium cutout 降级链已接上**（本版头条，`b1b90566`）：控制台两条 `now targets net.minecraft.client.renderer.texture.SpriteContents.<init>(…ForgeTextureMetadata;)V`（`evidence/console-markers.txt`）；`applies only partially … SpriteContents.originalImage` = **0**；`load-report.txt` 提到 sodium/SpriteContents 的行 = **0**（0.3.4 控制臂为 4 条 Sodium finding）。
3. **同一夹具、同一分类器的像素副读数**：三个 ROI（草方块侧面 / 蒲公英 / 树叶）的**纯黑 `(0,0,0)` 占比全为 0.0000**；草方块侧面 `dirt_brown = 0.7856`（`evidence/pixel-readings.txt`）。控制臂预登记读数（`black 0.9327 / 0.8516 / 0.6279`，见 `2026-10-04-sodium-cutout`）在本夹具上不可复跑，故本条**只报修复臂的绝对读数**，不当作控制/修复对照（见 `evidence/preregistration.md` §3 的不可判降级）。
4. **颜色修复（`ad9d5615`，两步一体）在客户端确实执行**：四条 marker 全部出现（`BlockColors` 2 处、`ItemColors` 1 处 re-key + 两个 `@Shadow` 重绑；类加载期与渲染线程各一次，`evidence/console-markers.txt`）。
5. **加载报告聚合面按状态拆句（`37ad693b`）**：`load-report.txt` 的聚合句写的是"9 个 mod **有一部分**没有跑起来"，不再把零 `FAILED` 的集合说成"没有完成加载"。

## 二档 · 仅离线证明（字节证据 / 单测；本集合未在世界深度复跑）

- **Sodium `MixinStubRebind` 的构造器 stub 委托识别与 `@At(opcode=)` 形状过滤**：离线探针驱动真实维修方法跑在真实字节上（4 参 stub 识别 → 表行命中 → `own=3` → 两 mixin 各 `adapt() moved 1 injector`），全文 `2026-10-04-sodium-cutout/evidence/probe-offline.txt`；`MixinStubRebindTest + MixinFitTest` found=54 / ok=26 / failed=0 / aborted=28（缺夹具的跳过）。
- **颜色维修的字节形状/帧/幂等/负对照**：`2026-10-04-tint/evidence/colour-fix-probe-output.txt`（ALL CHECKS PASSED）。
- **颜色修复的"收益"本身未被本集合覆盖**：本 12-mod 集合**没有注册自定义方块/物品颜色的 mod**。四个 marker 只证"维修执行了"；`fabric-rendering-v1` 的 `ColorProviderRegistry` 注册面复活这条收益**未被本读数覆盖**。

## 三档 · 已记录损失（不假装可用）

- **Indigo per-block 钩子（区块内自定义几何）**：重锚撤回（`TerrainRenderContext` 接收者为 `null`）；Fabric mod 在区块内的自定义几何**可能渲染错误或不渲染**。
- **Mod Menu 标题行替换**：按既有剪枝机制**带账退出**；该行不生效。
- **颜色族**：`fabric-rendering-v1` 的两个 client mixin 已重绑（marker 为证）；`fabric-registry-sync-v0` 的三条 pin **维持失效**（那是桥，不是锚点）。**且在未注册自定义颜色的集合上，基底原始键那半步在本场景行为等价**（`2026-10-04-tint` §5.3：控制/修复两臂确定性帧逐字节相同，预登记方向判据被证伪，如实记未证）。
- **启动日志仍见 `[Forbric/Load] 9 mod(s) 有一部分没有跑起来`**：本版**未修**。它不阻断 `strict`（不是 CONFIRMED required），但确实有 mod 未加载完。

## 不作超档声明

- 二档各项只有字节/单测证据，**没有**在真客户端世界深度复跑。
- 一档各项的上限是"这一次运行观测到"，不是"每个 mod 的每项功能都试过"。
- 本版未测更长的游戏内行为。

---

## 发布结果回填（2026-10-06）

- tag `v0.3.5-beta-1.21.1`（轻量 tag，指向发布提交 `327372f2`；其父 `80d13375` 为升版本提交）
- 同一提交已快进 `fork/1.21.1-port`（`af399135` → `327372f2`）
- Release `https://github.com/hi5548/Minecraft-Forbric-mod-loader/releases/tag/v0.3.5-beta-1.21.1`，附件：
  - `forbric-kernel-installer-0.3.5-beta.jar` sha256 `9ae62afa88a72d47e036c75debc64ff860f48d325375232516d499972b2ae191`
  - `forbric-kernel-installer-0.3.5-beta.zip` sha256 `9da990d2e6392dfe4c26a329b840313707bba306e38ec3c7698b102e432c90fc`
- 本地构建产物：`forbric-kernel-installer/build/{libs,dist}/`，副本 `/private/tmp/rel035/artifacts/`
- 安装器内嵌内核经逐字节校验 = 本次 gate 的 `a56bf626…`（`forbric/libs/net/forbric/forbric-kernel/0.3.5-beta/forbric-kernel-0.3.5-beta.jar`）
- `v0.3.4-beta-1.21.1` 已按惯例标注"已被取代"并撤下附件

### 安装校验（headless）

`java -jar forbric-kernel-installer-0.3.5-beta.jar --dir /Applications/.minecraft`，exit 0；逐条证据 `evidence/install-verification.txt`：

| 检查 | 结果 |
|---|---|
| 用户真实 12 mod（`versions/1.21.1-forbric/mods`）sha256 | 12/12 **逐字节不变** |
| `versions/` 版本目录 | 前后一致（无版本丢失/新增） |
| 全局 `mods/` 目录 | mtime 未变（2025-10-15），129 只 jar 原样 |
| `options.txt` / `saves/` / `config/` / `.forbric-kernel/` | mtime 均未变（安装只写 profile 与 `libraries/`） |
| 安装后的内核 | `libraries/net/forbric/forbric-kernel/0.3.5-beta/…jar` sha256 `a56bf626…`（= gate artifact） |
| profile | `versions/1.21.1-forbric/1.21.1-forbric.json` 指向 0.3.5-beta 的 kernel/loader/runtime，20 条 libraries |

（本次只做安装与文件级校验；**未**在安装实例上再启动一次游戏 —— 启动级读数以上面的 `strict` gate 为准。）
