# idea-fanqie-moyu

在 IntelliJ IDEA 里以**代码形态**读小说：正文被渲染成 Java 注释 / i18n 资源包 / markdown，
看起来就像在翻一份日志或配置文件，而不是开着阅读器。

内置番茄小说搜索与下载，不需要第三方代理、不需要手动准备 txt。

```
// ------------------------------------------------------------------
// 第 37 章 铁门
// ------------------------------------------------------------------
//
// 夜里的风从窗缝里挤进来，他把外套裹紧了些。
// 走廊尽头的灯闪了两下，灭了。
//
```

---

## 功能

| 能力 | 说明 |
|---|---|
| 在线搜索 | 面板内输入书名/作者，直接搜番茄公开接口 |
| 一键下载 | 下载全书免费章节到本地，3 线程 + 限速，1500 章约 3~5 分钟 |
| 正文解密 | 番茄用自定义字体混淆正文文字，插件内置映射表离线还原，不需要联网解密 |
| 三种形态 | 伪 Java 注释（最像代码）/ i18n 资源包（满屏中文最自然）/ markdown |
| 自动阅读 | 一键开启后编辑器自己往下滚，读完一节自动接下一节，不用点任何按钮 |
| 章节导航 | 快捷键翻章、面板按钮翻章、输入节号直接跳转 |
| 一键收起 | 关闭阅读标签，焦点自动回到打开前的文件 |
| 本地导入 | 任意位置的 txt/md 一键导入书库 |
| 零依赖 | 只用 JDK 自带的 `HttpURLConnection`，不引入 OkHttp/Gson，避免与 IDE 自带库冲突 |

---

## 安装

