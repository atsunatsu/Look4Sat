# Look4Sat-BA7OPF

[![Release](https://img.shields.io/github/v/release/atsunatsu/Look4Sat)](https://github.com/atsunatsu/Look4Sat/releases)

**BA7OPF 定制版** — 基于 [rt-bishop/Look4Sat](https://github.com/rt-bishop/Look4Sat) 的业余无线电卫星追踪器，增加了线性卫星频率计算器等功能。

## 本仓库特色功能

- **网格地图模式（Grid Mode）** — 地图页内置 VUCC 网格覆盖图：已通联网格绿色填充、漫游网格蓝色 45° 斜纹（疏朗半透明样式）、当前所在方格淡黄加粗框（field 与 sub-square 缩放均显示）；极区（南北纬 80°–90°）网格线与二位标签完整可见；点击已通联网格弹出该网格内通联明细弹窗；进入网格模式默认 VUCC 视图
- **奖状边界模式（Award Boundaries）** — 在网格地图上叠加 DXCC / WAPC / WAJA / WAZ / WAS 国界·省界·分区边界，已确认区域绿色填充；香港/澳门作为独立实体显示（DXCC 321/152），港澳标签强制显示
- **LoTW 网格同步** — 直连 ARRL Logbook（账号密码验证）下载确认报告，worked / roamed 网格自动更新（ADIF 字段顺序无关的健壮解析）；同步失败按凭据错误/限流/超时分类提示
- **双站过境匹配（Mutual Pass）** — 输入友台经纬度/网格，筛选双方同时可见的卫星过境，在地图上同时显示双方仰角曲线和地面轨迹，支持一键跳转雷达页查看详情
- **线性卫星转发器频率计算器** — 在雷达页的 Calculator 标签页中，支持 TX/RX 双向多普勒频率计算，以及下行频率偏移（offset）输入，方便操作带偏移的线性卫星
- **CW 解码器** — 集成 Morse Expert 解码引擎，支持瀑布图、实时解码文本（arm64 + armeabi-v7a 双架构）
- **Passband 模式** — 支持通过位置滑块（Passband）自动计算 TX/RX 频率，避免切换时跳变
- **AMSAT 状态页** — 实时查看卫星的业余无线电转发器状态（开启/关闭/待机），支持 72 小时历史记录回放，启动时预取缓存
- **应用内更新检查** — 设置页一键检查 GitHub 最新 Release，展示版本说明并直接下载安装
- **自定义 TLE 数据源** — 可添加自定义 TLE/Celestrak CSV 数据源，长按拖拽排序，数据源启停开关与 HTTP 状态码显示
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
