# Work Notes（IDEA 插件）

在编辑器里以**代码注释形态**浏览文本。三种内容来源，任选其一：

| 来源 | 说明 | 联网 |
|---|---|---|
| **在线书库（推荐）** | 右侧面板里搜索书名 → 一键下载全书 → 立即阅读 | 仅下载时 |
| 本地 txt | 自己把书存成 txt 放进书库目录 | 否 |
| 第三方代理接口 | 自备地址的在线源，兼容代理型接口 | 是 |

- 编译基线：IntelliJ Platform 2021.3（`since-build=213`），可装在 2021.3 ~ 2026.x（IDEA / PyCharm / GoLand / WebStorm）
- 产物：`build/WorkNotes-0.3.3.jar`（**必须是 .jar，不要用 .zip**，原因见下）
- 零第三方依赖：HTTP 用 `HttpURLConnection`，JSON 自己解析，不引入 OkHttp / Gson，避免与 IDE 自带库冲突

---

## 一、安装

1. `Settings`（`Ctrl+Alt+S`）→ `Plugins` → 右上齿轮 → **Install Plugin from Disk...**
2. 选 `build/WorkNotes-0.3.3.jar`
3. 重启 IDE

> **为什么不能发 .zip**：IDEA 2026.x 的安装入口会按扩展名分流——`.jar` 直接读 zip 条目解析描述符；
> `.zip` 走「解压到临时目录再找 `META-INF/plugin.xml`」的路径，该路径在 2026.x 上会失败，
> 界面只报「无法从文件 xxx.zip 加载插件描述符」。同样内容只要改名成 `.jar` 就能装。
> 已用平台自己的 `PluginDescriptorLoader.loadDescriptorFromArtifact` 复现并验证过这个差异。

---

## 二、面板用法：搜索 → 下载 → 阅读

按 **`Ctrl+Alt+Shift+L`**（或右侧边栏的 **Work Notes**）打开面板。

| 页签 | 能做什么 |
|---|---|
| **在线书库** | 输入书名/作者回车搜索 → 选中一本 → **下载并阅读** → 全自动下载到本地并打开 |
| **本地书架** | 列出书库目录里的 txt，双击直接用伪装形态打开 |

### 手动导入本地 txt

「本地书架」页签有四个按钮：

| 按钮 | 作用 |
|---|---|
| **导入 txt** | 弹出文件选择框（可多选、`.txt` 与 `.md` 都收），把文件**复制**进书库目录，原文件保留；同名会问覆盖/覆盖全部/跳过。只导入一本时自动打开 |
| 打开选中 | 用伪装形态打开书架里选中的书（等同双击） |
| 打开书库目录 | 在系统资源管理器里打开书库目录，方便直接拖文件进去 |
| 刷新 | 重新扫描书库目录（拖文件进去后点它） |

「章节」列会在后台统计每本书切分出多少节，读完自动回填，不会卡界面。

不想用按钮也可以：把 txt 直接复制进书库目录（默认
`C:\Users\<用户名>\AppData\Local\JetBrains\IntelliJIdea<版本>\work-notes\books`，
若在设置里填了「书库目录」则以设置为准），再点「刷新」。

### 翻页与翻章

一本书被切成若干「节」（默认按 `第X章` 之类的标题切），**插件一次只渲染一节到一个视图文件里**，所以：

| 想做什么 | 怎么做 |
|---|---|
| 下一章 | **`Ctrl+Alt+Shift+C`**，或面板底部 **「下一节 ▶」** |
| 上一章 | **`Ctrl+Alt+Shift+Z`**，或面板底部 **「◀ 上一节」** |
| 跳到指定章 | 面板底部输入框填节号（如 `120`）后回车 |
| 章内滚动 | 鼠标滚轮、`PageUp` / `PageDown`、`Ctrl+Home` / `Ctrl+End`（IDE 编辑器原生能力） |
| 看自己在哪 | 面板底部「第 N / M 节 · 标题」，视图文件头部也有 `N/M` |

> 注意：视图文件每次只有**当前这一节**的内容，所以在一节内往下滚到底不会自动接下一章——这是刻意的设计（整本 1000+ 章塞进一个文件会让 IDE 解析卡住）。到章尾就按 `C`。
>
> 快捷键没反应时，先用面板底部的按钮，再检查 `Settings > Keymap` 搜 `WorkNotes` 是否被别的插件或输入法占用了组合键。

下载行为：

- 只下载**免费章节**，付费/锁定章节自动跳过并在状态栏计数，不做任何绕过
- 3 线程抓取 + 限速，1500 章约 3~5 分钟；进度显示在 IDEA 底部的进度条
- 保存为 `<书名>.txt` 到书库目录，完成后自动切到阅读界面

