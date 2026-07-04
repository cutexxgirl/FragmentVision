# FragmentVision / Voxy NeoForge Port Plan

Дата статуса: 2026-07-04 00:47

## Цель

Портировать Voxy `0.2.16-beta` с Fabric/Minecraft `1.21.11` на NeoForge `1.21.1`, целевой NeoForge `21.1.235`.

## Решения

- За основу взята ветка Voxy для Minecraft `1.21.11`, потому что это актуальная кодовая база Voxy 2.
- Sodium для целевой версии: NeoForge `mc1.21.1-0.8.12-beta.2`.
- NeoForge: `21.1.235`.
- Minecraft: `1.21.1`.

## Сделано

- [x] Репозиторий размещён в `P:\FragmentVision`.
- [x] Подключён Git-репозиторий `cutexxgirl/FragmentVision.git`.
- [x] Gradle переведён с Fabric Loom на NeoForge ModDev.
- [x] Добавлен NeoForge metadata файл `META-INF/neoforge.mods.toml`.
- [x] Fabric access widener перенесён в NeoForge access transformer.
- [x] Fabric entrypoints/events заменены на NeoForge bootstrap/events.
- [x] Fabric Loader API обращения заменены платформенным слоем.
- [x] Основные Minecraft/Sodium API несовместимости 1.21.11 -> 1.21.1 исправлены до успешной компиляции.
- [x] Сборка `./gradlew build` проходит успешно.
- [x] Voxy jar добавлен в тестовый PrismLauncher-инстанс `P:\PrismLauncher\instances\test voxy\minecraft`.
- [x] Исправлен размер jar: `rocksdbjni` теперь урезается до Windows x64 + Linux x64, как в оригинальном Fabric Voxy.
- [x] Итоговый jar уменьшен примерно с `73 885 KiB` до `12 252 KiB`.
- [x] Исправлена упаковка trimmed `rocksdbjni`: `jarJar` metadata теперь сохраняет нормальные coordinates `org.rocksdb:rocksdbjni:10.2.1`, а не локальный file dependency с пустым group/hash version.
- [x] Исправлена упаковка LWJGL native classifiers: natives больше не попадают в `jarJar`, а кладутся обычными `windows/**` / `linux/**` ресурсами.
- [x] Убран embedded `lz4-java`, чтобы не было конфликта module layer с уже установленным `org.lz4:lz4-java:1.8.0`.
- [x] Исправлены runtime mixin/API несовместимости под Minecraft/NeoForge `1.21.1`:
  - `MixinMinecraft` -> `disconnect(Screen, boolean)`;
  - `MixinBlockableEventLoop` -> реальный `doRunTask` catch/log path 1.21.1;
  - `MixinWorld` -> constructor с `Supplier<ProfilerFiller>`;
  - `MixinClientLevel` -> constructor с `Supplier<ProfilerFiller>` и без старого `seaLevel` параметра.
- [x] Убран нефатальный Voxy `Serialization.collectAllClasses` NPE при запуске под NeoForge classloader.
- [x] Исправлен fog для Voxy LOD на Sodium `1.21.1/modern-port`: LOD viewport теперь получает текущие `RenderSystem` fog start/end/color/shape вместо `VoxyFogParameters.disabled(...)`.
- [x] Voxy post-fog приведён ближе к Sodium 1.21.1: учитывается `FogShape.CYLINDER`, а fog lerp использует `smoothstep`.
- [x] Исправлен портовый перекос в software model bake: `RenderType.cutoutMipped()` теперь считается alpha-discard слоем, а block model quads берутся через NeoForge layer-aware overload с `ModelData.EMPTY`.

## Проверено

- [x] `./gradlew build --no-daemon --console=plain` проходит успешно.
- [x] NeoForge обнаруживает Voxy в тестовом инстансе.
- [x] NeoForge обнаруживает embedded библиотеки Voxy через `jarJar`, включая `rocksdbjni-10.2.1.jar`.
- [x] В embedded `rocksdbjni` остались только:
  - `librocksdbjni-linux64.so`
  - `librocksdbjni-win64.dll`
