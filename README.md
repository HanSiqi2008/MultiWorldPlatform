# MultiWorldPlatform

MultiWorldPlatform 是一个仅依赖 SpongeAPI 的世界管理插件，插件 ID 为
`multiworldplatform`。它不直接引用 NeoForge 或 Forge 类，目标环境为：

- SpongeNeo 12 / Minecraft 1.21.1
- SpongeVanilla 12 / Minecraft 1.21.1

构建产物：

`build-output/multiworldplatform/MultiWorldPlatform-1.0.0.jar`

把它放入服务端的 `mods/plugins`（SpongeNeo）或 SpongeVanilla 对应的插件目录后重启。
Java 要求为 21。

## 命令

主命令是 `/mwp`，同时注册 `/cwp` 和 `/multiworldplatform`：

```text
/mwp list
/mwp create <world_name> <environment> [args]
/mwp import <world_name>
/mwp export <world_name>
/mwp load <world_name>
/mwp unload <world_name> [--tp-player=<world_name>]
/mwp destroy <world_name> [--auto]
/mwp tp <world_name> [player]
/mwp gamerule <world_name> <rule> [value]
```

`environment` 支持 `minecraft:overworld`、`minecraft:the_nether`、
`minecraft:the_end`，也接受简写 `overworld`、`the_nether`、`the_end`。

创建参数：

```text
--seed=<int>
--world-type=normal|flat|void
--no-structures
--no-adjust-spawn
```

默认世界显示名为 `world`，下界和末地显示为 `DIM-1`、`DIM1`，与世界目录
命名一致。插件创建的世界显示为 `multiworldplatform:<world_name>`。

`/mwp unload` 会先把玩家传送到目标世界，默认是 `minecraft:overworld`。
默认主世界不能被卸载或删除。删除已加载世界必须使用 `--auto`：

```text
/mwp destroy arena_01
# 已加载时拒绝

/mwp destroy arena_01 --auto
# 先踢出玩家、卸载，再删除
```

## 导入与导出

导入时按以下顺序查找 ZIP：

1. 默认世界根目录 `world/<world_name>.zip`
2. `config/multiworldplatform/import/<world_name>.zip`

ZIP 必须由本插件导出。导出位置固定为：

```text
config/multiworldplatform/export/<world_name>.zip
```

导出 ZIP 的内部结构如下，其中世界文件夹名必须与导入名一致：

```text
<world_name>/
  .multiworldplatform-world.properties
  multiworldplatform-template.json
  multiworldplatform-world.conf
  level.dat
  region/
  entities/
  ...
```

导入会校验标识、格式版本、世界名、配置、模板和 ZIP 路径。包含 `../`、绝对路径
或越界目标的项目会被拒绝。插件导出的模板会保留维度类型和区块生成器。

导出要求目标世界中没有玩家。若世界当前已加载，插件会先保存，再短暂卸载以生成
一致的磁盘快照，完成后恢复原来的加载状态。这样避免 Windows 上直接复制正在使用的
`session.lock`。

## 配置

全局配置：

```text
config/multiworldplatform/config.conf
```

每个世界一个 HOCON 文件：

```text
config/multiworldplatform/worlds/<world_name>.conf
```

示例：

```hocon
world {
    name="arena_01"
    key="multiworldplatform:arena_01"
    environment="minecraft:overworld"
    type=normal
    seed=12345
    generate-structures=true
    adjust-spawn=true
}
game-rules {
    do_daylight_cycle="false"
    random_tick_speed="3"
}
```

可以运行以下命令即时修改并持久化规则：

```text
/mwp gamerule arena_01 doDaylightCycle false
/mwp gamerule arena_01 doDaylightCycle
```

手动修改世界配置后，在下一次加载该世界时生效。插件创建的世界默认
`load-on-startup=false`，重启后需再次显式 `/mwp load`。

### 普通生成器与 `--no-structures`

超平坦和虚空世界通过 `surface/structure_overrides=[]` 安全禁用结构。

Minecraft 1.21.1 的普通 noise 生成器在区块生成时读取服务器级
`WorldOptions.generateStructures`，Sponge 12 没有公开“按单个维度关闭普通生成器结构”
的设置。因此正常生成器与 `--no-structures` 的组合会被明确拒绝，而不是创建一个
看起来有该配置、实际仍会生成结构的世界。若必须做到普通地形且完全无结构，需要额外
提供跨 SpongeNeo/SpongeVanilla 的可配置 `ChunkGenerator`，这不应通过修改服务器
全局设置来实现。

## 权限

文件权限节点：

```text
multiworldplatform.list
multiworldplatform.create
multiworldplatform.import
multiworldplatform.export
multiworldplatform.load
multiworldplatform.unload
multiworldplatform.destroy
multiworldplatform.tp
multiworldplatform.tp.other
multiworldplatform.gamerule
```

同时接受规格中的 `commonworldplatform.*` 节点，便于旧权限配置迁移。安装
LuckPerms 时使用权限节点；未安装 LuckPerms 时允许权限节点或原版 3 级 OP 对应的
`minecraft.command.op` 权限。控制台始终允许。

## API 与热重载

公开 API 和调用示例见 [docs/API.md](docs/API.md)。

维度热重载验证结论见
[docs/RELOAD-VALIDATION.md](docs/RELOAD-VALIDATION.md)。对于小游戏地图恢复，
推荐流程是：清空玩家、保存、卸载、替换维度目录、以同一 world key 加载。该流程在本次
SpongeNeo 12.0.4-RC0 实测中稳定；不支持在保持维度加载时原地替换其文件。

## 已知边界

- 地图若依赖外部数据包、模组注册表或自定义维度类型，导入机器必须安装相同依赖。
- 导出只打包该维度目录，不打包整个服务器或外部数据包。
- 默认主世界不能卸载、导入覆盖或删除。
- 所有世界变更操作串行执行，防止同一 key 同时加载、卸载、导入或删除。
- API future 不要在服务器线程上调用 `join()`/`get()` 等待，否则可能阻塞调度线程。
