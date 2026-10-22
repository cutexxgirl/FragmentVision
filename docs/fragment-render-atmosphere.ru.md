# Интеграция с Fragment Render

Ветка `render-atmosphere`, исходная база `pre1-1.0.0` / `fe065f9f`.
Цель: 6–12 чанков Sodium, дальше Vision LOD, общая воздушная перспектива.

При наличии Render с `com.fragmentobnova.fragmentrender.api.AtmosphereBridge`
версии 1 финальный проход Vision получает его GLSL и текущие uniform/sky texture.
Это необязательная связь через публичные методы; оба мода могут запускаться
отдельно. Без протокола остаётся прежний environmental/render fog Vision.

API v1 (вызывается на render thread):

- `version(): int` — версия протокола;
- `shaderSource(): String` — GLSL с функциями `fr_aerialFog` и `fr_aerialLodEdge`;
- `bind(int program)` — привязать параметры к текущему GL program;
- `release()` — восстановить внешний sky texture/sampler после прохода.

Vision реконструирует camera-relative world position из собственного inverse MVP
(так что вращение камеры не вращает дымку), затем применяет атмосферу к уже
готовому цвету opaque+SSAO+translucent. Существующий environmental fog не
накладывается второй раз. Последние 20% LOD range отдельно сводятся к sky cache
Render. Код формы/геометрии LOD и формат хранилища не менялись.

В воде, лаве, слепоте/тьме, других измерениях, при отключённой атмосфере Render
снова используется обычный туман Vision. Для обновления предоставленного GLSL
после изменения ресурсов требуется пересоздать pipeline (`/voxy reload`).

Зависимости согласованы с Render: Sodium **0.8.13**, NeoForge **21.1.262**,
Minecraft **1.21.1**. Диапазон Sodium ограничен 0.8.13, следующая версия требует
повторной проверки mixin-интеграции.

Сопутствующий фикс: `SoftwareModelTextureBakery.setupTexture()` больше не оставляет
`GL_PACK_ROW_LENGTH` равным ширине block atlas. Сохраняются/восстанавливаются
pixel-pack параметры и PBO; DSA readback не требует отвязывать framebuffer.
Утечка воспроизводила native SIGSEGV на штатном F2, когда экран уже атласа.

## Upstream

На 22 октября 2026 последняя опубликованная Voxy — 0.2.20-beta (Minecraft 26.3),
наша база — 0.2.16-beta. Целиком переносить новые API Minecraft/Sodium/Iris сюда
нельзя. Upstream `67439627` / 0.2.19 разделил fog и fade на дальней границе;
этот принцип учтён в независимых aerial perspective / sky edge blend. Новые
изменения datafixer, near/far Iris uniforms и dynamic far plane не переносились.

Полный отчёт, GPU-проверка одинакового оптического столба и фотографии находятся
в Fragment Render: `docs/atmosphere/` и `tools/sky/check_aerial.py`.

## Общие материалы

Дополнительно связаны runtime материалы через необязательные методы
`materialShaderSource`, `bindMaterial`, `releaseMaterial`, `blockColorProvider`.
MDIC передаёт исходный lightmap, sky/block light и camera-relative world position
в родной материал Render; vanilla directional face tint отключается при активном
освещении Render. Medium/High выбираются uniform, воздушная перспектива остаётся
в финальном composite, однократно. ModelFactory использует BiomePalette Render
вместо прямого вызова vanilla BlockColor. Классы материалов занимают свободные
биты runtime model flags; persistent cache и размер quad не меняются.

Дальняя вода получает тот же глубокий цвет и мягкую рефлексию из существующего
sky cache. Подробный отчёт и тестовая область 6561 чанк — в Render
`docs/lod-materials/`. Геометрическое упрощение LOD сохранено.

В High листва также использует native mip bias +0.75. Cutout проверяет фильтрованную альфу готовой пробы, чтобы дальние кроны не покрывались резкими пиксельными отверстиями. У standalone Vision и остальных материалов правила прежние.

### Bushy-листва

SoftwareModelTextureBakery не включает декоративные `*_bushy*` sprite-квады
блоков LEAVES в шесть текстур LOD. Основные листовые грани и их tint сохраняются;
нативный ресурсный pack/геометрия не меняются. Фильтр применяется при запекании
модельного атласа, не в per-frame GLSL. Формат LOD-хранилища не меняется: обычного
пересоздания renderer при запуске/ресурсной перезагрузке достаточно.