- [x] Текущий jar в тестовом инстансе обновлён после исправления `jarJar` metadata.
- [x] Тестовый Prism-инстанс запущен правильной командой: `P:\PrismLauncher\prismlauncher.exe --launch "test voxy"`.
- [x] Minecraft дошёл до окна `Minecraft NeoForge* 1.21.1`.
- [x] В свежем `latest.log` нет Voxy `ERROR/FATAL`, нет `Mixin apply for mod voxy`, нет `Failed to collect classes`.
- [x] Исправлен crash при входе в мир: `sodium.MixinRenderRegionManager` больше не требует устаревший Sodium `Math.toIntExact(J)` target.
- [x] Существующий мир `Новый мир` запущен через Prism `--world`; клиент дошёл до одиночной игры без `ERROR/FATAL`.
- [x] На `2026-07-04 02:34` свежий jar `voxy-0.2.16-beta-057039e.jar` запущен в мире `Новый мир`; `latest.log` содержит `Creating Voxy render system` / `Voxy render system created` и не содержит Voxy `ERROR/FATAL`, `Critical injection failure`, `Game crashed`.

## История запуска

- [x] Первый запуск тестового инстанса остановился не на Voxy, а на стороннем `sodium-extra-neoforge-0.9.1+mc1.21.1.jar`.
- [x] Причина: `META-INF/neoforge.mods.toml` Sodium Extra просит `accesstransformer.cfg` в корне jar, но файл лежит как `META-INF/accesstransformer.cfg`.
- [x] Для локального теста создана patched-копия Sodium Extra jar с дублированным root `accesstransformer.cfg`.
- [x] Оригинальный Sodium Extra оставлен рядом как `.jar.disabled` backup.
- [x] Пользователь проверил: без Sodium Extra запуск всё равно падает, а без Voxy запускается.
- [x] Следующая гипотеза после свежих логов: раннее падение могло быть вызвано некорректным `jarJar` metadata для локального trimmed RocksDB (`group=""`, hash вместо `10.2.1`). Это исправлено, нужен повторный запуск.
- [x] После исправления `jarJar` metadata найден реальный blocker: `rocksdbjni reads more than one module named org.lz4.java`.
- [x] После исправления LZ4/LWJGL packaging найдены и исправлены несовпавшие mixin descriptors для 1.21.1.
- [x] На `2026-07-04 00:47` клиент стартует до main menu/window на полном тестовом Prism-инстансе.
- [x] На `2026-07-04 01:49` клиент вошёл в мир `Новый мир`; исправленный `MixinRenderRegionManager` не падает на Sodium renderer init.

## Следующее

- [x] Проверить загрузку мира в тестовом Prism-инстансе.
- [x] Проверить создание/чтение Voxy config.
- [x] Проверить включение Voxy renderer и отсутствие runtime ошибок.
- [x] Проверить отсутствие Voxy/RocksDB/LMDB/ZSTD/LZ4 `ERROR/FATAL` при реальной загрузке мира.
- [x] Визуально проверить smoke-test: грани LOD блоков больше не отрезаются, LOD видны за натуральными чанками, багов в обычной сцене нет.
- [ ] Отдельно проверить настоящий environmental fog: вода, лава, powder snow, darkness/blindness, world/boss fog.
- [ ] Если появится новый crash, чинить уже следующий реальный Voxy/NeoForge runtime blocker.
- [ ] После стабилизации runtime убрать временные исключения/заглушки там, где возможно.
- [ ] Отдельным этапом вернуть/адаптировать compat-модули: Iris, ModMenu/конфиг UI, Flashback, Nvidium, Chunky, Vivecraft.

## Handoff note 2026-07-04 00:47

- Fresh jar copied to `P:\PrismLauncher\instances\test voxy\minecraft\mods\voxy-0.2.16-beta-59b62be.jar`.
- Installed Prism jar timestamp: `2026-07-04 00:46:44`, size `11,885,863` bytes.
- Current running Java process: `javaw`, window title `Minecraft NeoForge* 1.21.1`.
- Build command used:
  - `wsl -e bash -lc 'cd /mnt/p/FragmentVision && export JAVA_HOME="$HOME/.cache/codex-jdk21" && export PATH="$JAVA_HOME/bin:$PATH" && ./gradlew clean build --no-daemon --console=plain'`
- Prism launch command used:
  - `P:\PrismLauncher\prismlauncher.exe --launch "test voxy"`
- Latest verification log:
  - `P:\PrismLauncher\instances\test voxy\minecraft\logs\latest.log`
  - last checked at `2026-07-04 00:47:11`;
  - no Voxy `ERROR/FATAL`;
  - no Voxy mixin apply failure;
  - no `Failed to collect classes`.

## Handoff note 2026-07-04 01:49

