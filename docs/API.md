# MultiWorldPlatform API

## 获取 API

依赖插件的插件 ID 是 `multiworldplatform`。推荐在服务器启动完成后获取 API：

```java
import org.multiworldplatform.MultiWorldPlatform;
import org.multiworldplatform.api.MultiWorldPlatformApi;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.Server;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.lifecycle.StartedEngineEvent;

public final class MiniGamePlugin {

    @Listener
    public void onServerStarted(final StartedEngineEvent<Server> event) {
        MultiWorldPlatform.api()
            .createWorld("arena_01", ResourceKey.minecraft("overworld"),
                MultiWorldPlatformApi.CreateOptions.defaults())
            .thenAccept(world -> {
                // world.key() 是 multiworldplatform:arena_01
            });
    }
}
```

在多插件工程中，把 MultiWorldPlatform JAR 作为 `compileOnly` 依赖，并在依赖插件的
插件元数据里声明：

```json
{
  "id": "multiworldplatform",
  "version": "12.1.0",
  "load-order": "BEFORE"
}
```

不要把 SpongeAPI 或 MultiWorldPlatform 打包进依赖插件 JAR。

## 主要方法

`MultiWorldPlatform.api()` 返回 `MultiWorldPlatformApi`：

```java
CompletableFuture<List<WorldInfo>> listWorlds();
CompletableFuture<WorldInfo> createWorld(
    String worldName, ResourceKey environment, CreateOptions options);
CompletableFuture<WorldInfo> importWorld(String worldName);
CompletableFuture<Path> exportWorld(String worldName);
CompletableFuture<WorldInfo> loadWorld(String worldName);
CompletableFuture<Boolean> unloadWorld(String worldName, String destinationWorld);
CompletableFuture<Boolean> deleteWorld(String worldName, boolean autoUnload);
CompletableFuture<Boolean> teleport(ServerPlayer player, String worldName);
CompletableFuture<Void> setGameRule(
    String worldName, String ruleName, String value);
CompletableFuture<Optional<String>> getGameRule(
    String worldName, String ruleName);
ResourceKey keyForName(String worldName);
```

所有异步方法在失败时以异常完成该 future。不要在服务器线程等待 future：

```java
MultiWorldPlatform.api().unloadWorld("arena_01", null)
    .thenCompose(ok -> MultiWorldPlatform.api().loadWorld("arena_01"))
    .thenAccept(info -> logger.info("Arena reloaded: {}", info.key()))
    .exceptionally(error -> {
        logger.error("Arena reload failed", error);
        return null;
    });
```

## 创建选项

```java
MultiWorldPlatformApi.CreateOptions options =
    MultiWorldPlatformApi.CreateOptions.builder()
        .seed(12345L)
        .kind(MultiWorldPlatformApi.WorldKind.FLAT)
        .generateStructures(false)
        .adjustSpawn(false)
        .build();
```

`WorldKind.NORMAL` 与 `generateStructures(false)` 的组合会抛出异常，原因见主 README
的结构生成边界。

## 地图恢复模式

地图恢复应把维度当作不可变包：

1. 世界内玩家数为零。
2. `world.saveAndFlush()` 或 API `exportWorld`。
3. `unloadWorld`。
4. 只在该 key 已经完全卸载后替换磁盘目录。
5. 使用 `loadWorld` 在同一个 key 上重新创建世界实例。

不要在 `WorldManager.world(key)` 已返回实例时删除或覆盖对应维度目录。

## 与普通 Sponge 世界管理器的关系

`WorldInfo.key()` 可直接交给 `Sponge.server().worldManager().world(key)`。
下载入完成后，游戏逻辑仍可使用标准 `ServerWorld` API。公开 API 只负责编排易出错
的加载、卸载、文件事务、玩家转移和配置持久化。
