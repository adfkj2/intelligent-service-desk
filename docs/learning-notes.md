# ISD 学习笔记

> 每完成一个模块追加一节。记录"我踩过的坑 + 正确做法 + 待复习项"，面试前可直接复习。

---

## 2026-10-20 · P1：如何启动项目、如何判断"访问失败"的真实原因

### 1. 把项目跑起来（两种方式，任选）

**方式 A：IDEA（推荐）**
1. `File → Open` 选项目根目录 `C:\Users\Li peng\Desktop\IntelligentServiceDesk`，等待右下角 Maven 导入完成；
2. 打开 `IsdApplication.java`，点类名左侧绿色三角 → Run；
3. 看控制台最后两行，出现这两句才算启动成功：
   ```
   Tomcat started on port 8081 (http) with context path '/'
   Started IsdApplication in 2.604 seconds
   ```

**方式 B：命令行**
```bat
set JAVA_HOME=C:\Program Files\Java\jdk-21
C:\Users\Li peng\.workbuddy\binaries\maven\apache-maven-3.9.11\bin\mvn.cmd -DskipTests package
java -jar target\isd-0.0.1-SNAPSHOT.jar
```

### 2. 访问失败的三类现象，含义完全不同

| 浏览器表现 | 真实含义 | 常见原因 |
|---|---|---|
| 无法访问此网站 / 拒绝连接 / ERR_CONNECTION_REFUSED | **该端口没有进程在监听** | 应用没启动、已停止、启动时报错退出 |
| 404 Not Found，且页面写着 `Server: Apache` | 端口对了，但**那个服务不是你的应用** | 访问了 8080（本机 Apache24 占用），而应用在 8081 |
| 502 Bad Gateway / 空白 | 中间有代理在转发 | 代理后面的上游服务没起来 |

判断顺序：先看应用控制台有没有 `Started IsdApplication`，再看端口号是不是 8081，最后才怀疑代码。

### 3. 两个必须建立的心智模型

1. **Web 应用是常驻进程**：浏览器只发请求，不提供数据。进程不在了，"拒绝连接"是唯一正确的结果。
2. **改 Java 代码必须重启**：项目没有引入 devtools 时，Spring Boot 不会热加载；刷新浏览器无效，要停掉再启动（IDEA 红色方块停止，否则下次启动报 `Port 8081 was already in use`）。

### 4. 本项目端口约定

- 应用端口：**8081**（写死在 `src/main/resources/application.yml`）
- 8080 已被本机 `C:\Apache24` 的 Apache 2.4.69 占用，不要用

### 5. 本次接口验收记录（实测）

```
GET /api/ping              → 200 {"status":"UP","service":"isd","time":"2026-10-20T16:52:59..."}
GET /api/tickets/sample    → 200 {"id":1,"ticketNo":"T20261020-0001","title":"打印机无法连接","status":"OPEN","priority":"MEDIUM"}
启动耗时 2.604 秒
```

### 6. 待复习

- 为什么 `LinkedHashMap` 能保证 JSON 字段顺序，而 `Map.of` 不能（后者是不可变哈希表，迭代顺序不保证）
- `@RequestMapping("/api")` 类级前缀 + `@GetMapping("/tickets/sample")` 方法级路径 = `/api/tickets/sample`
- `@RestController` 与 `@Controller` 的区别（有没有 `@ResponseBody`）
