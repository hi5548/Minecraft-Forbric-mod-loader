# fabric-particles-v1 的 `ParticleEngine is not an interface` —— 字节级定位、一处 flag 修复、一次 W7Harness 运行（+ 一次产物卫生复测）

**Verdict (EN).** **Fixed, and the rejection is gone.** fabric-api's `ParticleManagerAccessor` — an interface mixin
the kernel itself made un-applyable by giving its `@Accessor` `getFactories()` a real default body — now classifies
as Mixin's `ACCESSOR` variant again, so it applies to the class `ParticleEngine` instead of throwing
`@Mixin target type mismatch: … ParticleEngine is not an interface` and taking the whole
`fabric-particles-v1.client.mixins.json` with it. The fix is one flag (`ACC_SYNTHETIC`) inside the kernel's existing
repair, chosen over the adapter/pin fallback because it keeps the mixin applying rather than recording the accessor as
lost. One W7Harness run on the user's real 12 mods, against a reading pre-registered before it (re-measured once,
same criteria, only after an artifact-hygiene repair to the frozen jar — §7):
`run=PASS world=true frames=1 strict=TRUE confirmed_required=0 exit=0`, and
`grep -c "@Mixin target type mismatch"` = **0**, `grep -c "ParticleEngine is not an interface"` = **0**.

**答案（中文）。** **已修，拒绝消失。** 内核自己在合并基底上把 fabric-api 的 `ParticleManagerAccessor.getFactories()`
从 `@Accessor` 改成真正的 default 方法，Mixin 因此把整个接口 mixin 判成 `INTERFACE` 变体、要求 target 是接口，
而 target 是类 `ParticleEngine` —— 于是 `MixinConfig.prepareMixins` 抛
`@Mixin target type mismatch: … ParticleEngine is not an interface`，整份
`fabric-particles-v1.client.mixins.json` 的 mixin 全部不施加。修复只在原修复里加**一个 flag**（`ACC_SYNTHETIC`）：
Mixin 的 variant 判定明确跳过 synthetic，于是这个 mixin 回到它出厂时的 `ACCESSOR` 变体，正常落到类 target 上。
选它而不是"adapter/pin 并把损失记在明面"，是因为它让 mixin 继续生效，而不是把访问器功能记为损失。用户真实 12 mod
上 `W7Harness` 运行（判据运行前已登记；因冻结 jar 的构建产物卫生问题复测一次，判据未改，见 §7）：

| | 逐字读数 |
|---|---|
| boot gate | `run=PASS exit=0 world=true frames=1 stopped=true killed=false strict=TRUE confirmed_required=0 catalog_failures=[] loaded=true na=false`，0 份 crash-report，11 只依赖全 `OK` |
| 拒绝 | `grep -c "@Mixin target type mismatch"` = **0**，`grep -c "ParticleEngine is not an interface"` = **0**（控制侧：用户本机安装的 `0.3.5-beta` `latest.log` 里同两行，2×） |
| 修复落地 | `grep -c "SYNTHETIC default method over ParticleEngine"` = **2**（main + Render thread），即跑的就是修好的字节 |
| 其余不动 | `load-report.txt` 里 fabric-particles-v1 一条与 `0.3.5-beta` 发布 gate **逐字节相同**（只剩既有的 `BlockDustParticleMixin` 一项），聚合句仍是"9 个 mod 有一部分没有跑起来" |

预登记四条全部达成，证伪条件未触发。**未测**：这 12 只 mod 没有一只注册粒子工厂，所以"访问器真的能用"这一步没有观测者
（见 §6）。

---

## 1. 缺陷（字节链，`evidence/byte-evidence.txt`）

1. **合并基底只有一个 `providers` 字段**，且是 `Map<ResourceLocation, ParticleProvider<?>>`
   （`javap -p -s` 于 `patched-mc-merged-1.21.1.jar`）。fabric-api 的 refmap 把 `getFactories()` 的 `@Accessor("factories")`
   映射到 `field_3835:Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;` —— **这个字段在这块基底上不存在**，
   而 `@Accessor` 是**按名字 + 描述符**绑定的（`MemberInfo.matches` 描述符不符即 `NONE`），于是它会
   `InvalidAccessorException`，并把同接口里**能绑上的**两个 sprite 访问器一起拖下水。
2. 所以内核的修复把 `getFactories()` 从 `@Accessor` 改写成挂在
   `ParticleEngine.forbric$providerFactories(ParticleEngine)` 上的 **default 方法**，并移掉 `@Accessor`。
   这一步是对的，且与语义一致：调用方 `DirectParticleFactoryRegistry` 就是
   `checkcast ParticleManagerAccessor` + `invokeinterface getFactories:()L…Int2ObjectMap;`。
