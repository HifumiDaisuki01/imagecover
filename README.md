# ImageCover —— 服务端控制的全屏图片演出（Paper 1.20.1 + Fabric 1.20.1）

服务端用命令让装有配套客户端 Mod 的玩家屏幕**全屏显示图片**，用于剧情加载、过场演出、
Boss 登场、公告海报等。支持**单图 / 预设列表(set) / 区域 / WorldGuard 区域 / 指定玩家 / 全员**，
可设置每张图的显示时长（支持小数秒），并带 0.3s 淡入淡出，播完自动消失。

## 组件

| 端 | 文件 | 说明 |
| --- | --- | --- |
| 服务端 | `ImageCover-1.0.0.jar` | Paper 1.20.1 插件（放 `plugins/`） |
| 客户端 | `imagecover-client-1201-1.0.0.jar` | 1.20.1 Fabric 客户端 Mod（放 `mods/`，需 Fabric API） |

两端通过 Fabric 插件消息通道通信：

| 通道 | 载荷 |
| --- | --- |
| `icb:play` | `writeInt(count)`，随后每条：`writeInt(url字节长度)` + `write(url UTF-8)` + `writeLong(时长毫秒)` |
| `icb:stop` | 空 payload |

客户端收到 `play` 后**按顺序播放并预加载下一张**，每张显示指定时长；收到 `stop` 立刻清空并隐藏。

## 安装

### 服务端（Paper 1.20.1）
1. 把 `ImageCover-1.0.0.jar` 放进 `plugins/`，重启服务器。
2. 首次启动会在 `plugins/ImageCover/` 自动生成示例 `set.yml`。
3. 可选软依赖（不装也能用，装了自动启用）：
   - **PlaceholderAPI**：命令参数支持 `%占位符%`（如 `%player_name%`）。
   - **Multiverse-Core**：世界参数支持 Multiverse 别名。
   - **WorldGuard**：支持 `wgplay` / `setwgplay` 按区域演出。

### 客户端
每位需要看到图片的玩家，在 **1.20.1 Fabric** 环境（Fabric Loader 0.15+、Fabric API 0.92+、Java 17+）
把 `imagecover-client-1201-1.0.0.jar` 放进 `mods/`。
**没装 Mod 的玩家收不到、也不会报错**，不影响服务端执行。

> 权限：需要 **OP** 或 `imagecover.use` 权限才能执行 `/icv`。

> **⚠️ 命令变更**：主命令已从 `/ic` 改为 **`/icv`**。原因是 CMI 等插件会抢先占用
> `/ic`，导致本插件命令被顶掉。`/imagecover` 仍可作为别名使用。

## 命令（主命令 `/icv`，别名 `/imagecover`）

```
/icv play <玩家|UUID|@a|@p|@r> <图片链接> <时长秒>
/icv play <x> <y> <z> <世界名> <半径> <图片链接> <时长秒>
/icv wgplay <WorldGuard区域名> <图片链接> <时长秒>
/icv setplay <玩家|UUID|@a|@p|@r> <set名称>
/icv setplay <x> <y> <z> <世界名> <半径> <set名称>
/icv setwgplay <WorldGuard区域名> <set名称>
/icv stop
/icv reload
```

- **时长支持小数**（如 `1.1` / `8.3`），单位秒，解析时容忍结尾的 `s`（如 `1.1s`）。
- **玩家解析顺序**：精确名（忽略大小写）→ UUID → `@a`（全员）/`@p`（执行者自己）/`@r`（随机一名）；
  另兼容 `all` 作为 `@a` 的别名。
- **世界解析**：Bukkit 世界名 → 忽略大小写 → Multiverse-Core 别名（软依赖）。
- **`@p`** 需要由玩家执行；控制台执行请用 `@a` 或指定玩家。
- 命令回显发送人数，例如 `已向 3 名玩家发送播放指令（共 2 张）`。

### 示例

```
# 指定玩家单图，显示 3.5 秒
/icv play Steve https://cdn.example.com/boss.png 3.5

# 用 UUID 指定
/icv play 069a79f4-44e9-4726-a5be-fca90e38aaf5 https://cdn.example.com/boss.png 3.5

# 全员播放，1.1 秒
/icv play @a https://cdn.example.com/announce.jpg 1.1

# 以坐标为圆心、半径 50 格内的玩家播放
/icv play 100 64 -200 world 50 https://cdn.example.com/cutscene.jpg 8.3

# 世界名用 Multiverse 别名
/icv play 0 80 0 lobby 30 https://cdn.example.com/welcome.jpg 5

# WorldGuard 区域内玩家播放
/icv wgplay arena_zone https://cdn.example.com/arena.jpg 4

# 用预设列表
/icv setplay @a testset01
/icv setplay 0 64 0 world 40 testset01
/icv setwgplay arena_zone testset01

# 停止自己屏幕上的图片（仅玩家可用，控制台执行会提示）
/icv stop

# 修改 set.yml 后热重载
/icv reload
```

> **PlaceholderAPI 示例**：`/icv play %player_name% https://.../hi.jpg 3`
> 由玩家执行时以该玩家为上下文（`%player_name%` 就是他自己）；
> 由控制台/MM 执行时取**首位在线玩家**作为上下文。

## WorldGuard 区域演出

`/icv wgplay <区域名> <图片链接> <时长秒>` 会对**当前位于该区域内的玩家**播放。特性：

- 区域名大小写不敏感（内部按 WorldGuard 规范转小写查询）。
- 同名区域存在于多个世界时，**逐玩家用其所在世界的 RegionManager 判定**，互不影响。
- 未安装 WorldGuard 时命令会提示，其他功能不受影响。