- Fixed world-entry crash:
  - `src/main/java/me/cortex/voxy/client/mixin/sodium/MixinRenderRegionManager.java`
  - `voxy$cancelFade` redirect is now optional with `require = 0`, because Sodium `0.8.12-beta.2+mc1.21.1` no longer has the old `Math.toIntExact(J)` fade target.
- Fresh jar copied to `P:\PrismLauncher\instances\test voxy\minecraft\mods\voxy-0.2.16-beta-59b62be.jar`.
- Installed Prism jar timestamp: `2026-07-04 01:48:45`, size `11,885,873` bytes.
- Prism world launch command used:
  - `P:\PrismLauncher\prismlauncher.exe --launch "test voxy" --world "Новый мир"`
- Current verification:
  - Java process: `javaw`;
  - window title: `Minecraft NeoForge* 1.21.1 - Одиночная игра`;
  - no `/ERROR]`, `/FATAL]`, `Critical injection failure`, or `Game crashed` in fresh `latest.log`.

## Handoff note 2026-07-04 00:10

- Rebuilt with `./gradlew build --no-daemon --console=plain` through WSL/JDK 21.
- Copied the fresh jar to `P:\PrismLauncher\instances\test voxy\minecraft\mods\voxy-0.2.16-beta-59b62be.jar`.
- Installed Prism jar timestamp: `2026-07-04 00:10:40`, size `12,553,230` bytes.
- Embedded `META-INF/jarjar/rocksdbjni-10.2.1.jar` length: `9,264,051` bytes.
- JarJar metadata for RocksDB is correct: `org.rocksdb:rocksdbjni:10.2.1`.
- Ignore the earlier Gradle `runClient` Sodium dependency result for Prism debugging; it was a dev-client-only false lead.
- Next real action: launch the Prism instance again and inspect fresh `latest.log`/`debug.log` from that run.

## Handoff note 2026-07-04 02:08

- Fixed Voxy client bootstrap on NeoForge:
  - `MixinRenderSystem` now injects into Minecraft 1.21.1 `RenderSystem.initRenderer(int, boolean)`;
  - `minecraft.MixinRenderSystem` is included in `client.voxy.mixins.json`;
  - the source exclude for `MixinRenderSystem` was removed from `build.gradle`.
- Fixed Sodium config registration:
  - removed duplicate `sodium:config_api_user` mod property from `META-INF/neoforge.mods.toml`;
  - kept `@ConfigEntryPointForge("voxy")` as the single config entrypoint.
- Fixed the world-render crash after Voxy renderer activation:
  - `RenderDistanceTracker` now converts level-0 Y section bounds to top-level LOD Y coordinates with `Math.floorDiv`;
  - top-level node ids now use `WorldEngine.MAX_LOD_LAYER` instead of hardcoded `4`.
- Rebuilt with:
  - `wsl -e bash -lc 'cd /mnt/p/FragmentVision && export JAVA_HOME="$HOME/.cache/codex-jdk21" && export PATH="$JAVA_HOME/bin:$PATH" && ./gradlew clean build --no-daemon --console=plain'`
- Fresh jar copied to:
  - `P:\PrismLauncher\instances\test voxy\minecraft\mods\voxy-0.2.16-beta-08c89ca.jar`
- Installed Prism jar timestamp/size:
  - `2026-07-04 02:04:04`, `11,886,695` bytes.
- Prism world launch command used:
  - `P:\PrismLauncher\prismlauncher.exe --launch "test voxy" --world "Новый мир"`
- Verification:
  - Minecraft reached `Minecraft NeoForge* 1.21.1 - Одиночная игра`;
  - fresh `latest.log` contains `Creating Voxy render system` and `Voxy render system created`;
  - no fresh crash report after this run;
  - `P:\PrismLauncher\instances\test voxy\minecraft\config\voxy-config.json` exists;
  - user confirmed: `работает`.

## Handoff note 2026-07-04 02:34

- Created/switch branch:
  - requested literal `dev 0.0.2` is not a valid Git ref name;
  - working branch is `dev-0.0.2`.
- Fixed LOD fog on Sodium 1.21.1:
  - `src/main/java/me/cortex/voxy/client/core/rendering/VoxyFogParameters.java`
  - `src/main/java/me/cortex/voxy/client/mixin/sodium/MixinDefaultChunkRenderer.java`
  - `src/main/java/me/cortex/voxy/client/core/NormalRenderPipeline.java`
  - `src/main/resources/assets/voxy/shaders/post/blit_texture_depth_cutout.frag`
