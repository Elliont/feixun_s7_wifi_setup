# 韭菜配网 · feixun_s7_wifi_setup

把 **Wi-Fi 账号密码 + 自建服务器地址** 写进斐讯 **S7 / S7PE** 体脂秤的安卓 App。

秤出厂只会连斐讯的云，云端一停秤就变砖。这个 App 干一件事：用手机把秤"掰"到你自己的服务器上。

```
手机  ──UDP 广播 SSID/密码──▶  秤  ──连上你的 Wi-Fi──▶
        （Esptouch）
手机  ◀──UDP 7551~7553──  秤（握手，带 MAC）
手机  ──UDP 7550──▶  秤（下发服务器 IP:端口）  ──▶  配网完成 ✅
```

配套的服务端是 **[feixun_s7_web](https://github.com/Elliont/feixun_s7_web)**，它监听 `30101` 收秤的数据。

---

## 前置条件

| 项 | 要求 |
|---|---|
| 手机 | 连在 **2.4G** Wi-Fi 上（**5G 一定失败**） |
| 手机网络 | 关掉 VPN / 代理；路由器不能开「AP 隔离」「客户端隔离」 |
| 秤 | 长按机身上的 **RESET**，屏幕显示 `@` 并闪烁 = 进入配网状态 |
| 服务器 | 已经在同一局域网跑起 [feixun_s7_web](https://github.com/Elliont/feixun_s7_web)，监听 `30101` |
| 权限 | 定位权限 + 定位开关要开（读 SSID 需要）；Android 13+ 还要「附近的设备」 |

---

## 使用

1. 装 APK（[Releases](https://github.com/Elliont/feixun_s7_wifi_setup/releases) 或自己编译）
2. 打开 App，会自动填好：
   - **Wi-Fi 名称**（当前 SSID）
   - **服务器 IP**（手动填写你服务器的局域网地址，如 `192.168.1.10`）
   - **服务器端口** `30101`
3. 手动输入 **Wi-Fi 密码**
4. 在 App 里点**开始配置**，然后看「进度 / 日志」：

```
UDP 监听已启动：7551
Esptouch 开始广播：SSID=xxx
Esptouch：设备已连上 WiFi
等待秤连上 WiFi 后发来握手包…
收到秤握手包：S7 / S7P  MAC=AA:BB:CC:DD:EE:FF  来自 192.168.1.23
已把服务器地址 192.168.1.10:30101 告知秤（重发 10 次）
收到秤的确认包，配网完成 ✅
```

5. 配网成功后**上秤踩一次**，在服务器上看：

```bash
docker compose logs -f ingest
# 应该看到：login → 称重 xx.x kg → 体脂 xx.x%
```

### App 里的六项自检

进页面会自动体检，六项全绿才容易成功：

| 自检项 | 要求 |
|---|---|
| 当前 WiFi | 显示真实 SSID（不是 `<unknown ssid>`） |
| **频段** | 必须 **2.4G**，5G 直接红字拦截 |
| BSSID | 正常是 6 段 MAC |
| 定位权限 / 附近设备 | ✅ ✅ |
| 手机 IP | 与服务器同网段（如 192.168.x.x） |
| **VPN / 代理** | 必须「未开启」 |
| 系统定位开关 | 已开启 |

---

## 协议要点（自己实现时要的）

两段式，全部 UDP，整包 **XOR 0x55**，CRC16/MODBUS 大端。

### 阶段 1：秤 → 手机（`7551~7553`，32 字节）

```
[0..1]   55 AA
[2..3]   00 20                              长度
[4..11]  产品码 S7 = 00 00 00 01 00 00 00 01   （S9=…02，S7PE=…03）
[12..17] 秤 MAC
[26]     00
[27]     0x10 = 已连上 AP      0x20 = 已收到服务器地址（确认）
[30..31] CRC16/MODBUS（覆盖 [2..27]）
```

### 阶段 2：手机 → 秤（`7550`，48 字节）

```
[0..3]   55 AA 00 30
[4..11]  00 00 00 01 00 00 00 01
[12..17] 全 0
[18..23] 秤 MAC（从阶段 1 拿到）
[26..27] 40 10
[28..31] 服务器 IP（4 字节）
[32..33] 服务器端口（大端）
[46..47] CRC16（覆盖 [2..43]）
```

要重发十来次（UDP 会丢），直到收到 `[27]=0x20` 的确认包。

Wi-Fi 下发走 **Esptouch**（乐鑫的 SmartConfig 变体）：把 SSID/密码编进 UDP 组播包的**长度序列**里，路由器广播出去，未配网的秤靠嗅探解出来。

---

## 编译

需要 **JDK 17** + Android SDK（compileSdk 34）。

```bash
# 命令行
./gradlew assembleDebug          # 产物在 app/build/outputs/apk/debug/

# 或 Android Studio 直接 Run
```

Windows 上也可以双击 `gradlew.bat` 同目录跑同样命令。

`local.properties` 里写上自己的 SDK 路径（这个文件不进仓库）：

```properties
sdk.dir=/path/to/Android/Sdk
```

---

## 常见问题

**一直卡在「等待握手包」**
- 十有八九是手机在 **5G** 或开了代理/VPN
- 秤没有真正进入配网态（`@` 没闪）就点开始了
- 路由器开了 AP 隔离，手机和秤不在同一层网络

**握手收到了，但秤从不连服务器**
- 服务器 IP 填错（要填**局域网** IP，不是公网）
- 服务器没监听 `30101`，或被防火墙挡了
- 秤连上了 Wi-Fi 但 IP 变了，看路由器 DHCP 列表

**SSID 显示 `<unknown ssid>`**
- 定位权限被撤销、或系统定位开关关着
- 部分 ROM（MIUI/ColorOS）需要额外允许「读取 WLAN 信息」

**Android 13+ 拿不到 SSID**
- 已申请 `NEARBY_WIFI_DEVICES`，但仍需保留定位权限 —— 这是 Android 的历史遗留限制

---

## 第三方代码

`app/src/main/java/com/espressif/iot/esptouch/` 是 **Espressif Esptouch**（乐鑫的 SmartConfig 配网库）源码副本，版权归 Espressif Systems 所有，按其 **Apache-2.0** 许可使用。本项目只用到其中的 UDP 组播下发部分，未做改动。

---

## 致谢

协议格式与配网流程参考了以下社区项目，在此致谢：

- [Praepes/phicomm-s7-ha](https://github.com/Praepes/phicomm-s7-ha) — 斐讯体脂秤接入 Home Assistant 的协议实现
- [dckiller51/bodymiscale](https://github.com/dckiller51/bodymiscale) — 体脂秤数据处理与体成分算法参考

## 免责声明

- 与斐讯、芯海无任何关联，未获授权
- 协议格式参考公开的社区资料与实现，**仅供个人学习、自建自用**
- 刷机 / 改配网属于非官方操作，**刷坏不保修**，后果自负
- 请勿用于商业分发或提供服务

## 许可

MIT，见 [LICENSE](LICENSE)。