### 正文为什么能读：字体混淆已被离线破解

番茄网页版把正文里的高频字替换成私有区码位（`U+E000` 起），再用一套自定义字体渲染回真字，直接抓文本会得到乱码。

这套字体是**全站统一**的（`dc027189e0ba4cd`，思源黑体 SC 的子集），所以映射表可以离线算一次、长期复用：

1. 下载混淆字体 `fq.otf`（362 个字形，CFF 轮廓，字形名是匿名 gid）
2. 用思源黑体 `SourceHanSansSC-Normal` 做基准，把 362 个字形逐个渲染成 32×32 位图
3. 与 2 万多个候选字（CJK 全表 + ASCII + 标点）做余弦相似度匹配，取最优
4. 结果内置为 `src/main/resources/fontmap.json`，运行时只做字符替换

362 个字**全部唯一匹配，相似度均在 0.90 以上**，实测还原后的正文：

> 一个老旧的钨丝灯被黑色的电线悬在屋子中央，闪烁着昏暗的光芒。

若番茄日后换字体，面板状态会显示残留私有区字符数，重新跑一遍上面的生成流程即可。

## 三、离线用法：自己准备 txt

已经有现成 txt 的人走这条，比面板少一步联网：

1. 用下载器（如 `fanqienovel-downloader`）把书下成 **txt**（设置里输出格式选 txt）
2. 把 txt 放进书库目录：
   ```
   C:\Users\<你>\AppData\Local\JetBrains\IntelliJIdea<版本>\work-notes\books
   ```
   首次按 `Ctrl+Alt+Shift+X` 时若目录为空，插件会问你要不要直接打开这个目录
3. 按 `Ctrl+Alt+Shift+X` → 选择文件 → 开始读

已实测可识别的章节标题格式（9 种）：

```
第1章 夜行        第一章 夜行        第 1 章 夜行
### 第1章 夜行    第1回 夜行         第１章 夜行（全角数字）
Chapter 1 Dawn    BOM 开头的文件     无标题→按 1800 字自动切片
```

所以下载器用「第X章」还是「第X节」、有没有 markdown `#` 前缀、有没有 BOM，都不用管。

小建议：一本书一个 txt；单文件超过 20 万字建议按卷拆开（可用作者的 `f.exe`），否则 IDE 打开时会有一段语法解析耗时。

## 四、第三方代理模式（可选）

**插件不内置任何服务地址。** 参考项目 `tomato-reader` 也没带可用地址——它的 `apiBase` 由用户在「授权设置」里自己填，且是域名级授权。所以这一步必须你自己有地址。

1. `Settings` → `Tools` → **Work Notes** → `内容来源` 选 **ONLINE**
2. `接口地址` 填你的服务，例如 `https://your-host:8080`
3. `请求头` 每行一个：
   ```
   X-Phone: 13800000000
   X-Password: your-password
   Authorization: Bearer xxxxxx
   ```
4. 点 **测试连接**（调 `/api/auth/status`，返回 `authorized=true` 即通）
5. `Tools` → **在线搜索书籍**（`Ctrl+Alt+Shift+D`）

接口约定（与参考项目 `src/renderer/src/api/` 一致）：

| 用途 | 请求 | 参数 | 取字段 |
|---|---|---|---|
| 授权状态 | `GET /api/auth/status` | — | `data.authorized`、`current_host` |
| 搜索 | `GET /api/search` | `key`、`tab_type=3`、`offset=0` | `data.search_tabs[].data[].book_data[]` 的 `book_id`/`book_name`/`author`；兜底 `data` 为数组 |
| 详情 | `GET /api/detail` | `book_id` | `data.book_info ?? data` 的 `book_name` |
| 目录 | `GET /api/book` | `book_id` | `data.chapterListWithVolume` + `volumeNameList`；兜底 `data.allItemIds` |
| 正文 | `GET /api/content` | `tab=小说`、`item_id` | `data.content ?? text ?? html`，段落数组或 HTML 均可 |

响应外层统一按 `data.data ?? data` 穿透。**如果你的服务字段名不同，只改 `api/TomatoMapper.java` 一个文件即可。**

## 五、快捷键

| 动作 | 默认键 | 说明 |
|---|---|---|
| 打开笔记 | `Ctrl+Alt+Shift+X` | 按当前来源打开 / 续读 |
| 在线搜索书籍 | `Ctrl+Alt+Shift+D` | 仅在线模式 |
| 下一节 | `Ctrl+Alt+Shift+C` | 在线源会自动预取下一节 |
| 上一节 | `Ctrl+Alt+Shift+Z` | |
| 切换显示形态 | `Ctrl+Alt+Shift+K` | COMMENT → PROPS → PLAIN |
| **收起笔记** | `Ctrl+Alt+Shift+Q` | 关掉标签，回到打开前的文件 |
| 更换笔记文件 | 无 | `Tools` 菜单 |

