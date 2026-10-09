# 0.3.9-beta —— 安装校验清单（install checklist）

被校验对象：headless 安装 `0.3.9-beta` 到 `/Applications/.minecraft`，并回答“用户 14 只 mod 与装上的内核 sha
是否如预期”。逐条的原始证据在 `evidence/install-verification.txt`、`evidence/gate-reading.txt`、
`evidence/build-provenance.txt`。

命令
```bash
java -jar forbric-kernel-installer-0.3.9-beta.jar --doctor --dir /Applications/.minecraft   # exit 0
java -jar forbric-kernel-installer-0.3.9-beta.jar --dir /Applications/.minecraft            # exit 0
```

## A. 产物与内核

- [x] **gate 工件 == 安装器内嵌内核 == 装上的内核**：三处 sha256 均为
      `c9c6abea9f81e9adf3ee363e53473067400749d25c3ace3f0b593cd594441cc8`
      （`/tmp/rel039/release-kernel.jar`；安装器 `forbric/libs/…/forbric-kernel-0.3.9-beta.jar`；
      `/Applications/.minecraft/libraries/net/forbric/forbric-kernel/0.3.9-beta/forbric-kernel-0.3.9-beta.jar`）
- [x] 内核带游戏侧：nested `META-INF/jars/forbric-kernel-runtime.jar`
      sha `ffcffd1106a3e015a9a3a8892e31505a1ad91d953429262ac7eda02f8c4132c4`（292 条目）
- [x] 0 条陈旧重名条目（`X N.class` / `X N.jar`）：内核 737 条目、安装器 105 条目，均 0
- [x] 安装器 jar / zip 的 sha256 与发布附件一致（见 `evidence/release-result.txt`）
- [x] 安装器自带的 link-check：`loaded 14379 classes … dangling references: 36 (known 36, new 0)`

## B. 用户的 14 只 mod

- [x] 安装前后 **14/14 逐字节相同**（sha256 逐条列在 `install-verification.txt`）；
      安装只动了 `versions/1.21.1-forbric/1.21.1-forbric.json` 与 `libraries/` 下新增的 0.3.9 条目
- [x] 实例用户文件 mtime 一律未动：`mods/`、`config/`、`options.txt`、`saves/`、`resourcepacks/`、
      `defaultconfigs/`、`data/`、`downloads/`、`logs/`、`log4j2.xml`、`.forbric-kernel/`、`forbric-mods.txt`、
      `.hmcl/`、`usercache.json`、`usernamecache.json`、`shooting_star.log`
- [x] 全局 `mods/`（129 jar）未动
- [x] profile json 是**唯一**有意变更：`4af30f56…`（0.3.8-beta）→ `df15cea8…`（0.3.9-beta）

## C. 安装重建的游戏侧 == 三臂所跑 stage

- [x] 安装器在本机重建 `patched-mc-merged` / `forge-runtime` / `neoforge-runtime`；与 gate stage 逐**条目**
      比较（name → sha256）：added 0 / removed 0 / content-diff 0（23535 / 4166 / 5030 条目）
- [x] jar 整体 sha 不同（zip 条目元数据），与历次发布同一口径：按内容比，不按 zip 字节比

## D. 启动级读数（不属于安装，属于同 sha 的三臂 gate）

- [x] 三臂均 `run=PASS exit=0 world=true frames=1 stopped=true killed=false strict=TRUE
      confirmed_required=0 catalog_failures=[] mod=OK cause=None`，0 份 crash-report
- [x] 内核 sha 在三条 `results.jsonl` 里都等于 `c9c6abea…`（未重建、未替换）
- [x] 本版七条新 marker 全部按预登记读出（`evidence/gate-reading.txt`）

## E. 未做（不作超档声明）

- [ ] 未在**安装实例**上再启动一次（启动级读数以上面三臂为准：内核字节同一、游戏侧逐条目同一）
- [ ] 未验证游戏内深度行为（像素/施法/伤害时的事件等）；三臂是“进世界 + 画一帧 + 干净退出”
