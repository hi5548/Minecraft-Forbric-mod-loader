# 0.3.4-beta —— 发布说明(三档)

**版本**:`0.3.4-beta`(安装器 `forbric-kernel-installer-0.3.4-beta`)
**来源**:`main` 尖端 `77577837`(ReviewFixes 十条 + `ResourcePackLoaderFix` 的 `0c0a2eea` + `InertApiFix` 的 Indigo 撤回 + 声音重锚);此前最后一次已验构建 `e11e4fcb`(`451c6780…`)。
**内核**(客户端 boot jar,带游戏侧)sha256:

```
bf56012d7bf6f025dba3628e43c303bf54970ce81142fa41b3833fb1dd8e21f4
```

由干净 worktree(`/tmp/w7-rel034-wt` @ `77577837`)构建,`-Pforbric.stagedRoot/mcLibraries/fabricApi/rebornEnergy` 取自 `p0/stage-1.21.1` + `p0/fixtures`,构建脚本自检 zip 可读、游戏侧存在、无 macOS 重名 class。

**gate 运行**:`W7Harness`,`w7/reports/2026-10-04-release-0.3.4-usermods/`,一遍即过、无重跑,`--kernel-jar` 钉住上面这只 jar(未重建)。主体 `modmenu-11.0.5.jar`,其余 11 只作闭包,JDK 21,默认策略。逐字读数(证据 `gate-reading.txt`,完整行 `per-mod/results.jsonl`):

```
run=PASS  exit=0  world=true  frames=1  stopped=true  killed=false  strict=TRUE
confirmed_required=0  seconds=35  java=jdk-21
joined world via quick-play: 1
[Forbric/Seed] seeded NeoForge LoadingModList with 64 mod(s): 1
```

12-mod 集合:modmenu, fabric-api, JEI, Sodium, Lithium, FerriteCore, ModernFix, EntityCulling, ImmediatelyFast, AppleSkin, Cloth Config, Placeholder API。

**安装**:下载 `forbric-kernel-installer-0.3.4-beta.zip`,解压,双击 `Forbric-Installer.command`(macOS 首次右键→打开)/ `Forbric-Installer.bat`;确认 **Game directory** → **Install** → 启动器里选 **`1.21.1-forbric`**。不需要任何 JVM 参数。

**发布结果**:
- tag `v0.3.4-beta-1.21.1`(轻量 tag,指向发布提交 `18c5e77a`;同一提交已快进 `fork/1.21.1-port`)
- Release `https://github.com/hi5548/Minecraft-Forbric-mod-loader/releases/tag/v0.3.4-beta-1.21.1`,附件:
  - `forbric-kernel-installer-0.3.4-beta.jar` sha256 `f4d934fc688e78db8238f53f95894d0cc6263ec33da92b2e45f5eb608c8de4ed`
  - `forbric-kernel-installer-0.3.4-beta.zip` sha256 `b7381edc53f6632ac178b3b83c6bf4329cc77b050026f9796a80bc0abdda8331`
- 本地构建产物:`/tmp/w7-rel034-wt/forbric-kernel-installer/build/{libs,dist}/`,副本 `/tmp/w7-rel034-artifacts/`
- 安装器内嵌的内核经逐字节校验 = 本次 gate 的 `bf56012d…`(`forbric/libs/net/forbric/forbric-kernel/0.3.4-beta/forbric-kernel-0.3.4-beta.jar`)
- `v0.3.3-beta-1.21.1` 已按惯例标注"已被取代"并撤下附件

---

## 一档 · 实测通过(在真客户端、世界深度跑过)

1. **用户真实 12-mod 组合**:`run=PASS / world=true / frames=1 / strict=TRUE / confirmed_required=0`,11 只依赖全部 `OK`,默认策略、零 JVM 参数。这是本组合第一次以本 sha 进世界。
2. **ResourcePackLoader 墙已过**(`0c0a2eea`,1.21.1 的 NeoForge `LoadingModList` 播种):同一集合此前死于 `Could not initialize class …ResourcePackLoader`;本运行 `NoClassDefFoundError` = **0**,`[Forbric/Seed] seeded NeoForge LoadingModList` 逐字出现。
3. **P5**(FRAPI 证据判据认出 Sodium NeoForge 构建真正调用的入口点):同一集合上 Sodium 的配置墙清除,启动走到世界(`Sodium's config could not be found` = **0**)。
4. **P1 的两条玩家可见 ERROR 缺席**:`could not apply the server's ids` = **0**、`they keep their local ids` = **0**。
5. **声音流重定向**(`2868ebd6`,自定义 `AudioStream` 按 1.21.1 世代重绑):在此前的世界深度读数上证得生效,并已从"已记录损失"表中移除。

## 二档 · 仅离线证明(字节证据 / 单测;本集合未在世界深度复跑)

- **P1 的 id 重映射行为本身**:新 `util/IdentifierNames` 两代访问器(`location()`/`identifier()`)与两类名(`ResourceLocation`/`Identifier`);`KernelRegistryAliasesTest` 加一只只有 `location()` 的键、新增 `IdentifierNamesTest`。运行只证其 ERROR 行缺席,重映射行为本身**未**在跑中观测。
- **P2** 覆盖条件否决的重锚(合成 1.21.1 section 红→绿);本集合不经过 gated overlay。
- **P3** 花盆修复全部三条子编辑按真实字节重推(合成 1.21.1 形状红→绿)。
- **P4** 别名对等键改 `ResourceLocation`(真实类探针:`NamespacedWrapper` hooks 4→8、`NamespacedDefaultedWrapper` 0→1)。
- **P6 / P7** 两条 HEDGE(随机源精度 / EnderDragonPart):从每次启动的 ERROR 降为提示;26.2 夹具上仍命中。
- **P8–P10** 卫生:当前版本文档不再写 `26.2`、`buildForbricJars` 转发整族 `forbric.*`、`bundleForbric` 守卫改精确坐标。

## 三档 · 已记录损失(不假装可用)

- **Indigo per-block 钩子(区块内自定义几何)**:重锚在第二次世界深度读数里 NPE(重定向 handler 的 `TerrainRenderContext` 接收者为 `null`),按判据**撤回**;代价是 Fabric mod 在区块内的自定义几何**可能渲染错误或不渲染**。
- **Mod Menu 标题行替换**:按既有剪枝机制**带账退出**——该 CONFIRMED required 不再单独卡住真实启动,代价是那一行不生效。
- **颜色族**(`ColorProviderRegistry`、`FluidRenderHandlerRegistry` 等):**维持失效**;修复形状已给出(重绑 `@Shadow` 字段 + 正常化合并基底的取值键),未落地。
- **启动日志仍见 `[Forbric/Load] N mod(s) did not finish loading`**:本版**未修**。它不阻断 `strict`(不是 CONFIRMED required),但确实有 mod 未加载完。

## 不作超档声明

- 二档各项只有字节/单测证据,**没有**在真客户端世界深度复跑。
- 一档各项的上限是"这一次运行观测到",不是"每个 mod 的每项功能都试过"。
- 本版未测更长的游戏内行为。