都能在 `Settings > Keymap` 搜 `WorkNotes` 重绑。**建议先改「收起笔记」的键。**

## 六、三种显示形态

| 形态 | 生成文件 | 说明 |
|---|---|---|
| `COMMENT` | `WorkLog.java` | 正文全在 `//` 注释里，语法合法、不报红 |
| `PROPS` | `messages_dev_zh_CN.properties` | 伪装 i18n 资源包，正文是正常前景色，最舒服 |
| `PLAIN` | `work-notes.md` | 普通 markdown |

视图文件写在 `<IDE 系统目录>/work-notes/view/`，不在项目里，不进 git。

## 七、重新编译

不需要 Gradle：

```bash
cd plugin
bash build-offline.sh
```

- 默认基线 `C:/Users/FuXin/AppData/Local/Programs/IntelliJ IDEA Ultimate 3`（IU-213.7172.25）
- 换基线：`IDEA_HOME="D:/JetBrains/IntelliJ IDEA 2022.1" bash build-offline.sh`

```
src/main/java/com/tomato/reader/
├── BookParser.java      切章节 + 折行 + 三种形态渲染（纯 Java，可独立测试）
├── ReaderSettings.java  设置与阅读位置（PersistentStateComponent）
├── ReaderService.java   会话：选源 / 取正文 / 渲染 / 隐身
├── fanqie/              番茄网页版公开接口 + 字体还原 + 整本下载
│   ├── FanqieApi.java   搜索 / 目录 / 正文解析
│   ├── FontMap.java     内置映射表还原私有区字符
│   ├── Downloader.java  3 线程抓取 + 写 txt + 进度回调
│   └── Http.java        HttpURLConnection 封装（gzip / 重试 / UA）
├── api/                 ApiClient（HTTP）+ TomatoMapper（响应映射）+ ApiException
├── source/              BookSource 接口 + LocalSource + OnlineSource
├── infra/Json.java      极简 JSON 解析器
├── action/              8 个动作
└── ui/                  LibraryWindow（右侧面板）、SearchDialog、SettingsConfigurable
```

配套脚本：

- `build-offline.sh` 离线编译打包（不联网、不需要 Gradle）
- `probe.sh` + `probe/Probe.java` 用 IDEA 自带的描述符加载器验证包能否被平台接受
- `../tools/gen-fontmap.py` 重新生成字体映射表（字体变更时用）

## 八、已知限制

- **没有状态栏迷你条**。`StatusBarWidget` 的 `ID()`/`getId()` 在新旧平台上签名变过，实现错会导致插件加载失败。进度写在文件头注释（`1/12`）。
- **只下载免费章节**：目录里标记付费/锁定的章节直接跳过，不尝试任何绕过。
- **正文解密依赖字体哈希**：映射表按当前全站字体（`dc027189e0ba4cd`）离线生成。番茄换字体后，正文会重新出现私有区乱码，需要重新生成 `fontmap.json`（流程见第二节）。
- **下载速度受站点限流影响**：3 线程 + 每章 120ms 间隔，1500 章约 3~5 分钟；若中途大量章节失败，稍后重试即可，已成功的会重新抓（不做断点续传）。
- **第三方代理模式未做真机验证**：解析层按参考项目真实结构做过 15 项断言，但服务是否可用、是否授权只有你自己的地址能验证。
- 在线正文缓存 12 节（内存 LRU）；阅读位置按「来源 + 节序号」记录，不跨机器同步。

### 关于「文件缓存冲突」弹窗

0.3.2 及更早的版本翻节时会弹：

> 已在内存中和磁盘上对 `...\work-notes\view\WorkLog.java` 进行更改

原因是旧实现先把新内容 `Files.write` 到磁盘，再用 `Document.setText` 改内存，IDEA 因此判定「磁盘被外部改动、内存有未保存内容」。0.3.3 起**只在 Document 上改内容再保存**（视图文件已打开时不再直接写磁盘），不会再弹。

升级后若旧标签仍处于冲突状态，按一次 `Ctrl+Alt+Shift+Q` 收起再 `Ctrl+Alt+Shift+X` 打开（或直接关掉 `WorkLog.java` 标签）即可，之后翻节不会再提示。

## 九、隐私与边界

- 在线书库只访问 `novel.snssdk.com`（搜索）、`fanqienovel.com`（目录与正文）两个域名，不经任何第三方中转
- 本地模式**不发起任何网络请求**
- 请求头明文存在 `<IDE 配置目录>/options/work-notes.xml`，仅本机
- 不读取项目内容，不扫描代码
- 下载与阅读仅限个人使用，不要二次分发内容
