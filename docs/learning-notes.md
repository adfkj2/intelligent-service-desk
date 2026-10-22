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

---

## 2026-10-22 · P1-3 统一响应、全局异常与集成测试

### 1. 统一响应结构（为什么不是"永远返回 HTTP 200"）

```json
{"code": 0, "message": "OK", "data": {...}}
```

- **保留 HTTP 状态码语义**（201/400/404/409/500），body 里再给业务码 `code`：
  网关限流、监控告警、前端 axios 拦截器都能按 HTTP 状态统一处理，不需要解析业务码。
- **反面做法**：全部返回 200，用 body 里的 `code` 区分成败。优点是前端只处理一种"成功"分支；
  缺点是监控看不出错误率、CDN/网关无法按状态分类、缓存语义错乱。**小项目可接受，对外 API 不建议。**
- `code` 规定：0 = 成功；4xxxx = 客户端侧错误；5xxxx = 服务端错误。

### 2. 异常 → 响应的映射表

| 异常 | HTTP | code | 触发场景 |
|---|---|---|---|
| `BusinessException` | 随错误码（404/409…） | 40400 等 | Service 主动拒绝：工单不存在、状态非法 |
| `MethodArgumentNotValidException` | 400 | 40000 | `@Valid` 字段校验失败 |
| `MethodArgumentTypeMismatchException` | 400 | 40000 | 路径参数类型错，如 `/tickets/abc` |
| `HttpMessageNotReadableException` | 400 | 40000 | 请求体不是合法 JSON |
| `NoResourceFoundException` | 404 | 40400 | 访问不存在的路径 |
| `Exception`（兜底） | 500 | 50000 | 未预期异常，只记日志，不把堆栈返回给调用方 |

**关键原则**：异常信息对外"最小够用"，完整堆栈只进服务端日志——否则等于把内部结构告诉攻击者。

### 3. 测试策略

```java
@SpringBootTest            // 起完整容器（真实 MyBatis + MySQL）
@AutoConfigureMockMvc      // 不启动 Tomcat，直接走 DispatcherServlet
@Transactional             // 每个测试结束后回滚
```

- `MockMvc` 比 curl 快，且能断言状态码、JSON 路径、错误消息；
- `@Transactional` 让测试数据自动回滚——实测：跑完 6 个用例后库里行数没有增加（自增 id 会跳动，属正常）；
- 断言要覆盖**失败路径**：400 字段校验、404 业务不存在、400 类型转换。只测成功路径等于没测。

### 4. 本轮踩的坑

1. **`mvn test` 不会重新打包 jar**：改完代码只跑测试，`target/*.jar` 仍是旧产物，启动后行为还是老版本。
   正确顺序：`mvn package`（含测试）→ 跑 jar。区分"编译通过 / 测试通过 / 交付物已更新"三件事。
2. 改了返回结构后，`/api/ping` 也要跟着改——**统一响应必须全站一致**，留一个"裸 Map"接口就等于留了个坑。

### 5. 待复习

- `@RestControllerAdvice` 为什么能捕获 Controller 抛出的异常（`HandlerExceptionResolver` 链路）
- `@ExceptionHandler` 多个匹配时的优先级（继承关系越近越优先）
- `@Transactional` 默认只回滚 `RuntimeException`，受检异常需 `rollbackFor`

---

## 2026-10-22 · 四个必须彻底搞懂的原理（附实测证据）

### 一、Mapper 接口没有实现，为什么能注入、能调用

**是什么**：Spring 容器里放的不是 `TicketMapper` 的实现类，而是一个 **JDK 动态代理对象**，它实现了 `TicketMapper` 接口。

**实测证据**（临时探针跑出来的）：
```
PROBE_mapper_class=jdk.proxy2.$Proxy82      ← JDK 动态代理
PROBE_mapper_is_jdk_proxy=true
PROBE_service_class=com.isd.ticket.service.TicketService$$SpringCGLIB$$0   ← CGLIB 代理
PROBE_service_is_cglib=true
```

**完整链路（7 步）**：
1. `@SpringBootApplication` = `@SpringBootConfiguration` + `@EnableAutoConfiguration` + `@ComponentScan`；
2. `@EnableAutoConfiguration` 读取 classpath 上各 jar 的 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，
   我们引入的 `mybatis-spring-boot-starter` 里就登记了 `MybatisAutoConfiguration`；
3. `MybatisAutoConfiguration` 上有条件注解：`@ConditionalOnClass(SqlSessionFactory.class)`、`@ConditionalOnSingleCandidate(DataSource.class)`。
   —— 这就是"为什么加了 starter 就自动生效、没配数据源就报错"的原因；
