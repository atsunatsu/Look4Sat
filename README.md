# Look4Sat-BA7OPF

[![Release](https://img.shields.io/github/v/release/atsunatsu/Look4Sat)](https://github.com/atsunatsu/Look4Sat/releases)

**BA7OPF 定制版** — 基于 [rt-bishop/Look4Sat](https://github.com/rt-bishop/Look4Sat) 的业余无线电卫星追踪器，增加了线性卫星频率计算器等功能。

## 本仓库特色功能

- **双站过境匹配（Mutual Pass）** — 输入友台经纬度/网格，筛选双方同时可见的卫星过境，在地图上同时显示双方仰角曲线和地面轨迹，支持一键跳转雷达页查看详情
- **线性卫星转发器频率计算器** — 在雷达页的 Calculator 标签页中，支持 TX/RX 双向多普勒频率计算，以及下行频率偏移（offset）输入，方便操作带偏移的线性卫星
- **CW 解码器** — 集成 Morse Expert 解码引擎，支持瀑布图、实时解码文本
- **Passband 模式** — 支持通过位置滑块（Passband）自动计算 TX/RX 频率，避免切换时跳变
- **AMSAT 状态页** — 实时查看卫星的业余无线电转发器状态（开启/关闭/待机），支持 72 小时历史记录回放
- **中文界面优化** — 翻译修正、UI 布局调整，系统语言自动切换

## 上游仓库

本仓库是 [rt-bishop/Look4Sat](https://github.com/rt-bishop/Look4Sat) 的分支，上游仓库的原始功能包括：

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
