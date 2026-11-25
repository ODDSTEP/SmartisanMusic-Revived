# App 阴影体系分类盘点（v24 / 1.4.23 · 87）

> 只读盘点，未改任何代码。目的：把散落在 10+ 个文件里的阴影收成一张可维护的清单，并回答"底部导航栏阴影与小播放栏阴影该分还是该合"。

## 一、先分两个大类（性质完全不同）

| 大类 | 本质 | 判断依据 |
|---|---|---|
| **投影（Drop Shadow）** | 表达"谁浮在谁上面"的层次 | 有明确的一侧深、向另一侧渐淡；贴在某个实体的边缘外侧 |
| **边缘渐隐（Scroll Fade）** | 表达"列表还能继续滚" | 上下对称出现在可滚动区域两端，不依附任何实体边缘 |

把这两类混在一起谈，是当前最容易出错的地方——`shadow_top` / `shadow_bottom` 看着像阴影，实际是**渐隐**，不该和顶栏投影用同一套规格。

---

## 二、投影：按"固定件相对内容的位置"分三类

### A 类 · 上沿固定件 → 下缘投影（顶栏）

规格：`title_bar_shadow.9`，高度 `title_bar_shadow_height = 14.0dp`（`values/smartisan_tokens.xml:106`），方向向下（顶部深→向下渐淡），画在栏体**下边界之外**、压在内容之上。

| 使用点 | 文件:行 |
|---|---|
| 通用标题栏 | `ui/components/SmartisanTitleBar.kt:121,196` |
| 通用包装件（**仅 1 处引用**） | `ui/shell/titlebar/TitleBarShadow.kt:10` |
| 设置页标题栈 | `ui/settings/SettingsTitleStack.kt:25` |
| 更多页 | `ui/more/MorePage.kt:222` |
| 大播放页 | `ui/playback/PlaybackPage.kt:275` |
| 播放列表页 | `ui/playlist/PlaylistPage.kt:237` |
| 全局搜索 | `ui/search/GlobalSearchScreen.kt:453` |
| 主壳 | `ui/shell/MusicAppShell.kt:861` |
| 风格页 | `ui/genre/GenrePage.kt:110` |
| 文件夹页 | `ui/folder/FolderPage.kt:206` |
| 导航编辑浮层 | `ui/shell/tabs/NavigationEditorOverlay.kt:203` |

**问题**：11 个地方各写一遍同样的"高 14dp + offset −14dp + 画在界外"，`TitleBarShadow` 这个现成包装件却只有 1 处在用。→ 建议统一收口到 `TitleBarShadow`（下版一起做）。

### B 类 · 卡面 → 下缘投影（资料库卡 / 设置卡片）

| 子类 | drawable | 实现 | 使用点 |
|---|---|---|---|
| 资料库顶部卡面 | `title_bar_shadow.9`（与 A 类同一张） | `LibraryTopCard`（`ui/library/SmartisanLibraryComponents.kt:298-330`）用 `drawBehind` 把阴影画在**内容实测底边之外** | 歌曲页 `SongsPage.kt:195`、专辑页 `AlbumPage.kt:135`、收藏页 `LovedSongsPage.kt:211`、文件夹页 `FolderPage.kt:810` |
| 设置分组卡片 | `list_content_item_{top,middle,single}_shadow.9` | 按 RowShape 选 top/middle/single 的 9-patch 底图 | `SettingsPages.kt:2216-2217`、`ThemeSettingsPage.kt:108-111`、`ColorThemeSettingsPage.kt:195-196` |

**已知缺陷（R30）**：`LibraryTopCard` 被套进 `ImmersiveActionRowVisibility`（带 `clipToBounds` 的动画容器）内层时，画在界外的阴影会被裁掉 → 专辑页、文件夹页当前**没有**这道投影；歌曲页、收藏页正常。已登记 R30。

### C 类 · 下沿固定件 → 上缘投影（小播放栏 / 底部导航）

这是你问"该分还是该合"的那一类。

| 项 | drawable | 方向处理 | 位置 |
|---|---|---|---|
| 小播放栏 | `now_playing_bar_shadow.9` | `scaleY = -1f` 翻转，使靠近栏体一侧深、向上渐淡 | `ui/shell/playback/PlaybackBar.kt:142-152`，高 14dp、`offset(y=-14dp)`、`zIndex(2)` 压在列表底部 |
| 底部导航栏 | `tab_bar_shadow.9` | 同样 `scaleY = -1f` 翻转 | `ui/shell/tabs/MusicBottomBar.kt:63-73`，高 14dp，受 `topChromeVisible` 控制 |

**我的结论：应该合并为同一类，但保留两个 drawable 实例。**