1. 从 [Releases](https://github.com/LuckyTiger12138/idea-fanqie-moyu/releases) 下载最新的 `WorkNotes-x.y.z.jar`
2. `Settings`（`Ctrl+Alt+S`）→ `Plugins` → 右上齿轮 → **Install Plugin from Disk...**
3. 选中 jar（**不要解压**）→ 重启 IDE

> 必须是 `.jar`，不要用 `.zip`。IDEA 2026.x 的安装入口按扩展名分流，`.zip` 会走另一条路径并报
> 「无法从文件 xxx.zip 加载插件描述符」。同样内容改名成 `.jar` 就能装。

兼容：`since-build=213`，即 IntelliJ Platform 2021.3 及以上（IDEA / PyCharm / GoLand / WebStorm 均可）。

---

## 使用

### 1. 打开面板

`Ctrl+Alt+Shift+L`，或点右侧边栏的 **Work Notes**。

### 2. 搜书、下载

「在线书库」页签输入书名回车 → 选中一本 → **下载并阅读**。
下载完成后自动打开阅读视图。

### 3. 阅读

| 操作 | 快捷键 | 面板按钮 |
|---|---|---|
| 下一章 | `Ctrl+Alt+Shift+C` | 下一节 ▶ |
| 上一章 | `Ctrl+Alt+Shift+Z` | ◀ 上一节 |
| 跳到指定章 | — | 输入节号后回车 |
| 换形态 | `Ctrl+Alt+Shift+K` | — |
| 收起（回到原文件） | `Ctrl+Alt+Shift+Q` | — |
| 打开/继续 | `Ctrl+Alt+Shift+X` | — |
| 自动阅读 开/关 | `Ctrl+Alt+Shift+A` | 「自动」勾选框 |

### 3.1 自动阅读（不用点，自己往下走）

按 `Ctrl+Alt+Shift+A`（或勾上面板底部的「自动」）之后就不用再管了：

- 编辑器每隔一小段时间自己往下滚一行，像在读一份不断追加的日志
- 滚到这一节末尾，停留一屏的阅读时间后**自动接下一节**，从顶部继续滚
- 正在读的这一屏还没读完不会提前换节；一节读完自动往下，整本可以一直放着滚
- 面板上的速度下拉可以随时切 `0.8 / 1.2 / 1.8 / 2.5 / 4` 秒每行，也可以在设置里填自定义毫秒数
- 前台标签不是笔记文件时（比如你切去改代码）它会自动停住，绝不滚动别人的代码；切回来继续
- 再按一次 `Ctrl+Alt+Shift+A` 停止；`Ctrl+Alt+Shift+Q` 收起时也会自动停

不开自动阅读也行：**手动用滚轮滚到本节最后一行后，再往下滚一格就自动进入下一节**，
可以一路滚下去不断章（`Settings > Tools > Work Notes > 滚到底接续` 可关掉）。
贴到底之前不会换节，所以读最后一屏时不会被打断；一次滚轮只换一节，不会连翻好几章。

手动翻章时视图文件每次只包含**当前这一节**，到章尾按 `C` 继续——
整本上千章塞进一个文件会让 IDE 解析卡住。

### 4. 本地文件

「本地书架」页签：

- **导入 txt**：选任意位置的 `.txt`/`.md`，复制进书库（原文件保留）
- **打开书库目录**：直接在资源管理器里打开书库
- 双击任意一行即可阅读

书库默认位置：

```
Windows  %LOCALAPPDATA%\JetBrains\IntelliJIdea<版本>\work-notes\books
macOS    ~/Library/Caches/JetBrains/IntelliJIdea<版本>/work-notes/books
Linux    ~/.cache/JetBrains/IntelliJIdea<版本>/work-notes/books
```

可在 `Settings > Tools > Work Notes` 里改。

---

## 三个必须调的 IDE 设置

不调会露馅，也会难受：

1. `Settings > Editor > Inspections` → 搜 `Typo` → 取消勾选
   （不关的话中文满屏绿波浪线，一眼就暴露）
2. `Settings > Editor > General` → 取消 `Soft wrap`
   （插件已按宽度切好行，开软换行反而错行）
3. `Settings > Editor > Color Scheme > Language Defaults > Comments > Line comment` → 改成 `#3F3F3F`
   （默认浅灰看二十分钟眼睛就废）

---

## 正文为什么能读

番茄网页版把正文里的高频字替换成私有区码位（`U+E000` 起），再用一套自定义字体渲染回真字，
直接抓 HTML 只能得到乱码。

这套字体是**全站统一**的（思源黑体 SC 的子集，362 个字），因此可以离线算一次映射长期复用：
把字体字形渲染成位图，与 2 万个候选汉字做余弦相似度匹配，362 个字全部唯一命中，最低相似度 0.90。
结果内置为 `plugin/src/main/resources/fontmap.json`（5KB），运行时只做字符替换。

番茄换字体后正文会重新出现乱码，用 `tools/gen-fontmap.py` 重新生成即可。

---

## 从源码构建

### 方式一：离线构建脚本（推荐，不需要联网）

```bash
cd plugin
bash build-offline.sh
# 产物：plugin/build/WorkNotes-x.y.z.jar
```

脚本会从本机 IDEA 安装目录取 `platform-api.jar` 等平台 jar 作为编译基线。
可通过环境变量 `IDEA_HOME` 指定 IDEA 路径。

### 方式二：Gradle

```bash
cd plugin
gradle buildPlugin
```

### 验证构建产物

```bash
cd plugin
bash probe.sh build/WorkNotes-x.y.z.jar
# 输出：结果: 成功  id=com.tomato.worknotes  name=Work Notes  version=x.y.z
#       getActions -> 8 项 / getExtensions -> 2 项
```

---

## 目录结构

```
plugin/                    IDEA 插件工程
├── src/main/java/com/tomato/reader/
│   ├── BookParser.java    切章节、折行、三种形态渲染
│   ├── ReaderService.java 阅读会话：取正文 / 渲染 / 翻节 / 隐身
│   ├── ReaderSettings.java 设置与阅读位置
│   ├── fanqie/            搜索、目录、正文抓取与字体还原
│   ├── api/               第三方代理模式（可选）
│   ├── source/            本地源与在线源
│   ├── action/            8 个动作
│   └── ui/                书库面板、搜索框、设置页
├── build-offline.sh       离线构建
└── README.md              详细说明（安装、排错、已知限制）

tools/
├── gen-fontmap.py         从混淆字体生成映射表
└── txt2camouflage.py      把普通 txt 转成伪代码（不用插件也能用）

docs/
├── 开发规划书.md           最初的开发规划（架构、接口、排期、风险）
└── novel_sample.txt       测试用样例
```

---

## 已知限制

- **只下载免费章节**，付费/锁定章节跳过，不做任何绕过
- **不支持跨机器同步阅读进度**，位置按「文件 + 节序号」记在本地
- **依赖字体哈希**：番茄换字体后需重新生成 `fontmap.json`
- **第三方代理模式**（可选）未做真机验证，需要你自己的可用地址
- 本插件无法对抗屏幕录制、远程桌面审计、公司统一的插件审计

---

## 免责声明

本项目仅供个人学习与技术研究，请勿用于商业用途或二次分发下载内容。
所有内容版权归原作者与平台所有，请支持正版。

## License

[MIT](LICENSE)
