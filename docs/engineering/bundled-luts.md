# 用户提供的内置 LUT

2026-10-11。用户明确提供以下 10 个文件作为内置 LUT，原件保留在项目根目录 `lut/`，应用只读副本位于 `app/src/main/assets/luts/`。Git 对 .cube 禁止文本换行转换，保留文件字节。临时生成的五款基础 LUT 已移除。未运行解析或渲染验证，未打包。

以下尺寸、范围及照片风格来自文件头静态读取，字节数及哈希来自资产文件读取。全部声明 `LUT_3D_SIZE 33`，未声明范围的文件按 Cube 默认 0～1；其余文件显式声明 0～1。总原始文本 9,588,439 字节，不能当作最终 APK 增量。

| 文件 | 字节 | 文件头风格 | SHA-256 |
|---|---:|---|---|
| Fresh Bright.cube | 970634 | STD | 5e63c3f1b93f232614771c0133672d078c2e7f8261f0a2c4041936ae8d5e174a |
| Kitaura.cube | 970420 | STD | 35b9f75b7321218c5f475034b1a64dabda52b48e48b7febaade371d0b3c6c6a9 |
| Smoky Color-S.cube | 994394 | STD | 624a382fa10439d59135cfcb15b9d7be9e3a83377b7f86acad8ffcfcb4433d68 |
| Teal Flat-S.cube | 915180 | STD | 9affc2596c91266fbe206bf8895151f3a27ba063de89a58dea3e97093e3d645a |
| VOL 23 TXD AgfaPhoto LeBox.cube | 970617 | 未标注 | 46669a6c553a43151a15349796f96a1b77f5678a5790513b1c1a519ccfab92cc |
| 千禧回声 cold.cube | 970384 | 未标注 | 1616e709340549ff4beef53d72bb5ed7d607e7fffc0fb97cc53d701e76a30ca1 |
| 千禧回声 warm.cube | 970384 | 未标注 | 1616e709340549ff4beef53d72bb5ed7d607e7fffc0fb97cc53d701e76a30ca1 |
| Backlight-N.cube | 950669 | NAT | cf69fe43df23a6e66cb083d40c375e1ef91d28ef3e38fc4260658daf5889562a |
| Clear-S.cube | 905128 | STD | b0e744a0963ff27224780b52fdb10b8c29bdfe311c69e2bc89b68f954154e132 |
| Color Harmony.cube | 970629 | STD | e0346b0589a738be30ebdbff3f42ca8965d3767b8581c5193aee774096c7890c |

千禧回声 cold／warm 的全部内容哈希相同，当前保留两项独立名称；两者对同一输入产生相同 LUT 效果。不能通过改名或给其中一份自动调色冒充不同作者版本。

作者注释保留：千禧回声标注 DESONG SHI，Kitaura 标注 rossandhisjpegs；部分文件保留 Resolve／Lightroom 导出工具注释。这些信息不是完整再分发许可。应用 GPLv3 仅适用于本项目代码，不为作者 LUT 重新授权；目前按用户提供文件接入原型，公开分发时需补齐相应资产许可记录。
