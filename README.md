# Look4Sat-BA7OPF-FT4

[![Release](https://img.shields.io/github/v/release/atsunatsu/Look4Sat)](https://github.com/atsunatsu/Look4Sat/releases)

**BA7OPF 定制版** — 基于 [rt-bishop/Look4Sat](https://github.com/rt-bishop/Look4Sat) 的业余无线电卫星追踪器，增加了线性卫星频率计算器等功能。

非官方修改版（fork），独立维护；首次发布 2026-09，持续修改中。

## 两个版本（basic / jsuenhanced）

每次发布同一 tag 下提供两个 APK，二者**包名、签名、versionCode 完全相同**，因此可以在应用内互相覆盖安装切换，应用数据（日志本、设置）保留：

| 版本 | 发布资产 | 内容 |
|---|---|---|
| **基础版 basic** | `Look4Sat-<版本>.apk` | 本 fork 的稳定功能集（网格地图、奖状边界、LoTW 网格同步、双站过境、频率计算器、CW 解码、AMSAT 状态、数据源管理、中文优化等） |
| **增强版 jsuenhanced** | `Look4Sat-<版本>-jsuenhanced.apk` | 在基础版之上增加 FT4 解码、独立日志本、CAT 电台控制、旋转器控制、L4S 手台联动等（见下方"特色功能"全部条目），应用名 `Look4Sat-JSUEnhanced` |

切换方式：设置 → 更新检查页提供「基础版 / 增强版」两个下载安装按钮，**已是最新版本时也可用**，用于在两个版本之间切换。

> 注意：增强版使用 FT4 原生解码库（`liblook4sat_ft4.so`），只能由 CI 的 Windows 构建产出；基础版不包含该库。

## 本仓库特色功能

- **网格地图模式（Grid Mode）** — 地图页内置 VUCC 网格覆盖图：已通联网格绿色填充、漫游网格蓝色 45° 斜纹（疏朗半透明样式）、当前所在方格淡黄加粗框；点击已通联网格弹出该网格内通联明细弹窗；进入网格模式默认 VUCC 视图
- **奖状边界模式（Award Boundaries）** — 在网格地图上叠加 DXCC / WAPC / WAJA / WAZ / WAS 国界·省界·分区边界，已确认区域绿色填充
- **LoTW 网格同步** — 直连 ARRL Logbook（账号密码验证）下载确认报告，worked / roamed 网格自动更新（兼容任意字段顺序的 ADIF 解析）；同步失败按凭据错误/限流/超时分类提示
- **双站过境匹配（Mutual Pass）** — 输入友台经纬度/网格，筛选双方同时可见的卫星过境，在地图上同时显示双方仰角曲线和地面轨迹，支持一键跳转雷达页查看详情
- **线性卫星转发器频率计算器** — 在雷达页的 Calculator 标签页中，支持 TX/RX 双向多普勒频率计算，以及下行频率偏移（offset）输入，方便操作带偏移的线性卫星
- **CW 解码器** — 集成 Morse Expert 解码引擎，支持瀑布图、实时解码文本（arm64 + armeabi-v7a 双架构）
- **AMSAT 状态页** — 实时查看卫星的业余无线电转发器状态（开启/关闭/待机），支持 72 小时历史记录回放，启动时预取缓存
- **应用内更新检查** — 设置页一键检查 GitHub 最新 Release，展示版本说明并直接下载安装
- **自定义 TLE 数据源** — 可添加自定义 TLE/Celestrak CSV 数据源，长按拖拽排序，数据源启停开关与 HTTP 状态码显示
- **中文界面优化** — 翻译修正、UI 布局调整，系统语言自动切换
- **FT4模式** — FT4线性卫星通联功能
- **日志本** — 独立日志本页面，支持通联记录新建/编辑、筛选与分组、ADI 导入/导出，以及 LoTW 证书配置与日志上传（支持无密码证书）；新建记录按卫星名称填充标称 RX/TX 频率，保留手动修改
- **CAT电台控制** — 提供蓝牙 SPP、USB CAT 直连、TCP 原始 CAT 与 Hamlib rigctld 连接方式；接入 Yaesu FT-817/818、FT-857/897，以及 Icom IC-705、IC-9700、IC-910/D/H、IC-820，支持频率跟踪、收发分路与状态回读
- **独立 RX/TX 基准频率** — 在收发器面板统一设置上下行基准频率，默认独立调整，支持显式计算器联动；精简频率卡片与半屏微调面板，FM 发射亚音通过可滚动单选列表配置
- **FTX-1 USB CAT** — 支持 Yaesu FTX-1 的频率、模式、亚音、DATA、PTT 控制及回读；MAIN/SUB 独立调谐，持续更新上下行频率时不反复切换接收侧，使用 CAT-1 增强 USB 端口
- **UV-K1 / UV-K5 V3 联动（L4S）** — 搭配 L4S 固件，通过 USB CDC/Android OTG 实现单台半双工、独立 RX/TX 多普勒频率控制，默认控制步进 250 Hz，可在 CAT 设置中调整；发射期间保持两路频率不变，停发后恢复跟踪
- **原生 CW 拍发与手机 PTT** — L4S([uv-k1-k5v3-firmware-custom-l4s](https://github.com/Be-Galaxy/uv-k1-k5v3-firmware-custom-l4s)) 手台支持手机按住 PTT 发射与固件本地 CW 文本拍发；CW 面板提供 F1–F8 预设，长按可编辑并保存，支持速度设置、进度显示与停止操作
- **卫星档案管理（L4S）** — 按卫星与转发器保存 56 组设置，包含模式、功率、亚音、静噪、CW 参数、RX/TX 基准及固定修正；手机支持查看、删除、JSON 导出与预览合并导入，永久保存/导入/删除由手台确认
- **旋转器控制** — 提供蓝牙 SPP、USB 串口、TCP 或 UDP 连接方式，兼容 GS-232、EasyComm I/II/III、SPID ROT2PROG、SAEBRTRACK、rotctld、PstRotator、OZ9AAR URC 及自定义模板；支持自动过境跟踪、提前指向、停放、停止与位置回传
- **手动旋转器控制** — 点选方位/仰角图修改目标，确认后发送并接管自动跟踪；黄色显示目标、红色显示新鲜位置回传，保留数值输入，支持正负方位与 450° 范围
- **小窗 / 分屏雷达** — 进入小窗或分屏后可切换大雷达/小雷达，紧凑布局保留时间栏和电台控制区域
- **控制诊断日志** — 分阶段记录电台与旋转器连接、指令发送及回传，区分传输失败、协议拒绝、超时和应用主动断开，支持导出排查
- **多地图切换** — 支持天地图/OSM地图的切换

## 上游仓库

本仓库是 [rt-bishop/Look4Sat](https://github.com/rt-bishop/Look4Sat) 的分支，上游仓库的原始功能包括

- 基于 Celestrak / SatNOGS 数据的 9000+ 活跃卫星追踪
- SGP4/SDP4 轨道预测，10 天过境预报
- 极坐标雷达图、地面轨迹图
- SSTV 图像解码
- 无广告、无跟踪、完全离线

## 许可证

GNU General Public License v3.0。详见 [LICENSE](LICENSE)。

## Star History

<a href="https://star-history.dera.page/#atsunatsu/Look4Sat&type=timeline&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://star-history.dera.page/svg?repos=atsunatsu/Look4Sat&type=timeline&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://star-history.dera.page/svg?repos=atsunatsu/Look4Sat&type=timeline&legend=top-left" />
   <img alt="Star History Chart" src="https://star-history.dera.page/svg?repos=atsunatsu/Look4Sat&type=timeline&legend=top-left" />
 </picture>
</a>