3. **变体判定因此翻转。** Mixin 0.8.7 的 `MixinInfo.getVariant(ClassInfo)`：类不是接口 → `STANDARD`；是接口时，
   只要有一个方法**既不是 `@Accessor`/`@Invoker` 也不是 synthetic**，就是 `INTERFACE` 变体，否则 `ACCESSOR`。
   出厂形态（三个 `@Accessor`）→ `ACCESSOR`；改成"无注解 + 有体的 default"→ `INTERFACE`。
4. `SubType$Interface` 的 `targetMustBeInterface = true`，`SubType$Accessor` 的 `= false`。`ParticleEngine` 是类，
   于是 `SubType.validateTarget` 抛 `@Mixin target type mismatch: … ParticleEngine is not an interface`，
   `MixinConfig.prepareMixins` 上抛，**整份 config 的四个 mixin 都不施加**。

## 2. 修复（一处，boot 半，`evidence/diff.patch`）

`ForbricMergedBaseCompatTransformer.readTheFactoriesThroughThatBridge`：改写后加 `Opcodes.ACC_SYNTHETIC`。

为什么这一个 flag 就是完整的修复（逐条对着 Mixin 的字节/源，§5 of `byte-evidence.txt`）：

- **分类**：`getVariant` 跳过 synthetic ⇒ 回到 `ACCESSOR` ⇒ `targetMustBeInterface = false` ⇒ 类 target 合法；
- **合并**：`MixinPreProcessorInterface.prepareMethod` 只在 `!isPublic && isSynthetic` 时提前 `return`，
  这条方法是 **public** synthetic，落到 `super.prepareMethod`；`attachMethods` 没有 `@Accessor` 可以把它分流到
  accessor 生成路径；`MixinApplicatorStandard.mergeMethod` 无条件 `this.targetClass.methods.add(method)`；
- **接口表**：`SubType$Accessor.getInterfaces()` 返回 mixin 自身的引用，`ParticleEngine` 被声明实现
  `ParticleManagerAccessor` —— 正是出厂访问器 mixin 的行为，也正是 `checkcast` 需要的。

**首选/兜底**：任务给的是"优先保留 Mixin 的接口分类，否则走 adapter/pin 并把损失写清楚"。合成标记这条路让
Mixin 自己的分类器把 mixin 认回 accessor 形态（不动 mixin 的**接口**本性，也不改 target），因此落在首选一侧；
adapter/pin 兜底（`SUPPRESSED_MIXINS` + 记录访问器功能损失）没有动用，也不需要。

## 3. 字节级探针（`evidence/probe-output.txt`，`evidence/probe-src/VariantProbe.java`）

独立探针：读**真实** fabric-api 的 `ParticleManagerAccessor.class`，跑**真实**的
`ForbricMergedBaseCompatTransformer`，然后把字节交给 **Mixin 自己的** `MixinInfo.getVariant`（`MixinBootstrap.init()`
之后，`-cp` 只用内核 boot classpath）。

```
shipped   getFactories flags = public=true abstract=true  synthetic=false (0x401)   accessors={…Atlas,…,Factories,…SpriteAware…}  variant=ACCESSOR
repaired  getFactories flags = public=true abstract=false synthetic=true (0x1001)   accessors={…Atlas,…SpriteAware…}                variant=ACCESSOR
control   (flag cleared)     = public=true abstract=false synthetic=false (0x1)      accessors={…Atlas,…SpriteAware…}                variant=INTERFACE
```

控制臂是同一批字节**只清掉一个 flag**：它复现出拒绝时的分类。所以"修复臂是 ACCESSOR"不是空断言 —— 差别就是那个 flag，
而那个 flag 正是 §1 里把整份 config 打掉的东西。（两个节点在问之前各自改名：`ClassInfo.fromClassNode` 按类名缓存，
不改名第二次问会拿到第一次的叶子。）

同样的两条断言进了单测 `MergedBaseParticleProvidersTest.mixinClassifiesTheRewrittenAccessorAsAnAccessorMixin`
（另一个控制：`@Accessor` 必须已移走、两个 sprite 访问器必须保持原样）。

## 4. 单测（`evidence/unit-test-reading.txt`）