4. 它创建两个核心 Bean：`SqlSessionFactory`（读 `mybatis.*` 配置）和 `SqlSessionTemplate`；
5. 扫描 `@Mapper` 接口（`AutoConfiguredMapperScannerRegistrar`，或用 `@MapperScan` 指定包），
   把每个接口注册成 `MapperFactoryBean`；
6. `MapperFactoryBean.getObject()` 返回 `SqlSessionTemplate.getMapper(接口.class)` 生成的代理对象；
7. 调用 `ticketMapper.findById(1L)` 时，代理拦截 → `MapperMethod.execute()` → 按 `@Select` 找到 SQL →
   用 `?` 占位符绑定参数 → JDBC 执行 → 结果按 `map-underscore-to-camel-case` 映射成对象。

**为什么这么设计**：接口无法 new，框架用代理统一完成"参数→SQL、结果集→对象"，否则每个 Mapper 都要手写实现类。

---

### 二、`@Transactional` 的自调用陷阱

**是什么**：`@Transactional` 靠 **AOP 代理**实现。容器里注入的 `TicketService` 是 `TicketService$$SpringCGLIB$$0`（上面已实测），
真正的业务对象被它包在里面。只有**从代理进入**的方法调用才会被事务拦截器处理。

**为什么自调用会失效**：
```
外部调用 → 代理.create() → 开启事务 → target.create() → this.getById()   ← 直接调用目标对象自己
                                                            ↑ 不经过代理，getById 上的注解不生效
```
- `create` 的事务是生效的（它从代理进入）；
- 在 `create` 内部调用 `this.getById()` 时，`getById` 上的 `@Transactional(readOnly = true)` 或 `REQUIRES_NEW` **都不会生效**，
  它仍在 `create` 的事务里。如果你以为"它是新事务"，就会出现"该回滚的没回滚"。

**怎么修（三选一）**：
1. **拆 Bean（推荐）**：把查询方法放到 `TicketQueryService`，通过注入调用 → 走代理；
2. 注入自身代理：`@Lazy TicketService self`，用 `self.getById()`；或 `AopContext.currentProxy()`（需 `@EnableAspectJAutoProxy(exposeProxy = true)`）；
3. 用 `TransactionTemplate` 编程式控制。

**怎么验证**：在 `getById` 里打印 `TransactionSynchronizationManager.getCurrentTransactionName()`；
外部调用会看到 `...TicketService.getById`，自调用看到的仍是 `...create`。

---

### 三、`code` 与 HTTP 状态码的边界

| 层 | 谁在看 | 作用 |
|---|---|---|
| HTTP 状态码 | 网关、负载均衡、监控、CDN、浏览器、axios 拦截器 | 传输层语义：成功/未认证/无权限/不存在/冲突/服务端故障 |
| body 的 `code` | 业务前端、客服排障 | 业务语义细分：40400 工单不存在、40901 工单已被他人接单 |

**如果全部返回 200**：监控里 5xx 率永远是 0，告警不响；网关无法按状态重试；错误响应可能被缓存。
**如果只有 HTTP 状态没有 code**：前端只能匹配 message 文本，改一个字就崩。

**一个具体陷阱**：把成功数据塞进 `ApiResponse.error(...)`（data 恒为 null），
表现是"HTTP 200、code 也是 0、但 data 拿不到"——这是最难查的一类 bug，因为链路每层都没报错。

---

### 四、`Duplicate entry 'T20261022-6044' for key 'uk_ticket_no'` 的根因

**根因**：单号 = 日期 + 四位随机数（1000–9999，共 9000 个取值）。同一天的单号是"从 9000 个格子里抽签"，
按生日悖论，冲突概率约为 `1 - e^(-n²/18000)`：

| 当天工单数 | 冲突概率 |
|---|---|
| 20 | ≈ 2% |
| 50 | ≈ 13% |
| 100 | ≈ 42% |

也就是说，**一天几十单就足够撞**，与并发无关；并发只是让它更早暴露。

**修复方案与代价**：

| 方案 | 做法 | 代价 |
|---|---|---|
| 应用层重试（最小改动） | 捕获 `DuplicateKeyException`，重新生成后重试 ≤3 次 | 延迟抖动；必须确保插入失败无副作用（本例安全） |
| 换生成策略（长期正确） | 雪花算法 / Redis `INCR` / 数据库序列表 | 雪花要处理时钟回拨；Redis 引入外部依赖 |
| 用主键派生（务实） | 单号 = `T` + 日期 + 自增 id，插入后回填 | 多一次 UPDATE，但绝不冲突 |

**唯一索引必须保留**：它是最后一道防线，应用层重试和索引是"双保险"而不是"二选一"。