理由：
1. **视觉职责完全相同**——都是"把屏幕底部的固定操作区与上方滚动内容分层"，方向一致（向上投）、高度一致（14dp）、翻转手法一致。
2. **两者上下叠放**（小播放栏在底部导航之上），属于同一条"底部 chrome 带"，分类拆开会导致同一条带子被两套规格管理，未来必出不一致。
3. **drawable 不同是合理的**：小播放栏是浮在内容上的浅色半透明条，底部导航是贴屏幕底的实体条，两者底色不同，所以用了两张图。**差异在素材，不在规格**。

**但有一个必须指出的隐患**：两者都向上投 14dp 时，底部导航那层恰好落在小播放栏**背后**——小播放栏有背景色，会把下面那层阴影**完全遮住**。也就是说：小播放栏显示时，底部导航的阴影实际是白画的；只有小播放栏收起（沉浸模式）时才看得到。这不算 bug，但意味着"两道阴影"从来不会同时可见，规格上只需保证**最上面那层**正确即可。

---

## 三、边缘渐隐（不是投影）

| 资源 | 用途 | 使用点 |
|---|---|---|
| `shadow_top.png` / `shadow_bottom.png` | 可滚动列表的上下边缘淡出，提示"还有内容" | 播放队列 `ui/playback/PlaybackQueue.kt:141-142`、播放列表详情 `ui/playlist/PlaylistTrackPages.kt:95-96` |

**分类要点**：这类不该跟投影共用 `title_bar_shadow_height`，也不该叫"阴影"。建议在代码里改名/归组（如 `ScrollFadeTop/Bottom`），避免以后有人按 14dp 投影去调它。

---

## 四、装饰性投影（依附具体控件，不参与页面分层）

| 资源 | 用途 | 使用点 |
|---|---|---|
| `needle_shadow2.png` | 唱针在唱片上的投影 | `ui/playback/PlaybackTurntableStage.kt:985` |
| `ablum_crosstexture_bg_shadow.9.png` | 专辑封面十字纹理底衬 | `ui/artist/ArtistSelectedPages.kt:264` |
| `shadow_button_shrink_*`（normal/pressed/disable） | 收缩按钮的按钮态投影 | 按钮 selector |
| `letters_bar_background_shadow.9.png` | 字母栏按下时"冒白"的那块 | `ui/songs/SmartisanQuickBar.kt` —— **R29 已定一并删除** |

---

## 五、疑似废弃（0 引用，可直接清）

| 资源 | 引用数 |
|---|---|
| `smartisan_secondary_bar_shadow.9.png` | **0** |
| `popup_menu_bg_shadow.9.png` | **0** |

（`shrink_long_btn_shadow_*` 走 selector，未逐个核引用；`letters_bar_background_shadow` 在 R29 删除后也会归入此类。）

---

## 六、当前规格总表（一张表看清全部）

| 类别 | drawable | 高度 | 方向 | 画在哪 | 是否被裁剪风险 |
|---|---|---|---|---|---|
| A 顶栏 | `title_bar_shadow.9` | 14dp | 向下 | 栏体下界之外 | 有（容器 clip 时消失） |
| B 资料库卡 | `title_bar_shadow.9` | 14dp | 向下 | 卡面下界之外 | **是（R30 已复现）** |
| B 设置卡片 | `list_content_item_*_shadow.9` | 9-patch 自带 | 向下 | 卡片行底图内 | 否 |
| C 小播放栏 | `now_playing_bar_shadow.9` | 14dp | 向上（翻转） | 栏体上界之外 | 否（zIndex 2） |
| C 底部导航 | `tab_bar_shadow.9` | 14dp | 向上（翻转） | 栏体上界之外 | 否，但常被小播放栏遮住 |
| D 滚动渐隐 | `shadow_top/bottom.png` | 图自带 | 上/下 | 列表两端 | 否 |
| E 装饰 | needle / crosstexture / shrink | 各自 | 各自 | 控件内部 | 否 |

**一句话总结**：全 App 的"页面分层投影"其实只有**一个规格**——14dp、单侧渐淡、画在实体界外；变的是方向（向下/向上翻转）和素材（4 张图）。真正该统一的不是数值，而是**承载方式**（现在 11 处顶栏各写一遍）。

---

## 七、建议（供下版决策，未动手）

1. **顶栏投影收口**：全部改走 `TitleBarShadow`，11 处重复代码归一。
2. **C 类合并命名**：抽 `BottomChromeShadow`（参数：drawable + 是否翻转），小播放栏与底部导航共用，明确"只有最上层可见"。
3. **D 类改名**：`ScrollFadeEdge`，与投影分开，参数不共用 14dp。
4. **清废弃**：`smartisan_secondary_bar_shadow`、`popup_menu_bg_shadow` 删文件（shrink 会自然不带）。
5. **定一条硬规则**：任何投影都必须画在容器 clip 之外，或宿主显式 `clipToBounds=false`——R30 那类"阴影被裁"的 bug 才不会复发。