本机跑 `gradlew test` 的完整套件跑不了（`energy-4.1.0-named.jar` 缺失 ⇒ `compileRuntimeJava` 跳过 ⇒ 大量真实字节测试会
`assumeTrue` 跳过；与 `2026-10-06-bughunt-create` 同因）。但**这个测试类**可以整跑：把 `-Pforbric.stagedRoot` 指向真实的
三个 staged jar（`-x compileRuntimeJava -x verifyRebornEnergy -x extractTransferApis`，只跳过游戏侧编译），
`-Pforbric.fabricApi` 指向真 fabric-api：

```
test: 6 tests, 0 skipped, 0 failed
  PASS theBaseDeclaresExactlyOneResourceLocationKeyedProvidersField()
  PASS aSecondPassLeavesBothHalvesAlone()
  PASS theEngineCarriesTheProviderViewBridge()
  PASS mixinClassifiesTheRewrittenAccessorAsAnAccessorMixin()
  PASS anotherClassIsUntouched()
  PASS theAccessorReadsThroughThatBridge()
```

顺带修了同一文件里 `anotherClassIsUntouched` 的一处既有顺序错：它先
`read(TestFixtures.mergedBase(), …)` 再 `assumeTrue(other != null)`，没有 staged 基底时是 NPE 而不是跳过；
改成先 `assumeTrue(TestFixtures.mergedBase() != null, …)`，与该文件其它用例的写法一致（见 §7 偏离）。

## 5. W7Harness 运行（`evidence/gate-reading.txt`、`evidence/console-markers.txt`）

- 集合：用户真实 12 mod（`versions/1.21.1-forbric/mods` 原样拷贝，sha256 在 `evidence/mod-set.txt`），
  subject `modmenu-11.0.5.jar`（fabric, `kind=popular`），其余 11 只作闭包。
- 装置：`w7/harness/sweep_client.py`，quick-play 进 `W7Client`，`-Dforbric.compatibilityPolicy=strict`、
  `-Dforbric.mixinFit=default`，`W7_JAVA` 钉 JDK 21.0.7，`--kernel-jar /private/tmp/particle-lane/kernel-fix.jar`
  （sha `ffdb6979…`），stage / mc 根 / corpus / 热 remap 缓存都在 `/private/tmp/particle-lane/`，
  boot 超时 600 / 停滞 300（只放宽超时）。
- 读数：`[client-sweep] STRICT PASS world=True frames=1 mod=OK 32s cause=None`，
  `joined world via quick-play: W7Client` → `client-ready after 200 world tick(s)` → `clean disconnect observed`，
  0 份 crash-report。
- 该行另带一句 `--boot-stall` 的机器负载提示（rule 3）：`371% CPU busy across 8 cores (load 14.4) — this row is
  timing-suspect`（被取代的首次读数同样是这一句，`load 7.9`）。本车道没有任何时限判据，如实记下。
- 控制侧（未复跑，逐字引用两处）：用户自己安装的 `0.3.5-beta`（内核 `a56bf626…`）本次 `latest.log` 里同一行出现 **2 次**
  （`[07:24:24] [main/ERROR]: [Mixin/mixin] @Mixin target type mismatch: … ParticleEngine is not an interface …`）；
  `2026-10-06-bughunt-create` 的控制臂（同内核）同样带着它，且该报告 §4 已把因果与该次实验的 Create 拆开。

## 6. 明写的空白与损失

- **"访问器真的能用"没有被这次运行观测。** 12 只 mod 里没有任何一只调用 `ParticleFactoryRegistry` / 该访问器，
  所以这次运行证明的是**分类不再被拒、mixin 被保留**，不是"自定义粒子工厂注册成功"。分类证明在 §3 的探针与 §4 的单测里，
  不在 boot 里。
- **既有的、未修的损失：`BlockDustParticleMixin` 被内核排除**（`load-report.txt` 里 fabric-particles-v1 唯一那一条），
  与 `0.3.5-beta` 发布 gate 逐字节相同 —— 本车道没有动它，也没有假装它可用。
- **`MixinFit` 的纯访问器预检对 remapped `@Accessor` 是假阳性**（本 boot 里 56 条同类 INFO）：它把映射后的
  `name:desc` 成员选择器整串当成字段名丢进 `findField`，而 Mixin 自己用 `TargetSelector.parseName` 解析同一串。
  三个锚点（`textureAtlas:…TextureAtlas;`、`spriteSets:Ljava/util/Map;`、`sprites:Ljava/util/List;`）在合并基底上都存在。
  这是 INFO、从不用于抑制，且两臂都在。**未修**（不在本判决路径上），论证见 `evidence/byte-evidence.txt` §6。
