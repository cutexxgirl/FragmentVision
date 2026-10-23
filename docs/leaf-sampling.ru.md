# Передача листвы и оттенков граней из Fragment Render

При активном Render сохраняем directional face tint Vision: обычные чанки
Sodium также сохраняют baked face shade в vertex colour после снятия
vanilla lightmap. Прежнее исключение tint при fr_LightEnabled!=0 осветляло
LOD-бока травяных ступеней и крон относительно обычных чанков.

Если материал Render объявляет FR_LEAF_DETAIL_POLICY, High использует общий
выбор mip и alpha cutoff только на самом подробном LOD0 (биты3..5 interData.w).
Крупные прокси сохраняют прежний mip bias +.75 и cutoff .1: пробное применение
строгой прозрачности на всех уровнях создавало крупные отверстия в силуэте.
Обычный standalone-путь и совместимость с Render без новой политики сохранены.
Новых sampler, текстур, render passes или формата model cache нет.

Проверено build, компиляцией настоящих opaque/translucent shader sources как
с Render, так и standalone; также реальным клиентом Minecraft1.21.1 на
llvmpipe, native8 чанков/Vision512 блоков. Исходные F2 и проверка фактического
Sodium fragment program находятся в fragmentrender, ветка sky-clouds,
docs/leaf-sampling. Аппаратный FPS не измерен.