- Fixed likely LOD face-loss source in model baking:
  - `src/main/java/me/cortex/voxy/client/core/model/bakery/SoftwareModelTextureBakery.java`
  - `RenderType.cutoutMipped()` now uses alpha discard;
  - model quads are queried through NeoForge layer-aware `getQuads(..., ModelData.EMPTY, layer)`.
- Rebuilt with:
  - `wsl -e bash -lc 'cd /mnt/p/FragmentVision && export JAVA_HOME="$HOME/.cache/codex-jdk21" && export PATH="$JAVA_HOME/bin:$PATH" && ./gradlew build --no-daemon --console=plain'`
- Fresh jar copied to:
  - `P:\PrismLauncher\instances\test voxy\minecraft\mods\voxy-0.2.16-beta-057039e.jar`
- Previous Prism jar disabled as:
  - `P:\PrismLauncher\instances\test voxy\minecraft\mods\voxy-0.2.16-beta-08c89ca.jar.disabled`
- Prism world launch command used:
  - `P:\PrismLauncher\prismlauncher.exe --launch "test voxy" --world "Новый мир"`
- Verification:
  - Minecraft reached `Minecraft NeoForge* 1.21.1 - Одиночная игра`;
  - fresh `latest.log` contains `Creating Voxy render system` and `Voxy render system created`;
  - no Voxy `ERROR/FATAL`, no `Critical injection failure`, no `Game crashed`;
  - `P:\PrismLauncher\instances\test voxy\minecraft\config\voxy-config.json` exists, timestamp `2026-07-04 02:33:50`;
  - user confirmed later: block faces are no longer culled and ordinary-scene LOD/fog has no visible bugs.

## Handoff note 2026-07-04 02:45

- Fixed the over-fogged LOD regression from the first fog pass:
  - `src/main/java/me/cortex/voxy/client/core/rendering/VoxyFogParameters.java`
  - Minecraft/Sodium 1.21.1 exposes only one `RenderSystem` fog range, so the ordinary render-distance terrain fog looked like environmental fog to Voxy;
  - `VoxyFogParameters.fromRenderSystem` now detects the vanilla 1.21.1 terrain-fog shape/range and returns disabled fog for that case;
  - actual environmental fog remains enabled because water/lava/powder-snow/effect/world-fog ranges do not match the vanilla terrain-fog formula.
- Rebuilt with:
  - `wsl -e bash -lc 'cd /mnt/p/FragmentVision && export JAVA_HOME="$HOME/.cache/codex-jdk21" && export PATH="$JAVA_HOME/bin:$PATH" && ./gradlew build --no-daemon --console=plain'`
- Fresh jar copied to:
  - `P:\PrismLauncher\instances\test voxy\minecraft\mods\voxy-0.2.16-beta-057039e.jar`
- Installed Prism jar timestamp/size:
  - `2026-07-04 02:42:38`, `11,887,695` bytes.
- Prism world launch command that worked:
  - `P:\PrismLauncher\prismlauncher.exe --launch "test voxy" --world "Новый мир"`
- Verification:
  - Minecraft reached `Minecraft NeoForge* 1.21.1 - Одиночная игра`;
  - fresh `latest.log` contains `Creating Voxy render system` and `Voxy render system created`;
  - no fresh crash report after this run;
  - fresh log scan found no `[ERROR]`, `[FATAL]`, `Critical injection failure`, or `Game crashed`;
  - user confirmed later: ordinary-scene LOD/fog has no visible bugs; real environmental fog still needs a separate check.

## Handoff note 2026-07-04 02:55

- Moved render-distance fog from the edge of real Sodium chunks to the edge of Voxy LOD:
  - `src/main/java/me/cortex/voxy/client/core/rendering/VoxyFogParameters.java`
  - `src/main/java/me/cortex/voxy/client/core/NormalRenderPipeline.java`
  - `src/main/resources/assets/voxy/shaders/post/blit_texture_depth_cutout.frag`
  - `src/main/java/me/cortex/voxy/client/mixin/minecraft/MixinFogRenderer.java`
  - `src/main/resources/client.voxy.mixins.json`
