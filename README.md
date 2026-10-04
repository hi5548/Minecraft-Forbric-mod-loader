# Forbric

[中文](#中文) · [English](#english)

## 中文

> 本分支把加载器重定向到 **Minecraft 1.21.1**；这次移植做了什么、实测通过率与已知限制见 [PORT-1.21.1.md](PORT-1.21.1.md)。

**一个 Minecraft 实例，同时运行 Fabric mod、Forge mod 和 NeoForge mod。**

版本 0.3.0 · Minecraft 1.21.1

## 它能做什么

Minecraft 的 mod 分三种，通常你只能选其中一种。每个 mod 都是为 **Fabric**、**Forge** 或 **NeoForge** 中的某一个做的，而且只能在它对应的那一个上运行。把 Fabric mod 放进 Forge 游戏里，什么也不会发生。所以大多数人会同时维护好几套互相独立的环境，而不管启动哪一套，大部分 mod 都躺在另外几套里。

Forbric 是第四种东西，装了它就不用再装那三个。你把**所有** mod 放进**同一个**文件夹——Fabric、Forge、NeoForge 混在一起，不用分类——Forbric 会打开每个文件，判断它是哪一种，然后加载它。所有 mod 都在同一个世界里同时运行。

它还会把你装的所有东西列在同一张列表里。暂停菜单和标题界面上会多出一个 Forbric 的 Mods 按钮；在这张列表里，不管一个 mod 属于三种中的哪一种，你都能打开它自己的设置界面（Fabric mod 需要同时装了 Mod Menu 才行）。

**你也许听说过 Kilt 或 Sinytra Connector。** 它们是装在普通加载器上的 mod，在一方内部重新实现另一方的功能——相当于屋里请了个翻译。Forbric 则是加载器本身。Fabric Loader，以及 Forge 和 NeoForge 里面自带的加载器，都不会启动；它们的活由 Forbric 来干——找到 mod、启动 mod、按顺序运行 mod——并尽量按每个 mod 自己的加载器那样去做。你的 mod 调用的是真正的 Fabric API，以及真正的 Forge 和 NeoForge 代码；这一部分不是重新实现的。所以这不是三个加载器并排运行，而是一个新的加载器，把三种 mod 和它们依赖的真实代码放进同一个游戏。

不过 Forge 和 NeoForge 都会修改 Minecraft，而且常常改的是同一个地方，而一个游戏里每个地方只能保留一个版本，所以 Forbric 大多保留 NeoForge 的版本。然后由 Forbric 自己的衔接代码让其他 mod 继续工作：把游戏事件转交给 Forge mod，把 Fabric mod 的修改挪到代码现在所在的位置，并让来自不同加载器的 mod 互相传递物品、流体和能量。这些衔接代码同样是一种翻译，而且还没有完成，这也是一部分 mod 仍然失败的原因之一。

Connector 已经很成熟，Forbric 还不是，所以如果 Connector 已经能运行你想要的 mod，就用 Connector。Forbric 是为它照顾不到的情况准备的。

## 安装方法

### 准备工作

- **一个从 `.minecraft/versions` 文件夹启动版本的启动器。** Forbric 已在 Windows 上用 **PCL2** 测试过。HMCL 读取的是同样的文件，应该也能用，但还没有测试过。官方 Minecraft 启动器也还没有测试过（见第 6 步）。Prism Launcher 和 MultiMC 使用各自独立的实例，看不到 Forbric。
- **Java。** 如果你已经能玩 Minecraft，你就已经有了。就算你从没自己装过 Java，安装器也能找到你的启动器下载的那一份。
- **网络连接**，以及安装过程中约 730 MB 的可用磁盘空间（完成后约保留 190 MB）。

你**不需要**先装 Minecraft 1.21.1。如果没有，安装器会自动下载。你也**不需要** Fabric、Forge 或 NeoForge，也不用去找其他任何文件：Forbric 需要的一切都由安装器下载并构建。不过你的 mod 照常还需要各自的前置 mod，比如大多数 Fabric mod 都需要 Fabric API。

### 安装

1. 打开[最新发布版](https://github.com/hi5548/Minecraft-Forbric-mod-loader/releases/latest)。

2. 把**两个**文件下载到**同一个文件夹**：

   | 你的系统 | 下载 |
   | --- | --- |
   | Windows | `forbric-kernel-installer-0.3.0.jar` **和** `Forbric-Installer.bat` |
   | macOS | `forbric-kernel-installer-0.3.0.jar` **和** `Forbric-Installer.command` |
   | Linux | `forbric-kernel-installer-0.3.0.jar`（用 `java -jar` 运行） |

3. **双击 `.bat`（Windows）或 `.command`（macOS）。** 它会查找 Java，包括启动器放在默认 Minecraft 文件夹里的那一份，然后用它启动安装器。在某些 Windows 电脑上，直接双击 jar 只会闪一下黑窗口，这是因为 Windows 曾被设置成用一种行不通的方式打开 `.jar` 文件；用脚本就能避开这个问题。如果双击 jar 确实打开了安装器窗口，那也没问题：是同一个安装器。

   在 macOS 上第一次运行时，你可能需要右键点击这个文件，选择**打开**，然后确认。这是 macOS 对下载的文件比较谨慎，并不是出错了。

4. **会弹出一个窗口。** 唯一需要关心的是 **Game directory**（游戏目录）这一栏——也就是你的启动器使用的 `.minecraft` 文件夹。它一开始就填好了你的系统上通常的位置：

   - Windows：`C:\Users\<your name>\AppData\Roaming\.minecraft`
   - macOS：`~/Library/Application Support/minecraft`
   - Linux：`~/.minecraft`

   如果你的启动器把 `.minecraft` 放在别处（PCL2 和 HMCL 可以把它放在启动器程序所在的文件夹里），就点 Game directory 旁边的 **Browse…**，选择那个文件夹。

   其他设置都不要动。尤其是 **Built artifacts** 要留空——它只给从源码构建了 Forbric 游戏文件的开发者使用。

5. **点击 Install，然后等待。** 第一次安装需要几分钟。它在下载 Minecraft、Forge 和 NeoForge 各自的文件，并在你的电脑上把它们组装起来，因为按照法律，这些文件不能做成现成的包直接分发。安装期间请保持联网。之后再安装会复用磁盘上已有的文件，很快就能完成。

6. **打开你的启动器。** 列表里会出现一个名为 **`1.21.1-forbric`** 的新版本。像启动其他版本一样启动它即可。PCL2 会把它显示成 Fabric 版本，这是正常的（见下一节）。

   安装器不会把它加进官方 Minecraft 启动器的安装列表。在官方启动器里，你大概得自己新建一个安装，并选择 `1.21.1-forbric`。

> 想在点击 Install 之前先检查一下你的电脑？在 jar 所在的文件夹里运行这条命令。它只做检查，不写入任何东西：
>
> ```bash
> java -jar forbric-kernel-installer-0.3.0.jar --doctor
> ```

### mod 放在哪里

**Fabric、Forge 和 NeoForge 的 mod 都放进同一个 `mods` 文件夹。** 具体是哪个文件夹取决于你的启动器，而不是 Forbric：

- 如果你的启动器让每个版本各自独立（通常叫“版本隔离”；PCL2 和 HMCL 都能这样设置）：`.minecraft/versions/1.21.1-forbric/mods/`
- 否则就是你所选 Game directory 里共用的 `.minecraft/mods/`。所有没有独立文件夹的版本都用这个文件夹，所以 Forbric 也会尝试加载里面已有的 mod。

安装器完成时会把这两个位置都列出来。不确定你的启动器用的是哪一个？先启动一次游戏：正确的那个 `mods` 文件夹旁边会出现一个名为 `.forbric-kernel` 的文件夹。

你的启动器可能会把 `1.21.1-forbric` 称为 Fabric 版本。这是有意为之：启动器每个版本只显示一个 mod 加载器，所以 Forbric 的版本告诉它的是 Fabric。PCL2 会读取这一信息，把 `1.21.1-forbric` 当作 mod 版本，并在它的 mod 浏览器里优先推荐 Fabric 版的 mod。其他启动器可能会把它显示为普通的 Minecraft。不管怎样，Forbric 都会从 `mods` 文件夹加载 Fabric、Forge 和 NeoForge 的 mod。

有一点要注意：很多 mod 同时有 Fabric 版、Forge 版和 NeoForge 版。每个 mod 只放**一个**版本进文件夹。如果你放了不止一个，Forbric 仍然只会运行其中一个。第一次遇到这种情况时，它会把自己的选择写进 `mods` 文件夹旁边的 `forbric-mods.txt`，你可以在那里改选另一个版本。

多个 mod 共同需要的前置（库）mod 也是一样：通常一个版本就够了，因为 Forge 或 NeoForge 的 mod 一般可以使用其前置 mod 的 Fabric 版，反过来也一样。为两个加载器各装一份前置，并不会让每个 mod 各用各的：Forbric 仍然只运行其中一份。如果一个 mod 直接挂接在另一个 mod 上（比如 Iris 之于 Sodium），两者要用同一个加载器的版本。关于 Sodium，另见下文的*一个已知的崩溃*。

### 装好了吗？

打开暂停菜单。那里有一个画着**三个叠在一起的方块**的按钮，提示框上写着 *Mods (Forbric)*。点开是一张列表，列出你装的所有 mod，每一行都标明了它是哪一种。选中一个 mod 后点 **Config**，或者双击那一行，就能打开这个 mod 自己的设置。

Fabric mod 把自己的设置界面交给 Mod Menu 管理，所以只有同时装了 Mod Menu，Fabric mod 在这张列表里才会有 **Config** 按钮。装了 Mod Menu 后，标题界面和暂停菜单上都会有**两个** Mods 按钮。请用画着三个方块的那个：它能打开三种 mod 的设置，而 Mod Menu 自己的按钮只能打开 Fabric mod 的设置。

### 出了问题怎么办

| 你看到的情况 | 怎么做 |
| --- | --- |
| **弹出窗口说某个 mod 缺少它需要的前置** | 窗口里会写出是哪个 mod、需要装什么。装上它，或者点 **继续启动**。 |
| **弹出窗口说必要的 mod 功能不可用** | mod 的某个部分没能启动。你可以继续玩，也可以退出游戏并移除那个 mod。 |
| **游戏崩溃** | 打开 `mods` 文件夹旁边的 `.forbric-kernel` 文件夹。里面的 `crash-analysis.txt` 会列出嫌疑最大的 mod；完整的崩溃报告在 `crash-reports/` 里。移除这些 mod 后再试一次。用的是 NeoForge 版的 Sodium？请看下文的*一个已知的崩溃*。 |
| **mod 装上了，但没有任何效果** | 打开 Forbric mod 列表——没加载完的 mod 会在那里被标出来。同样的列表也在 `load-report.txt` 里，位于 `mods` 文件夹旁边的 `.forbric-kernel` 文件夹中。常见原因是这个 mod 是为别的 Minecraft 版本做的，或者你装了它的两个版本。 |
| **专用服务器启动不了**，日志说是兼容策略让它停下的 | 服务器没有界面可以询问你，所以会直接停下。移除它点名的 mod，或者在服务器的启动命令里加上 `-Dforbric.compatibilityPolicy=continue`，强行继续运行。 |
| **客户端一启动就退出**，日志说是兼容策略让它停下的（退出码 78） | 内核发现「必要的 mod 功能不可用」，默认策略是**询问你**；但询问窗口可能在游戏窗口出现之前来不及弹出。给这个版本加一条 JVM 参数 `-Dforbric.compatibilityPolicy=continue`（HMCL：版本设置 → 高级设置 → JVM 参数），表示「把损失记录在案，但照样启动」。`compatibility-report.json` 里会列出记录了什么。 |
| **Continuity 加载了，但玻璃方块之间仍然有边框** | 在 **选项 → 资源包** 中启用 **Default Connected Textures**（Continuity 自带）。它内置的资源包是可选的，光装上 mod 并不会自动启用。在 0.3.0 上，即使这样做了，Fabric 版的 Continuity 仍可能留下边框。这是 Forbric 的 bug。0.3.1 beta 已经修复（Releases 页面上的预发布版），但还没有进入正式版。另一个选择是使用为你的 Minecraft 版本制作的 NeoForge 版 Continuity。 |
| **安装好像卡住了** | 通常是你和 Mojang 服务器之间有代理或 VPN。运行*安装*一节末尾的 `--doctor` 检查，然后关掉代理或 VPN 再试一次。 |

遇到其他问题？可以在[这里](https://github.com/hi5548/Minecraft-Forbric-mod-loader/issues/new?template=bug_report.yml)反馈。

### 更新与卸载

**更新**：用相同的设置运行新的安装器。你的 mods 文件夹和世界都不会被动到。从 0.2.0 升级后的第一次安装会重新构建 Forbric 的游戏文件，所以又要花上几分钟。

**卸载**：删除 `.minecraft/versions/1.21.1-forbric/`。如果你的启动器让每个版本各自独立，这个文件夹里还存着这个版本的 mod、世界和设置，所以请先把想保留的东西复制出来。如果还想收回磁盘空间，再删除 `.minecraft/.forbric-build/` 和 `.minecraft/libraries/net/forbric/`。

## 0.3.0 更新内容

**能用的 mod 更多了。** 我们从 Modrinth 随机挑了三批 mod，每批约 100 个（既有热门的，也有随机的，三种都有），然后每次只装其中一个 mod 来启动游戏。**0.2.0 上有 80.5% 无错误加载，0.3.0 上是 89.0%**（判定标准：日志里没有 mod 加载失败）。在 0.3.0 上，有 91.8% 能进入世界，有 79.1% 同时做到了没有任何部分被报告为无法工作。这个测试只检查 mod 能不能加载、世界能不能打开；不会逐个试 mod 的功能，也不测多个 mod 放在一起。

新增：

- **不同加载器的 mod 之间可以互相传递物品、流体和能量**——比如 Fabric 的管道或漏斗可以给 NeoForge 或 Forge 的机器供料。
- **当 mod 的某个部分无法工作时，开始游戏前会弹出窗口**，让你选择继续还是退出，而不是事后才发现。
- **Forbric mod 列表会标出没加载完的 mod**，并说明原因。
- **崩溃之后会生成 `crash-analysis.txt`**，列出最可能导致崩溃的 mod。
- **警告窗口支持 10 种语言**，包括中文，并会建议你该装什么。
- Forbric 现在使用正式版的 NeoForge 而不是 beta 版，所以需要较新 NeoForge 的 NeoForge mod 也能加载了，标题界面上也不再显示“beta”。

修复：

- 以下情况下的崩溃：装有 Fabric API 时用熔炉冶炼、合成或酿造，与末影龙战斗，使用花盆，或放置来自 Fabric 或 Forge mod 的流体。
- 某些 mod 组合曾让游戏在启动时停在黑屏。
- 之前地牢不会生成，同一个种子生成的地形也与原版 Minecraft 不一致。
- 物品提示框里之前缺少附魔、物品描述（lore）、属性和耐久度。
- 许多 Forge mod 加载了却不起作用——现在它们的命令、按键绑定、屏幕上的显示内容、配置文件、生物和对世界的改动都能正常工作了。
- Xaero's Minimap 和 World Map（Forge 版）曾在启动时崩溃。
- 在 0.2.0 上崩溃或失败、现在能正常工作的 mod 包括 Farmer's Delight Refabricated、Better End、Better Nether、Entity Culling、More Culling、Friends & Foes、Repurposed Structures、Traveler's Backpack、Shoulder Surfing 和 YetAnotherConfigLib。

不如 0.2.0 的地方：在同一测试中，**Alex's Mobs Continued**、**Drippy Loading Screen**、**FancyMenu** 和 **Easy Magic**（NeoForge 版）在 0.2.0 上能用，在 0.3.0 上不能用了。

面向服主的改动：如果某个 mod 缺少它需要的部分，专用服务器现在会在启动时停下，因为没有界面可以询问你（见上文的*出了问题怎么办*）。

## 我们的承诺

**不会动你现有的 Minecraft。** Forbric 与其他所有东西并存安装。你的 Fabric、Forge 和 NeoForge 环境、你的世界、你的其他 mod 文件夹，都和原来一模一样。

**卸载就是删掉一个文件夹。** 它不会在你的系统里到处留下东西，你不玩游戏时也不会有任何东西在运行。

**没有任何隐藏。** 所有源代码都在这里，许可证是 Apache-2.0。本仓库不包含任何 Minecraft、Forge 或 NeoForge 的代码——这些都是在你安装时从它们各自的服务器上获取，并在你的机器上组装的。

我们**不**承诺的是：

**我们无法保证任何一个具体的 mod 能用。** 在我们自己的测试里，大约每十个 mod 中仍有一个单独运行就会失败；而各自单独能用的 mod，放在一起时也仍可能冲突。

**mod 的某个部分可能不崩溃也不工作。** 当 mod 的某一部分接不上游戏时，Forbric 会让这个 mod 的其余部分继续运行，而不是直接停下，并且通常会告诉你——在开始游戏前的窗口里，以及 Forbric 的 Mods 列表里。如果某一部分接上了、但行为不对，Forbric 和我们的测试都发现不了。

**一个已知的崩溃：** 除非同时装了 Fabric API，否则 NeoForge 版的 Sodium 会在启动时崩溃，需要它的 mod（例如 NeoForge 版的 Iris 和 Sodium Extra）也一样。这是 Forbric 的 bug，并不是你安装的方式有错。在修复之前，请把 Fabric API 也放进你的 `mods` 文件夹，或者改用 Fabric 版的 Sodium，以及挂接在它上面的那些 mod 的 Fabric 版。

**这是一个处于 0.3.0 版本的研究项目。** 没有技术支持，没有路线图，一切都还会变。

Forbric 与 Mojang、FabricMC、MinecraftForge 或 NeoForged 均无关联。

---

### 写给 mod 开发者

**你的 mod 不需要做任何修改。** 它调用的是真正的 Fabric API、MinecraftForge 或 NeoForge 类，所以不存在需要你专门针对它编写代码的兼容层。Forbric 重新实现的是加载器：类加载、mod 发现、加载顺序、生命周期、Mixin 服务，以及 Fabric Loader 的 API（Forbric 自带 Fabric Loader 的公开 API 类型——这些类型保留 FabricMC 的版权——并实现了它们；Fabric Loader 本身从不运行）。游戏本身也不一样：安装器会用两个 Forge 系的补丁构建出一个合并后的游戏 jar。这对你的 mod 意味着：

- **游戏是一个合并后的 jar。** 凡是 MinecraftForge 和 NeoForge 都打了补丁的同一个方法（大约一千个），只保留了一个版本：除其中五个保留的是 MinecraftForge 的版本外，其余都保留 NeoForge 的。因此丢失了调用的事件——几乎都是 MinecraftForge 的，另有少数 NeoForge 的，比如物品提示框和界面打开——只有在 Forbric 重新发出时才会到达你的监听器，没有桥的则永远不会触发（[introduction.md §8](introduction.zh-CN.md#8-事件桥)）。调用在合并中保留下来的事件照常触发。NeoForge 自带的 coremod 不会被加载；它们所做的改写由 Forbric 自己完成。
- **mixin 会应用在这份合并后的代码上。** Forbric 会放宽 mod 的 mixin 配置（`required: false`、`defaultRequire: 0`），因此目标缺失的注入器会什么也不做，而不是报错失败，除非它自己设置了 `require`。目标挪了位置的注入器，Forbric 会把它跟着挪过去；如果一个 mixin 的目标全都不存在，就丢弃整个 mixin（[§7](introduction.zh-CN.md#7-合并基底上的-mixin)）。
- **启动按 Forbric 的顺序进行**，与各加载器原生的顺序接近，但并不相同（[§3](introduction.zh-CN.md#3-启动顺序)）。
- **不支持启动扩展：** MinecraftForge 的 ModLauncher 服务（`ITransformationService`、`ILaunchPluginService`）和 `coremods.json`、NeoForge 的 `ClassProcessorProvider`，以及自定义的 mod 定位器或依赖定位器。目前它们会被直接跳过，不会有任何警告。

更多细节：

- [introduction.md](introduction.zh-CN.md)——面向开发者介绍 Forbric 的内部工作方式：启动顺序、三种 mod 如何一起加载、安装器构建了什么，以及它是怎样测试的。
- [forbric-kernel/README.md](forbric-kernel/README.zh-CN.md)——内核的简要概述；安装器安装的就是内核。

要从源码构建内核，你需要 `git` 和 JDK 21 或更新版本。内核有自己的 Gradle 构建；引导侧的编译不需要 Fabric 底座：

```bash
git clone https://github.com/hi5548/Minecraft-Forbric-mod-loader.git
cd Minecraft-Forbric-mod-loader
cd forbric-kernel && ./gradlew build
```

**全新克隆上构建通过，并不代表游戏能启动。** 本地没有暂存的游戏 jar 时，运行时源码集和传输测试会被跳过，需要这些 jar 的测试也可能被跳过。CI 验证的是引导侧构建和独立的加载器构建；它不会启动 Minecraft。

要在开发用的游戏里运行当前源码，请安装 **JDK 25+ 和 Python 3.9+**，然后在仓库根目录运行（Windows 上把 `python3` 换成 `py`）：

```bash
python3 tools/dev.py client                 # automatically prepare dependencies, build and launch
python3 tools/dev.py server --accept-eula   # a separate local server instance
```

下载的文件、组装好的 jar 和实例都放在被忽略的 `forbric-kernel/.dev/` 目录下。也可以使用 Gradle 入口：先运行 `prepareDev`，再在另一次调用中运行 `runClient` 或 `runServer`。配置和测试覆盖范围见[内核开发指南](forbric-kernel/run/README.md)。`check` 包含工具自测；`integrationTest` 不接受被跳过的断言，并且需要完整的测试夹具。构建第一代 `forbric-loader/` 本身时，请运行 `./bootstrap.sh`；独立的合并工具和当前的内核开发流程都不需要它。

### 许可证

Apache-2.0——见 [LICENSE](LICENSE) 和 [NOTICE](NOTICE)。净室边界的说明见 [forbric-loader/CREDITS.md](forbric-loader/CREDITS.md) 和 [forbric-loader/MAPPINGS.md](forbric-loader/MAPPINGS.md)。

---

<a id="english"></a>

## English

> This branch retargets the loader to **Minecraft 1.21.1**; what the port changed, the measured rates and
> the known limits are in [PORT-1.21.1.md](PORT-1.21.1.md).

**One Minecraft instance that runs Fabric mods, Forge mods and NeoForge mods at the same time.**

Version 0.3.0 · Minecraft 1.21.1

## What it does

Minecraft mods come in three kinds, and normally you have to pick one. A mod is built for **Fabric**, or
for **Forge**, or for **NeoForge**, and it only works on the one it was built for. Put a Fabric mod into
a Forge game and nothing happens. So most people keep several separate setups, and whichever one they
start, most of their mods are sitting in the other ones.

Forbric is a fourth thing you install instead of those three. You put **every** mod into **one** folder —
Fabric, Forge and NeoForge mixed together, no sorting — and Forbric opens each file, works out what kind
it is, and loads it. All of them are running in the same world at the same time.

It also gives you one list of everything you have installed. The pause menu and the title screen get a
Forbric mods button, and from that list you can open a mod's own settings screen, whichever of the
three it belongs to (for Fabric mods, only when Mod Menu is installed too).

**You may have heard of Kilt or Sinytra Connector.** Those are mods you add to a normal loader, and they
re-create one side's features inside the other — a translator in the room. Forbric is the loader itself.
Fabric Loader and the loaders inside Forge and NeoForge never start; Forbric does their job — finding the
mods, starting them, running them in order — and tries to do it the way each mod's own loader would. Your
mods call the real Fabric API and the real Forge and NeoForge code; that part is not re-created. So this is
not three loaders running side by side: it is one new loader that puts all three kinds of mods, and the
real code they rely on, into one game.

But Forge and NeoForge both change Minecraft, often in the same spots, and one game can hold only one
version of each spot, so Forbric mostly keeps NeoForge's. Its own glue then keeps the other mods working:
it passes game events on to Forge mods, moves Fabric mods' changes to where the code now sits, and lets
mods from different loaders hand each other items, fluids and energy. That glue is translation too, and
it is not finished, which is one reason some mods still fail.

Connector is mature and Forbric is not, so if Connector already runs the mods you want, use Connector.
Forbric is for the cases it cannot reach.

## How to install

### Before you start

- **A launcher that starts versions from your `.minecraft/versions` folder.** Forbric has been tested
  with **PCL2** on Windows. HMCL reads the same files and should work, but has not been tested yet. The
  official Minecraft Launcher has not been tested either (see step 6). Prism Launcher and MultiMC keep
  their own instances and will not see Forbric.
- **Java.** If you can already play Minecraft, you have it. The installer finds the copy your launcher
  downloaded, even if you never installed Java yourself.
- **An internet connection**, and about 730 MB of free disk while it works (about 190 MB is kept
  afterwards).

You do **not** need to install Minecraft 1.21.1 first. If you do not have it, the installer downloads it.
You also do **not** need Fabric, Forge or NeoForge, and you do not need to find any other files: the
installer downloads and builds everything Forbric needs. Your mods still need their own prerequisites as
usual, for example Fabric API for most Fabric mods.

### Install

1. Open the [latest release](https://github.com/hi5548/Minecraft-Forbric-mod-loader/releases/latest).

2. Download **two** files into the **same folder**:

   | You are on | Download |
   | --- | --- |
   | Windows | `forbric-kernel-installer-0.3.0.jar` **and** `Forbric-Installer.bat` |
   | macOS | `forbric-kernel-installer-0.3.0.jar` **and** `Forbric-Installer.command` |
   | Linux | `forbric-kernel-installer-0.3.0.jar` (run it with `java -jar`) |

3. **Double-click the `.bat` (Windows) or the `.command` (macOS).** It looks for Java, including the copy
   a launcher keeps in the usual Minecraft folder, and starts the installer with it. On some Windows PCs,
   double-clicking the jar itself only flashes a black window, because Windows was once told to open
   `.jar` files in a way that does not work; the script avoids that. If double-clicking the jar does open
   the installer window, that is fine too: it is the same installer.

   On macOS the first time, you may need to right-click the file and choose **Open**, then confirm. That
   is macOS being careful about downloads, not an error.

4. **A window opens.** The only field that matters is **Game directory** — the `.minecraft` folder your
   launcher uses. It starts out filled in with the usual place for your system:

   - Windows — `C:\Users\<your name>\AppData\Roaming\.minecraft`
   - macOS — `~/Library/Application Support/minecraft`
   - Linux — `~/.minecraft`

   If your launcher keeps `.minecraft` somewhere else (PCL2 and HMCL can keep it in the same folder as the
   launcher program), press **Browse…** next to Game directory and choose that folder.

   Leave everything else alone. In particular, leave **Built artifacts** empty — it is only for developers
   who built Forbric's game files from source.

5. **Press Install and wait.** The first install takes several minutes. It is downloading Minecraft's,
   Forge's and NeoForge's own files and putting them together on your computer, because those files
   cannot legally be handed out ready-made. Stay connected while it runs. Installing again later reuses
   what is already on disk and is quick.

6. **Open your launcher.** A new version called **`1.21.1-forbric`** is in the list. Start it like any
   other version. PCL2 shows it as a Fabric version; that is expected (see the next section).

   The installer does not add it to the official Minecraft Launcher's list of installations. There you
   would probably have to create a new installation and pick `1.21.1-forbric` yourself.

> Want to check your computer before you press Install? Run this in the folder with the jar. It only
> looks, and writes nothing:
>
> ```bash
> java -jar forbric-kernel-installer-0.3.0.jar --doctor
> ```

### Where to put mods

**Fabric, Forge and NeoForge mods all go in the same `mods` folder.** Which folder that is depends on
your launcher, not on Forbric:

- If your launcher keeps each version separate (often called version isolation; PCL2 and HMCL can do
  this): `.minecraft/versions/1.21.1-forbric/mods/`
- Otherwise the shared `.minecraft/mods/` in the Game directory you chose. Every version that does not keep
  its own folder uses this one, so Forbric will also try to load any mods already in it.

The installer names both when it finishes. Not sure which one your launcher uses? Start the game once:
a folder called `.forbric-kernel` appears next to the right `mods` folder.

Your launcher may call `1.21.1-forbric` a Fabric version. That is on purpose: a launcher shows only one mod
loader per version, so Forbric's version tells it Fabric. PCL2 reads this, treats `1.21.1-forbric` as a
modded version and suggests Fabric builds first in its mod browser. Other launchers may show it as plain
Minecraft. Either way, Forbric loads Fabric, Forge and NeoForge mods from the `mods` folder.

One thing to watch: many mods come as a Fabric build, a Forge build and a NeoForge build. Put **one**
build of each mod in the folder. If you add more than one, Forbric still runs only one of them. The first
time this happens it writes its choice to `forbric-mods.txt` next to your `mods` folder, where you can pick
the other build.

The same goes for a prerequisite (library) mod that several of your mods need: one build is usually
enough, because a Forge or NeoForge mod can normally use the Fabric build of its prerequisite and the
other way round. Adding the prerequisite for both loaders does not give each mod its own copy: Forbric
still runs only one. If one mod plugs straight into another (Iris into Sodium, for example), use the same
loader's build of both. For Sodium, also see *A known crash* below.

### Did it work?

Open the pause menu. There is a button with **three overlapping squares**, and the tooltip says
*Mods (Forbric)*. It opens one list of every mod you installed, each row labelled with the kind it is.
Select a mod and press **Config**, or double-click the row, to open that mod's own settings.

Fabric mods hand their settings screens to Mod Menu, so a Fabric mod gets a **Config** button in this list
only when Mod Menu is installed too. With Mod Menu there are **two** mods buttons, on the title screen and in
the pause menu. Use the one with three squares: it opens settings for all three kinds, while Mod Menu's own
button opens settings only for Fabric mods.

### If something goes wrong

| What you see | What to do |
| --- | --- |
| **A window says a mod is missing something it needs** | It names the mod and what to install. Install it, or press **Launch anyway**. |
| **A window says required mod features are unavailable** | Some part of a mod could not start. You can continue playing, or quit and remove that mod. |
| **The game crashes** | Open the `.forbric-kernel` folder next to your `mods` folder. The `crash-analysis.txt` there names the mods most likely to blame; the full crash report is in `crash-reports/`. Remove those mods and try again. Using the NeoForge build of Sodium? See *A known crash* below. |
| **A mod is installed but does nothing** | Open the Forbric mods list — a mod that did not finish loading is marked there. The same list is in `load-report.txt`, in the `.forbric-kernel` folder next to your `mods` folder. Often the mod was built for a different Minecraft version, or you have two builds of it. |
| **A dedicated server will not start** and the log says the compatibility policy stopped it | A server has no screen to ask you on, so it stops instead. Remove the mod it names, or add `-Dforbric.compatibilityPolicy=continue` to the server's start command to run anyway. |
| **The client exits right after launch** and the log blames the compatibility policy (exit code 78) | The kernel found that required mod features are unavailable and the default policy is to **ask**; the asking window may not get a chance to appear before the game window is created. Add `-Dforbric.compatibilityPolicy=continue` to that version's JVM arguments (HMCL: instance settings → advanced → JVM arguments) to start anyway, with the loss recorded in `compatibility-report.json`. |
| **Continuity loads, but glass still has borders between blocks** | In **Options → Resource Packs**, enable **Default Connected Textures** (included with Continuity). Its built-in packs are optional and are not enabled just by installing the mod. On 0.3.0 the Fabric build of Continuity can still leave the borders after that. That is a Forbric bug. It is fixed in the 0.3.1 beta, a pre-release on the Releases page, but not yet in a regular release. A NeoForge build of Continuity made for your Minecraft version is the other choice. |
| **The install seems stuck** | Usually a proxy or VPN sitting between you and Mojang's servers. Run the `--doctor` check from the end of *Install*, then try again with the proxy or VPN off. |

Something else? You can report it [here](https://github.com/hi5548/Minecraft-Forbric-mod-loader/issues/new?template=bug_report.yml).

### Updating and uninstalling

**To update**, run the new installer with the same settings. Your mods folder and worlds are left alone.
The first install after updating from 0.2.0 builds Forbric's game files again, so it takes several minutes
once more.

**To uninstall**, delete `.minecraft/versions/1.21.1-forbric/`. If your launcher keeps each version
separate, that folder also holds this version's mods, worlds and settings, so first copy out anything you
want to keep. To get the disk space back as well, also delete `.minecraft/.forbric-build/` and
`.minecraft/libraries/net/forbric/`.

## What's new in 0.3.0

**More mods work.** We picked three batches of about 100 random mods from Modrinth (popular ones and
random ones, all three kinds) and started the game with each mod on its own. **80.5% loaded without errors
on 0.2.0, 89.0% on 0.3.0** (no mod failing to load in the log). On 0.3.0, 91.8% got into a world, and
79.1% also had no part reported as not working. This test checks that a mod loads and a world opens. It
does not try each mod's features, and it does not test mods together.

New:

- **Mods from different loaders can pass items, fluids and energy to each other** — for example a Fabric
  pipe or hopper can feed a NeoForge or Forge machine.
- **A window before you play when part of a mod cannot work**, so you can choose to continue or quit
  instead of finding out later.
- **The Forbric mods list marks mods that did not finish loading**, and says why.
- **After a crash, a `crash-analysis.txt`** names the mods most likely responsible.
- **The warning windows speak 10 languages**, including Chinese, and suggest what to install.
- Forbric now uses the full NeoForge release instead of a beta, so NeoForge mods that need a newer NeoForge
  can load, and the title screen no longer says "beta".

Fixed:

- Crashes when smelting in a furnace, crafting or brewing with Fabric API installed, fighting the Ender
  Dragon, using a flower pot, or placing a fluid from a Fabric or Forge mod.
- Some mod sets left the game on a black screen at startup.
- Dungeons did not generate, and a seed did not give the same terrain as vanilla Minecraft.
- Item tooltips were missing enchantments, lore, attributes and durability.
- Many Forge mods loaded but did nothing — their commands, key bindings, on-screen displays, settings files,
  mobs and world changes now work.
- Xaero's Minimap and World Map (Forge builds) crashed at startup.
- Mods that crashed or failed on 0.2.0 and work now include Farmer's Delight Refabricated, Better End,
  Better Nether, Entity Culling, More Culling, Friends & Foes, Repurposed Structures, Traveler's Backpack,
  Shoulder Surfing and YetAnotherConfigLib.

Worse than 0.2.0: in the same test, **Alex's Mobs Continued**, **Drippy Loading Screen**, **FancyMenu** and
**Easy Magic** (NeoForge builds) worked on 0.2.0 and do not on 0.3.0.

Changed for server owners: a dedicated server now stops at startup if a mod is missing a part it needs,
because there is no screen to ask you on (see *If something goes wrong* above).

## What we promise

**Your existing Minecraft is not touched.** Forbric installs alongside everything else. Your Fabric,
Forge and NeoForge setups, your worlds, and your other mod folders are exactly as they were.

**Uninstalling is deleting a folder.** Nothing is scattered around your system, and nothing is left
running when you are not playing.

**Nothing is hidden.** All the source code is here and the licence is Apache-2.0. This repository
contains no Minecraft, Forge or NeoForge code — all of that is fetched from their own servers and
assembled on your machine when you install.

And what we do **not** promise:

**We cannot promise any particular mod works.** In our own test about one mod in ten still fails on its
own, and mods that each work alone can still clash when put together.

**Part of a mod can stop working without a crash.** When a piece of a mod cannot attach to the game,
Forbric keeps the rest of the mod running instead of stopping, and usually tells you — in the window
before you play and in the Forbric mods list. If a piece attaches but then behaves wrongly, neither
Forbric nor our tests can tell.

**A known crash:** the NeoForge build of Sodium crashes at startup unless Fabric API is also installed,
and so do mods that need it, such as the NeoForge builds of Iris and Sodium Extra. This is a Forbric bug,
not a mistake in how you installed them. Until it is fixed, put Fabric API in your `mods` folder as well, or
use the Fabric builds of Sodium and of the mods that plug into it.

**This is a research project at version 0.3.0.** There is no support, no roadmap, and things will change.

Forbric is not affiliated with Mojang, FabricMC, MinecraftForge or NeoForged.

---

### For mod developers

**Your mod does not need to change.** It calls the genuine Fabric API, MinecraftForge or NeoForge classes,
so there is no compatibility layer to code against. What Forbric re-implements is the loader: class
loading, mod discovery, load order, the lifecycle, the Mixin service, and Fabric Loader's API (Forbric
carries Fabric Loader's public API types, which keep FabricMC's copyright, and implements them; Fabric
Loader itself never runs). The game is different too: the installer builds one merged game jar from both
Forge families' patches. What this means for your mod:

- **The game is one merged jar.** Where MinecraftForge and NeoForge patched the same method (about a
  thousand of them), only one version was kept: NeoForge's in all but five, MinecraftForge's in those
  five. An event whose call was lost that way — nearly always a MinecraftForge one, plus a few NeoForge
  ones such as item tooltips and screen opening — reaches your listener only if Forbric re-emits it, and
  one without a bridge never fires ([introduction.md §8](introduction.md#8-event-bridges)). Events whose
  call survived the merge fire as usual. The coremods NeoForge itself ships are not loaded; Forbric
  applies their rewrites itself.
- **Mixins are applied to that merged code.** Forbric relaxes mods' mixin configs (`required: false`,
  `defaultRequire: 0`), so an injector whose target is missing does nothing instead of failing, unless it
  sets `require` itself. It moves an injector whose target moved, and drops a whole mixin when none of its
  targets exist ([§7](introduction.md#7-mixin-on-the-merged-base)).
- **Start-up runs in Forbric's order**, close to but not the same as each loader's native order
  ([§3](introduction.md#3-boot-order)).
- **Start-up extensions are not supported:** MinecraftForge's ModLauncher services
  (`ITransformationService`, `ILaunchPluginService`) and `coremods.json`, NeoForge's
  `ClassProcessorProvider`, and custom mod or dependency locators. Today they are skipped without a
  warning.

More detail:

- [introduction.md](introduction.md) — how Forbric works inside, for developers: boot order, how the
  three kinds of mods are loaded together, what the installer builds, and how it is tested.
- [forbric-kernel/README.md](forbric-kernel/README.md) — a shorter summary of the kernel, which is what
  the installer installs.

To build the kernel from source you need `git` and a JDK 21 or newer. The kernel has its
own Gradle build; boot-side compilation does not require the Fabric substrate:

```bash
git clone https://github.com/hi5548/Minecraft-Forbric-mod-loader.git
cd Minecraft-Forbric-mod-loader
cd forbric-kernel && ./gradlew build
```

**A green build on a fresh clone does not mean the game can launch.** Without locally staged game jars,
the runtime source set and transfer tests are skipped, and tests that need those jars may also skip.
CI verifies the boot-side build and the separate loader build; it does not launch Minecraft.

To run the current source in a development game, install **JDK 25+ and Python 3.9+**, then run from the
repository root (Windows: replace `python3` with `py`):

```bash
python3 tools/dev.py client                 # automatically prepare dependencies, build and launch
python3 tools/dev.py server --accept-eula   # a separate local server instance
```

Downloads, assembled jars and instances stay under the ignored `forbric-kernel/.dev/` directory.
Gradle entry points are also available: `prepareDev`, then `runClient` or `runServer` in a separate invocation.
See [the kernel development guide](forbric-kernel/run/README.md) for configuration and test coverage.
`check` includes tool self-tests; `integrationTest` rejects skipped assertions and requires the full fixtures.
Run `./bootstrap.sh` when building the first-generation `forbric-loader/` itself; standalone merge tools
and the current kernel development workflow do not need it.

### Licence

Apache-2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE). The clean-room boundary is documented in
[forbric-loader/CREDITS.md](forbric-loader/CREDITS.md) and
[forbric-loader/MAPPINGS.md](forbric-loader/MAPPINGS.md).