- 未做：更长游戏内行为、像素读数、第二条**控制**臂（控制读数逐字取自用户安装实例与 `2026-10-06-bughunt-create`）
  —— 除产物卫生导致的复测外，只按要求跑了这一次。

## 7. 偏离与动作清单

- **只改两个文件**：`ForbricMergedBaseCompatTransformer.java`（一处 flag + 相应 javadoc/日志）与
  `MergedBaseParticleProvidersTest.java`（加断言、加探针测试、修一处既有顺序错）。
- **跑了两次而不是一次 —— 只为构建产物卫生，判据未改。** 第一次的 `kernel-fix.jar`（`b4247eae…`）与这次同源，但被
  工作树里**既有的**陈旧重复条目污染（`build/classes/java/main` 里 1413 个 `X N.class`、`build/resources/main` 里 7 个
  `X 3.*`；多数日期 2026-10-04，早于本车道）；发布校验明确要求 0 个这种条目，所以它不是尖端字节的合格替身。抽查的
  `X N.class` 与其 `X.class` **逐字节相同**，那次读数同样是 `PASS/world=true/frames=1/strict=TRUE`、marker 3 项同值。
  删掉陈旧条目、重建 boot 半（其条目集合与已发布 boot jar **完全相同**）、重测一次，作为报告读数。两次都在
  `evidence/gate-reading.txt` / `console-markers.txt` 里逐字留着，预登记判据一字未动。详见 `build-provenance.txt`。
- **测试文件里的第三处改动**（`anotherClassIsUntouched` 的先 `assumeTrue` 再 `read`）不在本缺陷路径上；不修它，
  没有 staged 基底的机器上该用例报 NPE 而非跳过，会让本次验证无法读成"0 failed"。它是该文件既有意图（同一
  条件本就有一个放错位置的 `assumeTrue`），一行，照该文件其它用例的写法写。如实记在此。
- boot 半重建覆盖了工作树的 `build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar`（`build/` 是构建产物、未跟踪）；
  已发布 artifact、用户 12 mod、`w7/reports/**` 均未动。
- **另一条车道在本车道运行期间并发改了工作树**：`forbric-kernel/src/main/resources/net/forbric/kernel/mixin/carrier-stubs.txt`
  于 `07:04:39` 被修改（其报告目录 `run/compat/reports/2026-10-06-stub-table/`）。本车道的冻结 jar 建于 `07:01:26`
  （`kernel-fix.jar` 装配于 `07:01:41`），**早于**该改动，因此**不含**它（已核：jar 内 `carrier-stubs.txt` 没有那条新增行）。
  两者互不影响，本报告的所有读数只绑定到 `ffdb6979…` 这个 sha。本车道没有触碰那条车道的任何文件。
- 未留诊断代码；探针源码在 `evidence/probe-src/VariantProbe.java`，其余 scratch 在 `/private/tmp/particle-lane/`。

## 8. 证据清单（`evidence/`）

| 文件 | 内容 |
|---|---|
| `preregistration.md` | **运行前**写下的集合、装置、逐条判据与证伪条件 + 事后 §Adjudication（未改判据） |
| `mod-set.txt` | 12 只 jar 的 sha256、两臂内核 sha、JVM/stage/mc/corpus · 世界夹具 |
| `gate-reading.txt` | 报告那次运行的 harness stdout、`results.jsonl` 行、client smoke marker、crash-report 计数、load-report 头、compatibility-report 摘要 |
| `results.jsonl` / `results-row.json` | 该行逐字（原始 / 展开） |
| `console-markers.txt` | 修后 0 条拒绝、2 条修复 marker；修前两处逐字（用户安装的 `0.3.5-beta`、bughunt 控制臂） |
| `byte-evidence.txt` | 缺陷字节链：出厂访问器、映射后的 `@Accessor` 值、`getVariant`/`validateTarget`/`prepareMethod`/`mergeMethod` 逐条、以及 §6 的假阳性论证 |
| `probe-output.txt` / `probe-src/VariantProbe.java` | 独立探针（真实字节 × Mixin 自己的分类器），含"清 flag"控制臂 |
| `unit-test-reading.txt` | 对该类跑出的 `6 tests, 0 skipped, 0 failed` 与逐条 PASS |
| `load-report-comparison.txt` | fabric-particles-v1 条目修前/修后逐字节相同 + 聚合句 |
| `diff.patch` | 本车道源码的全部改动 |
| `harness-stdout.txt` | 报告那次运行的 harness stdout 原文 |
| `build-provenance.txt` | 修复臂 jar 的装配、sha、条目集合校验、构建产物卫生（陈旧重复条目）与被取代的首次读数 |