- `VoxyFogParameters` now carries separate environmental and render-distance ranges, matching Sodium 1.21.11's fog model more closely.
- The post blit combines environmental fog and Voxy render-distance fog with `max(...)`; render fog is cylindrical and ends at `sectionRenderDistance * 512` blocks.
- The adapted Minecraft 1.21.1 `MixinFogRenderer` disables only ordinary vanilla terrain fog on real chunks after `FogRenderer.setupFog`, leaving water/lava/powder/effect/world fog alone.
- Rebuilt and copied fresh jar:
  - `P:\PrismLauncher\instances\test voxy\minecraft\mods\voxy-0.2.16-beta-057039e.jar`
  - timestamp/size: `2026-07-04 02:52:10`, `11,889,416` bytes.
- Verification:
  - `./gradlew build --no-daemon --console=plain` succeeded;
  - Minecraft reached `Minecraft NeoForge* 1.21.1 - Одиночная игра`;
  - fresh `latest.log` contains `Creating Voxy render system` and `Voxy render system created`;
  - no fresh crash report;
  - fresh log scan found no `[ERROR]`, `[FATAL]`, `Critical injection failure`, `Mixin apply for mod voxy`, or `Game crashed`;
  - user confirmed: `всё работает`, block-face culling is gone, LOD visibility is correct, no current visible bugs.

## Handoff note 2026-07-04 04:05

- Addressed short 1px white/black streaks visible on LOD block faces:
  - screenshot looked like custom model-atlas bleeding, not face culling;
  - `src/main/resources/assets/voxy/shaders/lod/gl46/quads.frag` now clamps per-face local UVs by half a 16x16 texel before sampling the 3x2 model-face atlas;
  - this is shader-side only and does not require rebaking Voxy storage.
- Added optional Chunky NeoForge integration:
  - `src/main/java/me/cortex/voxy/commonImpl/mixin/chunky/MixinNeoForgeWorld.java`
  - `src/main/resources/chunky.voxy.mixins.json`
  - `src/main/resources/META-INF/neoforge.mods.toml`
  - NeoForge Chunky 1.4.23 uses `org.popcraft.chunky.platform.NeoForgeWorld#getChunkAtAsync`, not the Fabric classes used by upstream Voxy's Fabric mixin;
  - after Chunky finishes loading a full chunk, the mixin asks `VoxelIngestService.tryAutoIngestChunk` to ingest it.
- Improved LOD foliage parity:
  - `src/main/java/me/cortex/voxy/client/core/model/bakery/SoftwareModelTextureBakery.java`
  - `src/main/java/me/cortex/voxy/client/core/model/ModelFactory.java`
  - leaves are no longer forced onto `RenderType.solid()`;
  - Voxy now uses Minecraft's block chunk render type for leaves, so alpha/cutout behavior should be closer to real Sodium chunks.
- Rebuilt successfully with:
  - `wsl -e bash -lc 'cd /mnt/p/FragmentVision && export JAVA_HOME="$HOME/.cache/codex-jdk21" && export PATH="$JAVA_HOME/bin:$PATH" && ./gradlew build --no-daemon --console=plain'`
- Fresh jar copied to:
  - `P:\PrismLauncher\instances\test voxy\minecraft\mods\voxy-0.2.16-beta-057039e.jar`
  - timestamp/size: `2026-07-04 04:01:51`, `11,891,544` bytes.
- Verification:
  - `./gradlew build --no-daemon --console=plain` succeeded;
  - jar contains `MixinNeoForgeWorld.class`, `chunky.voxy.mixins.json`, patched `quads.frag`, `ModelFactory.class`, and `SoftwareModelTextureBakery.class`;
  - fresh log scan found no `[ERROR]`, `[FATAL]`, `Critical injection failure`, `Mixin apply for mod voxy`, `InvalidInjection`, or `Game crashed`;
  - after the jar copy, Minecraft was restarted at `2026-07-04 04:02:54`; the fresh log sees `voxy-0.2.16-beta-057039e.jar` and `Chunky 1.4.23`;
  - this verifies startup/mixin discovery only; the latest foliage, Chunky ingestion, and visual fixes still need in-world testing.
- Remaining larger visual parity backlog:
  - vanilla/Sodium chunks still have per-vertex AO, biome color blending, more exact transparency behavior, and fluid edge handling that Voxy LODs do not fully reproduce;
  - water chunk-boundary seams likely need fluid-aware LOD meshing/culling across neighboring sections plus better translucent handling, not just a shader tweak;
  - LOD-to-real-chunk replacement can be softened later with a transition band/crossfade/dither, but it must be designed around depth and translucent rendering.