## set.yml（预设播放列表）

路径：`plugins/ImageCover/set.yml`，首次启用自动生成示例。修改后执行 `/icv reload` 生效。

支持**两种写法，可混用**：

**1) map 形式（推荐）**
```yaml
testset01:
  - url: "http://example.com/1.jpg"
    duration: 1.1
  - url: "http://example.com/2.jpg"
    duration: 8.3
  - url: "http://example.com/3.jpg"
    duration: 0.2
```

**2) 字符串简写**（按**最后一个空格**切分 url 与时长；时长结尾的 `s` 可写可不写）
```yaml
testset02:
  - "http://example.com/a.jpg 1.1s"
  - "http://example.com/b.jpg 8.3"
```

- 顶层键即 **set 名称**（查询时大小写不敏感）。
- 每张图按顺序全屏播放，全部播完自动消失。
- 单条无法解析时会写警告日志并跳过，不影响其它条目。
- 若某张图片下载/解码失败，客户端会**跳过该张继续下一张**，不会卡死。


## 淡入淡出配置

### config.yml（单张图片指令的全局开关）
`plugins/ImageCover/config.yml`：
```yaml
# /icv play 与 /icv wgplay 是否启用淡入淡出
fade: true
# 淡入 / 淡出各自的时长（秒），可精确到 0.1
fade-duration: 0.3
```
改完执行 `/icv reload`（会同时重载 config.yml 与 set.yml）或重启生效。

### set.yml（每个 set 独立开关）
`setplay` / `setwgplay` 使用 set 自己的设置；set 里**没写 fade** 时沿用 config.yml 的全局值。

写法一（直接列表，沿用全局）：
```yaml
testset01:
  - url: "http://example.com/1.jpg"
    duration: 1.1
  - "http://example.com/2.jpg 8.3s"
```

写法二（独立开关）：
```yaml
testset02:
  fade: false
  images:
    - url: "http://example.com/a.jpg"
      duration: 2.0
    - url: "http://example.com/b.jpg"
      duration: 5.0
```

## 常见问题（FAQ）

**Q：玩家屏幕没有任何图片？**
① 确认该玩家客户端装了 `imagecover` Mod 与 Fabric API（版本见上）；
② 看服务端控制台是否打印 `→ 已向 xxx 下发 N 张图片`；
③ 图片直链要能在浏览器直接打开、支持 https、格式为 jpg/png 等常见格式；
④ 区域命令的“世界名”要和服务器实际世界名一致（装了 Multiverse 也支持别名）。

**Q：图片拉伸/变形？**
客户端按“覆盖全屏”缩放：保持比例、铺满屏幕、超出部分裁掉，居中显示。想不被裁切请用与
屏幕比例接近（如 16:9）的图片。

**Q：控制台执行 `/icv stop` 报错？**
`/icv stop` 是**玩家停止自己屏幕**的命令，控制台没有“自己的屏幕”，因此会提示。
如需停某个玩家的，可在其客户端上执行，或让该玩家自行执行。

**Q：`@p` 不能用？**
`@p` 表示“执行者自己”，只能由玩家执行；控制台请用 `@a` 或指定玩家名/UUID。

**Q：`/icv wgplay` 提示未安装 WorldGuard？**
该子命令依赖 WorldGuard，装好后再试；其余命令无需 WorldGuard。

**Q：`setplay` 提示找不到预设？**
命令会列出可用预设名；注意 set 名称是 `set.yml` 的顶层键，大小写不敏感。

**Q：一次发太多张/链接太长没反应？**
单条插件消息有 32767 字节上限，超限时服务端会打印警告且该玩家发送失败（回显里会体现“失败 N”）。
减少张数或缩短链接即可。

**Q：图片闪烁/淡入淡出没效果？**
淡入淡出各 0.3s，且不会超过该张时长的一半（时长过短时自动缩短）。这是预期行为。

## 从源码构建

### 服务端插件
```bash
cd imagecover-plugin
JAVA_HOME=<JDK17+> gradle build --no-daemon
# 产物: build/libs/ImageCover-1.0.0.jar
```
依赖（compileOnly）：`io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT`、
`me.clip:placeholderapi:2.11.6`、`com.sk89q.worldguard:worldguard-bukkit:7.0.9`、
`com.sk89q.worldedit:worldedit-bukkit:7.2.15`。

### 客户端 Mod
```bash
cd imagecover-client-1201
JAVA_HOME=<JDK17+> gradle build --no-daemon
# 产物: build/libs/imagecover-client-1201-1.0.0.jar
```
Fabric Loom 1.7.4 + Mojang 官方映射（mojmap）+ Fabric API `0.92.12+1.20.1` + Loader `0.19.5`。

## 目录结构

```
imagecover-plugin/            服务端插件源码
  src/main/java/com/keran/imagecover/
    ImageCoverPlugin.java     主类：注册通道、set.yml、世界解析
    ImageCoverCommand.java    命令解析（play/setplay/wgplay/setwgplay/stop/reload）
    SetManager.java           set.yml 解析（map + 简写）
    PlayItem.java / Durations.java
    PlaceholderApiBridge.java PAPI 软依赖桥
  src/main/resources/plugin.yml, set.yml

imagecover-client-1201/       客户端 Mod 源码
  src/main/java/com/keran/imagecover/client/
    ImageCoverClient.java     入口：注册通道 icb:play / icb:stop + HUD 回调
    ImageOverlay.java         下载/解码/纹理/全屏渲染/淡入淡出/预加载/清理
  src/main/resources/fabric.mod.json
```

## 许可

MIT
