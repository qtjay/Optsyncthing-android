# Syncthing-Fork - A Syncthing Wrapper for Android

[![License: MPLv2](https://img.shields.io/badge/License-MPLv2-blue.svg)](https://opensource.org/licenses/MPL-2.0)
<a href="https://github.com/researchxxl/syncthing-android/releases/latest" alt="GitHub release"><img src="https://img.shields.io/github/v/release/researchxxl/syncthing-android" /></a>
<a href="https://tooomm.github.io/github-release-stats/?username=researchxxl&repository=syncthing-android" alt="GitHub Stats"><img src="https://img.shields.io/github/downloads/researchxxl/syncthing-android/total.svg" /></a>
<a href="https://f-droid.org/packages/com.github.catfriend1.syncthingfork" alt="F-Droid release"><img src="https://img.shields.io/f-droid/v/com.github.catfriend1.syncthingfork.svg" /></a>
<a href="https://fdroid-metrics.streamlit.app/package_details?package=com.github.catfriend1.syncthingfork"><img src="https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fgithub.com%2Fkitswas%2Ffdroid-metrics-dashboard%2Fraw%2Frefs%2Fheads%2Fmain%2Fprocessed%2Fmonthly%2Fcom.github.catfriend1.syncthingfork.json&query=%24.total_downloads&style=for-the-badge&logo=fdroid&label=F-Droid%20%F0%9F%93%A5%20last%20month" height="22" /></a>
<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.github.catfriend1.syncthingfork%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fresearchxxl%2Fsyncthing-android%22%2C%22author%22%3A%22researchxxl%22%2C%22name%22%3A%22Syncthing-Fork%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22verifyLatestTag%5C%22%3Atrue%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22com.github.catfriend1.syncthingfork%5C%22%7D%22%2C%22overrideSource%22%3Anull%7D"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="22"></a>
<a href="https://hosted.weblate.org/projects/syncthing-fork/app/"><img src="https://hosted.weblate.org/widget/syncthing-fork/app/svg-badge.svg" alt="Translation status" /></a>
[![Build App](https://github.com/researchxxl/syncthing-android/actions/workflows/build-app.yaml/badge.svg)](https://github.com/researchxxl/syncthing-android/actions/workflows/build-app.yaml)

A wrapper of [Syncthing](https://github.com/syncthing/syncthing) for Android. Head to the "releases" section or F-Droid for builds. Please seek help on the forum and/or social media apps first before creating issues on the tracker.

---

## 关于本分支：选择性同步（Selective Sync）

本分支是 [researchxxl/syncthing-android](https://github.com/researchxxl/syncthing-android) 的个人 fork，
核心目标是给 Android 客户端补上**选择性同步**能力——只同步你手动勾选的文件夹或文件。
手机存储空间有限时，不必再为了用其中一个文件夹而同步整个共享文件夹。

### 功能特点

- **只同步勾选的内容**：以可展开的目录树浏览远端文件结构，**任意层级**的文件夹或单个文件都能勾选。
- **随时切换同步方式**：顶部开关可在「选择性同步」与「全部同步」之间自由切换，已做的选择会保留。
- **父目录状态联动**：勾选或取消父目录时，子目录会跟随一起变化，状态不会打架。
- **部分选中一目了然**：子目录只有一部分被勾选时，父目录显示「－」标记。
- **取消勾选带安全检查**：会先查远端是否仍持有该文件
  - 远端有副本 → 直接删除本地文件；
  - 远端无副本 → 明确提示「删除有风险」，可选**保持勾选**或**强制删除**，不会误删唯一副本。
- **新增文件自动同步**：已勾选目录里后续新增的文件会自动纳入同步（含未来的文件）。
- **目录结构本机缓存**：离线也能看到目录树，打开时自动刷新保持最新。
- **可与官方版共存**：独立包名 `com.qtjay.syncthingfork.opt`，无需卸载官方版本。
- **不改动 Go 内核**：完全在 wrapper（Java）层基于 `.stignore` 实现。

### 实现方式与验证

- 规则生成经过了 **syncthing v2.1.6 双节点端到端真值校验**：真实启动一个源节点和一个目标节点，
  比对目标节点磁盘上**实际落盘的文件集合**与预期是否一致，而不是靠模拟推断。
- 校验过程中修正了两处只有在真实内核下才会暴露的缺陷：
  1. 单条全局兜底（`*` 或 `**`）**拦不住**被 `!` 放行目录下的深层路径；
  2. 「忽略其余子项」的规则必须排在「放行该目录」**之前**，否则对子项完全失效。
- 已知限制：忽略规则对收发双向生效，未勾选的内容本机既不会下载也不会上传。

### 作者与致谢

- **发起者与真正作者**：[qtjay](https://github.com/qtjay) —— 需求定义、交互设计、
  真机验证与每一轮缺陷反馈，本分支的方向与质量由他决定。
- **代码实现**：选择性同步功能的代码由 AI 助手 **WorkBuddy** 在 qtjay 的指导下编写完成；
  所有关键行为均以真实内核校验为准，而非仅凭推断。
- **诚挚感谢原作者与上游**：本分支的一切都建立在
  [researchxxl](https://github.com/researchxxl) 及历任维护者
  [Catfriend1](https://github.com/Catfriend1)、[imsodin](https://github.com/imsodin)、
  [nutomic](https://github.com/nutomic) 的长期贡献之上，
  同样感谢 [syncthing/syncthing](https://github.com/syncthing/syncthing) 官方项目。
  没有他们的工作，就不会有这个分支。

---

<img src="app/src/main/play/listings/en-US/graphics/phone-screenshots/1.png" alt="screenshot 1" width="200" /> · <img src="app/src/main/play/listings/en-US/graphics/phone-screenshots/2.png" alt="screenshot 2" width="200" /> · <img src="app/src/main/play/listings/en-US/graphics/phone-screenshots/4.png" alt="screenshot 3" width="200" />

## Switching from the deprecated official version

Switching is easier than you may think! See our [wiki article](wiki/migration/Switching-from-the-deprecated-official-version.md) for detailed instructions.

## Wiki and Useful Articles

Our knowledge base is published [here](wiki#readme).

## Building and Development Notes

See [detailed info](wiki/developers/Building-and-Development.md).

## Acknowledgments

This project was forked from [syncthing/syncthing-android](https://github.com/syncthing/syncthing-android).

Special thanks to the former maintainers:

- [Catfriend1](https://github.com/Catfriend1)
- [imsodin](https://github.com/imsodin)
- [nutomic](https://github.com/nutomic)

## Privacy Policy

See our document on privacy: [privacy-policy.md](privacy-policy.md).

## License

The project is licensed under [MPLv2](LICENSE).

## Third-party Community Projects

They extend use cases of our app or depend on our app. You might be curious to check their readme.

- [DecSync CC](https://f-droid.org/packages/org.decsync.cc/) - "Sync contacts, calendars and tasks without a server using DecSync"
- [Flym DecSync](https://f-droid.org/packages/org.decsync.flym/) - "Sync RSS without a server using DecSync"
- [SleepSync](https://github.com/Baggio94/SleepSync) - "Pause Syncthing-Fork when your Android device sleeps and automatically resume it when the screen wakes."
