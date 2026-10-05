# 🐗 Psina Engine

Мобильный Minecraft-лаунчер для Android — движок для запуска клиентов «Псины» на телефоне.
Форк [ZalithLauncher2](https://github.com/ZalithLauncher/ZalithLauncher2) (автор оригинала — Movtery и контрибьюторы), лицензия GPL-3.0.

Внутри — полноценный Minecraft: офлайн- и Microsoft-аккаунты, установка версий и Fabric, моды, изоляция игровых папок, сенсорное управление.

## Отличия от апстрима (GPLv3 §7)

- applicationId / namespace: `ru.psina.engine`; имя — **Psina Engine**; версия — `2.6.1-psina.1`
- action filemanager-сервиса: `ru.psina.engine.filemanager.EVENT_SERVICE`
- родные README апстрима перенесены в [docs/](docs/): `UPSTREAM-README.md`, `UPSTREAM-README_EN_US.md`, `UPSTREAM-README_ZH_TW.md`
- в работе: перенос ядра ПК-лаунчера [Псины](https://github.com/pyBIrtat/PsinaLauncher) и кнопка «Играть» для наших клиентов прямо из лаунчера

## Сборка

- JDK 21 + Android SDK
- `./gradlew ZalithLauncher:assembleDebug`

## Лицензия

[GPL-3.0](LICENSE). Все права и заслуги оригинального ZalithLauncher2 — его авторам; этот форк распространяется на тех же условиях.
