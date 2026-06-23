# App Publish — 实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在 omp 后端新增 `app-publish` 模块，实现「应用创建 → 关联仓库 → 预览 → 发布 → 分享」完整闭环。

**Architecture:** Spring Boot 3.3.5 + JPA + Flyway。新增 `app` / `app_version` / `port_pool` 表；`@Async` + DB 轮询驱动构建流程；Nginx 通过 `/etc/omp/nginx/apps/*.conf` 片段以 8888 端口统一暴露 `/preview/<name>/` 与 `/apps/<name>/` 路径；HMAC token + `X-Omp-User` header 实现预览作者私有鉴权。

**Tech Stack:** Spring Boot 3.3.5、Java 21、JPA/Hibernate、Flyway、MySQL 8、Spring `@Async`、`WorkspaceSigner`（复用既有 HMAC 工具）、SnakeYAML（解析 `.omp/app.yaml`）。

**Worktree:** `/Users/chenzhiwei/work/github/oh-my-pi-main/.worktrees/feature-app-publish` (branch `feature/app-publish`)
**设计文档:** `docs/plans/2026-06-23-app-publish-design.md`
**CLAUDE.md 约定：** Bun > Node（限 TS 侧）、Java 后端禁 `any`、禁动态导入、TDD 覆盖契约、`omp app` 包内避免 `console.log`（用 `logger`）。

---

## Task 1: Flyway 迁移 + 实体 + Repository 骨架

**Files:**
- Create: `backend/src/main/resources/db/migration/V<next>__app_publish.sql`（查询当前最大版本号 + 1，详见步骤）
- Create: `backend/src/main/java/com/yourorg/omp/app/entity/App.java`
- Create: `backend/src/main/java/com/yourorg/omp/app/entity/AppVersion.java`
- Create: `backend/src/main/java/com/yourorg/omp/app/entity/PortPool.java`
- Create: `backend/src/main/java/com/yourorg/omp/app/entity/AppEnv.java`（枚举）
- Create: `backend/src/main/java/com/yourorg/omp/app/entity/AppStatus.java`（枚举）
- Create: `backend/src/main/java/com/yourorg/omp/app/entity/AppVersionStatus.java`（枚举）
- Create: `backend/src/main/java/com/yourorg/omp/app/repo/AppRepository.java`
- Create: `backend/src/main/java/com/yourorg/omp/app/repo/AppVersionRepository.java`
- Create: `backend/src/main/java/com/yourorg/omp/app/repo/PortPoolRepository.java`
- Test: `backend/src/test/java/com/yourorg/omp/app/repo/AppRepositoryTest.java`

### Step 1.1: 确认下一个 Flyway 版本号

```bash
cd /Users/chenzhiwei/work/github/oh-my-pi-main/.worktrees/feature-app-publish
ls backend/src/main/resources/db/migration/ | sort | tail -3
```

取最大 V 号 + 1（如 `V22__foo.sql` 则下一个为 `V23__app_publish.sql`）。后续步骤中 `<NEXT>` 替换为该数字。

### Step 1.2: 写 Flyway 迁移

Create `backend/src/main/resources/db/migration/V<NEXT>__app_publish.sql`，内容与设计文档 §4.1 一致（`app` / `app_version` / `port_pool` 三张表 + 端口池初始化）。

### Step 1.3: 写枚举

`AppEnv.java`：
```java
package com.yourorg.omp.app.entity;
public enum AppEnv { PREVIEW, PROD }
```

`AppStatus.java`：`CREATED, BUILDING_PREVIEW, PREVIEW_READY, BUILDING_PROD, PUBLISHED, FAILED`

`AppVersionStatus.java`：`BUILDING, READY, FAILED, ARCHIVED`

### Step 1.4: 写实体

`App.java`：与设计文档 §4.1 字段一一对应。`@Entity @Table(name="app")`，主键自增；`currentVersion` 用 `@OneToOne`（`optional=true`，`@JoinColumn(name="current_version_id", insertable=false, updatable=false)`）。

`AppVersion.java`：与 §4.1 字段对应。`@ManyToOne App app`，`@Enumerated(EnumType.STRING) AppEnv env`。

`PortPool.java`：`@Id Integer port`，`status` 字符串（用 `@Enumerated(EnumType.STRING)` 存 `PortStatus` 枚举 `FREE/HELD`）。

### Step 1.5: 写 Repository

`AppRepository extends JpaRepository<App, Long>`：
- `Optional<App> findByNameAndDeletedAtIsNull(String name)`
- `List<App> findAllByOwnerIdAndDeletedAtIsNull(Long ownerId)`
- `@Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select a from App a where a.id=:id") Optional<App> findByIdForUpdate(Long id)`

`AppVersionRepository extends JpaRepository<AppVersion, Long>`：
- `List<AppVersion> findAllByAppIdOrderByCreatedAtDesc(Long appId)`
- `Optional<AppVersion> findByAppIdAndEnvAndStatus(Long appId, AppEnv env, AppVersionStatus status)`

`PortPoolRepository extends JpaRepository<PortPool, Integer>`：
- `@Lock(PESSIMISTIC_WRITE) @Query("select p from PortPool p where p.port=:port") Optional<PortPool> findByIdForUpdate(Integer port)`
- `@Query("select p from PortPool p where p.status='FREE' order by p.port") List<PortPool> findAllFree(Pageable)` 或 `List<PortPool> findFirst100ByStatusOrderByPortAsc(PortStatus status)`

### Step 1.6: 写 Repository 失败测试（先红）

`AppRepositoryTest.java`：
```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
class AppRepositoryTest {
  @Autowired AppRepository repo;

  @Test
  void findByName_excludesSoftDeleted() {
    App a = new App();
    a.setName("foo"); a.setOwnerId(1L); a.setRepoUrl("git@x:y.git");
    a.setHasBackend(false); a.setStatus(AppStatus.CREATED);
    repo.save(a);
    assertThat(repo.findByNameAndDeletedAtIsNull("foo")).isPresent();

    a.setDeletedAt(Instant.now());
    repo.save(a);
    assertThat(repo.findByNameAndDeletedAtIsNull("foo")).isEmpty();
  }
}
```

`application-test.yml` 用 H2 或 Testcontainers MySQL（沿用项目已有选择 — 跑一次现有测试看）。先用 H2 in-memory 跑通。

### Step 1.7: 跑测试，期望失败（缺实体）

```bash
cd backend && mvn -q -Dtest=AppRepositoryTest test 2>&1 | tail -20
```

期望：编译失败（找不到 App 类）或测试失败。

### Step 1.8: 实现实体与 Repository（让测试绿）

按 §4.1、§1.4、§1.5 实现。

### Step 1.9: 跑测试通过

```bash
cd backend && mvn -q -Dtest=AppRepositoryTest test 2>&1 | tail -10
```

期望：`Tests run: 1, Failures: 0`。

### Step 1.10: 提交

```bash
git add backend/src/main/resources/db/migration/V<NEXT>__app_publish.sql \
        backend/src/main/java/com/yourorg/omp/app/ \
        backend/src/test/java/com/yourorg/omp/app/
git commit -m "feat(app-publish): add Flyway migration and entity/repository skeleton"
```

---

## Task 2: PortAllocator（含并发测试）

**Files:**
- Create: `backend/src/main/java/com/yourorg/omp/app/PortAllocator.java`
- Test: `backend/src/test/java/com/yourorg/omp/app/PortAllocatorTest.java`

### Step 2.1: 写 PortAllocator 接口

```java
public interface PortAllocator {
  /** 抢占一个 FREE 端口，标记为 HELD(env, appId)。返回分配的 port；池满抛 NoFreePortException。 */
  int acquire(Long appId, AppEnv env);
  /** 释放指定 (port, appId) 的占用。 */
  void release(int port, Long appId);
}
```

`NoFreePortException extends RuntimeException`（落到 `backend/src/main/java/com/yourorg/omp/app/error/`）。

### Step 2.2: 写 JPA 实现 + 并发测试

```java
@Component
@RequiredArgsConstructor
public class JpaPortAllocator implements PortAllocator {
  private final PortPoolRepository repo;
  private static final Logger log = LoggerFactory.getLogger(JpaPortAllocator.class);

  @Transactional
  public int acquire(Long appId, AppEnv env) {
    // 悲观锁串行化：select ... for update skip locked 第一行 FREE
    List<PortPool> free = repo.findAllFreeForUpdate(); // 见 Repository 扩展
    if (free.isEmpty()) throw new NoFreePortException();
    PortPool p = free.get(0);
    p.setStatus(PortStatus.HELD);
    p.setAppId(appId);
    p.setEnv(env);
    repo.save(p);
    return p.getPort();
  }

  @Transactional
  public void release(int port, Long appId) {
    PortPool p = repo.findByIdForUpdate(port).orElseThrow();
    if (p.getAppId() != null && !p.getAppId().equals(appId)) {
      log.warn("port {} owned by {}, release from {} ignored", port, p.getAppId(), appId);
      return;
    }
    p.setStatus(PortStatus.FREE);
    p.setAppId(null);
    p.setEnv(null);
    repo.save(p);
  }
}
```

`PortPoolRepository` 增加：
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")}) // SKIP LOCKED
@Query("select p from PortPool p where p.status='FREE' order by p.port")
List<PortPool> findAllFreeForUpdate();
```

> MySQL 8 支持 `SELECT ... FOR UPDATE SKIP LOCKED`，Hibernate 5.6+ 通过 `-2` timeout 触发 SKIP LOCKED。若测试用 H2 不支持 SKIP LOCKED，本测试改用单线程执行 + 验证串行正确性，并发场景注释掉并标 `@Disabled` 留待集成测试。

### Step 2.3: 并发测试（先红）

```java
@SpringBootTest
class PortAllocatorTest {
  @Autowired PortAllocator allocator;
  @Autowired PortPoolRepository repo;

  @Test
  void acquire_returnsUniquePortsUnderConcurrency() throws Exception {
    int threads = 20;
    ExecutorService es = Executors.newFixedThreadPool(threads);
    CountDownLatch latch = new CountDownLatch(1);
    Set<Integer> ports = ConcurrentHashMap.newKeySet();
    List<Future<Integer>> futures = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      long appId = (long) i;
      futures.add(es.submit(() -> { latch.await(); return allocator.acquire(appId, AppEnv.PREVIEW); }));
    }
    latch.countDown();
    for (Future<Integer> f : futures) ports.add(f.get(5, TimeUnit.SECONDS));
    es.shutdown();
    assertThat(ports).hasSize(threads);
  }
}
```

### Step 2.4: 跑测试（先红再绿）

```bash
cd backend && mvn -q -Dtest=PortAllocatorTest test 2>&1 | tail -30
```

调试直到通过。如 SKIP LOCKED 在 H2 上失败：临时把方法改 `@Lock(PESSIMISTIC_WRITE)` 普通行锁（牺牲并发性换可测性），并在 Repository 方法上加 `@Profile("!test")` 的 SKIP LOCKED 版本。

### Step 2.5: 提交

```bash
git add backend/src/main/java/com/yourorg/omp/app/PortAllocator.java \
        backend/src/main/java/com/yourorg/omp/app/error/ \
        backend/src/main/java/com/yourorg/omp/app/repo/PortPoolRepository.java \
        backend/src/test/java/com/yourorg/omp/app/PortAllocatorTest.java
git commit -m "feat(app-publish): add PortAllocator with concurrent acquire"
```

---

## Task 3: AppService + 状态机迁移（不含构建）

**Files:**
- Create: `backend/src/main/java/com/yourorg/omp/app/AppService.java`
- Create: `backend/src/main/java/com/yourorg/omp/app/error/InvalidStateException.java`
- Create: `backend/src/main/java/com/yourorg/omp/app/error/NotOwnerException.java`
- Create: `backend/src/main/java/com/yourorg/omp/app/dto/CreateAppRequest.java`
- Create: `backend/src/main/java/com/yourorg/omp/app/dto/AppResponse.java`
- Create: `backend/src/main/java/com/yourorg/omp/app/dto/TriggerBuildResponse.java`
- Test: `backend/src/test/java/com/yourorg/omp/app/AppServiceTest.java`

### Step 3.1: 写 DTO

`CreateAppRequest`：`name`（`@Pattern(regexp="^[a-z0-9-]{3,32}$")`）、`repoUrl`、`hasBackend`。

`AppResponse`：`id, name, ownerId, repoUrl, hasBackend, status, currentVersionId, createdAt`。

`TriggerBuildResponse`：`versionId`。

### Step 3.2: 写异常

`InvalidStateException extends RuntimeException`，带 `appId, currentStatus, attemptedAction` 字段。

`NotOwnerException extends RuntimeException`，带 `appId, userId`。

### Step 3.3: 写 AppService 状态机表（先接口后实现）

```java
public interface AppService {
  AppResponse create(Long ownerId, CreateAppRequest req);
  AppResponse get(Long userId, String name);
  List<AppResponse> list(Long ownerId);

  /** 状态机：CREATED|PREVIEW_READY|FAILED → BUILDING_PREVIEW。返回新版本 id。 */
  TriggerBuildResponse triggerPreview(Long userId, String name);

  /** 状态机：PREVIEW_READY → BUILDING_PROD。 */
  TriggerBuildResponse triggerPublish(Long userId, String name);

  void softDelete(Long userId, String name);
}
```

`AppServiceImpl` 关键逻辑（伪代码，便于写实现）：
```
create(ownerId, req):
  if repo.findByNameAndDeletedAtIsNull(req.name).isPresent() throw InvalidNameException
  App a = new App(... status=CREATED ...)
  return repo.save(a)

triggerPreview(userId, name):
  App a = lockAndGet(name)
  if a.ownerId != userId throw NotOwnerException
  if a.status ∉ {CREATED, PREVIEW_READY, FAILED} throw InvalidStateException(...)
  // 抢占端口（先做，但 version 是 BUILDING 时才占用；调整：acquire 移到 BuildExecutor 内部，
  //   这里只创建 version(BUILDING, env=PREVIEW, backendPort=null) + 提交异步任务
  a.status = BUILDING_PREVIEW
  AppVersion v = newVersion(a, PREVIEW, BUILDING)
  // @Async 暂时用一个占位 BuildExecutor stub 跑（Task 4 替换）
  // version.id return
```

### Step 3.4: 写状态机迁移表测试（先红）

```java
@ExtendWith(MockitoExtension.class)
class AppServiceTest {
  @Mock AppRepository appRepo;
  @Mock AppVersionRepository versionRepo;
  @Mock PortAllocator portAllocator;
  @InjectMocks AppServiceImpl svc;

  @Test
  void triggerPreview_fromCreated_setsBuildingPreview() {
    App a = newApp("demo", 1L, AppStatus.CREATED);
    when(appRepo.findByNameAndDeletedAtIsNull("demo")).thenReturn(Optional.of(a));
    when(appRepo.findByIdForUpdate(a.getId())).thenReturn(Optional.of(a));
    when(versionRepo.save(any())).thenAnswer(inv -> { AppVersion v = inv.getArgument(0); v.setId(99L); return v; });

    var resp = svc.triggerPreview(1L, "demo");

    assertThat(resp.getVersionId()).isEqualTo(99L);
    assertThat(a.getStatus()).isEqualTo(AppStatus.BUILDING_PREVIEW);
  }

  @Test
  void triggerPreview_fromPublished_throws() {
    App a = newApp("demo", 1L, AppStatus.PUBLISHED);
    when(appRepo.findByNameAndDeletedAtIsNull("demo")).thenReturn(Optional.of(a));
    when(appRepo.findByIdForUpdate(a.getId())).thenReturn(Optional.of(a));
    assertThatThrownBy(() -> svc.triggerPreview(1L, "demo"))
      .isInstanceOf(InvalidStateException.class);
  }

  @Test
  void triggerPreview_byNonOwner_throws() {
    App a = newApp("demo", 1L, AppStatus.CREATED);
    when(appRepo.findByNameAndDeletedAtIsNull("demo")).thenReturn(Optional.of(a));
    when(appRepo.findByIdForUpdate(a.getId())).thenReturn(Optional.of(a));
    assertThatThrownBy(() -> svc.triggerPreview(2L, "demo"))
      .isInstanceOf(NotOwnerException.class);
  }

  @Test
  void triggerPublish_fromPreviewReady_setsBuildingProd() {
    App a = newApp("demo", 1L, AppStatus.PREVIEW_READY);
    when(appRepo.findByNameAndDeletedAtIsNull("demo")).thenReturn(Optional.of(a));
    when(appRepo.findByIdForUpdate(a.getId())).thenReturn(Optional.of(a));
    when(versionRepo.save(any())).thenAnswer(inv -> { AppVersion v = inv.getArgument(0); v.setId(100L); return v; });

    var resp = svc.triggerPublish(1L, "demo");
    assertThat(resp.getVersionId()).isEqualTo(100L);
    assertThat(a.getStatus()).isEqualTo(AppStatus.BUILDING_PROD);
  }

  @Test
  void triggerPublish_fromCreated_throws() {
    App a = newApp("demo", 1L, AppStatus.CREATED);
    when(appRepo.findByNameAndDeletedAtIsNull("demo")).thenReturn(Optional.of(a));
    when(appRepo.findByIdForUpdate(a.getId())).thenReturn(Optional.of(a));
    assertThatThrownBy(() -> svc.triggerPublish(1L, "demo"))
      .isInstanceOf(InvalidStateException.class);
  }
}
```

### Step 3.5: 跑测试 → 实现 → 绿

按 TDD 循环：跑失败 → 写最小实现 → 跑通过。`@Async` 部分暂用一个 stub bean 让测试可控。

### Step 3.6: 提交

```bash
git add backend/src/main/java/com/yourorg/omp/app/AppService.java \
        backend/src/main/java/com/yourorg/omp/app/dto/ \
        backend/src/main/java/com/yourorg/omp/app/error/ \
        backend/src/test/java/com/yourorg/omp/app/AppServiceTest.java
git commit -m "feat(app-publish): add AppService with state machine"
```

---

## Task 4: AppController + DTO + 全局异常处理

**Files:**
- Create: `backend/src/main/java/com/yourorg/omp/app/AppController.java`
- Create: `backend/src/main/java/com/yourorg/omp/app/error/GlobalExceptionHandler.java`（在 `app` 包内的 `@RestControllerAdvice`，避免污染全局 — 或放到 `com.yourorg.omp.rest` 复用既有 — 选其一）
- Test: `backend/src/test/java/com/yourorg/omp/app/AppControllerTest.java`（用 `@WebMvcTest` + `@MockBean AppService`）

### Step 4.1: 写控制器契约测试（先红）

```java
@WebMvcTest(AppController.class)
@Import(AppControllerTest.ExceptionAdvisors.class)
class AppControllerTest {
  @Autowired MockMvc mvc;
  @MockBean AppService svc;
  @Autowired ObjectMapper om;

  @Test
  void create_returns201() throws Exception {
    when(svc.create(eq(1L), any())).thenReturn(new AppResponse(...));
    mvc.perform(post("/api/apps").with(auth(1L)).contentType(APPLICATION_JSON)
            .content("{\"name\":\"demo\",\"repoUrl\":\"git@x:y.git\",\"hasBackend\":false}"))
       .andExpect(status().isCreated());
  }

  @Test
  void list_returnsOwnerOnlyApps() throws Exception {
    when(svc.list(1L)).thenReturn(List.of(...));
    mvc.perform(get("/api/apps").with(auth(1L))).andExpect(status().isOk());
  }

  @Test
  void preview_returns202() throws Exception {
    when(svc.triggerPreview(1L, "demo")).thenReturn(new TriggerBuildResponse(42L));
    mvc.perform(post("/api/apps/demo/preview").with(auth(1L))).andExpect(status().isAccepted());
  }

  @Test
  void preview_returns409_whenInvalidState() throws Exception {
    when(svc.triggerPreview(1L, "demo")).thenThrow(new InvalidStateException(...));
    mvc.perform(post("/api/apps/demo/preview").with(auth(1L)))
       .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
  }

  // auth(...) 助手构造 SecurityContext，userId=1L
  static RequestPostProcessor auth(Long uid) { ... }
  @ControllerAdvice static class ExceptionAdvisors { ... } // 引入本模块的 advice
}
```

### Step 4.2: 实现控制器 + advice

`AppController`：所有接口按设计文档 §7 实现，路径严格一致。所有读接口要求登录（沿用现有 `SecurityConfig` 模式，从 `SecurityContextHolder` 取 userId）。

`ExceptionAdvisors`（测试内）：把 `InvalidStateException` → 409 + `code=INVALID_STATE`，`NotOwnerException` → 403 + `code=NOT_OWNER`，`NoFreePortException` → 503 + `code=NO_FREE_PORT`。

### Step 4.3: 跑测试 → 实现 → 绿

### Step 4.4: 提交

```bash
git add backend/src/main/java/com/yourorg/omp/app/AppController.java \
        backend/src/test/java/com/yourorg/omp/app/AppControllerTest.java
git commit -m "feat(app-publish): add AppController with error mapping"
```

---

## Task 5: BuildExecutor + .omp/app.yaml 解析

**Files:**
- Create: `backend/src/main/java/com/yourorg/omp/app/build/AppYaml.java`（POJO）
- Create: `backend/src/main/java/com/yourorg/omp/app/build/AppYamlParser.java`
- Create: `backend/src/main/java/com/yourorg/omp/app/build/BuildExecutor.java`
- Create: `backend/src/main/java/com/yourorg/omp/app/build/BuildResult.java`
- Create: `backend/src/main/java/com/yourorg/omp/app/config/AsyncConfig.java`（`@EnableAsync` + `TaskExecutor` bean, `corePoolSize=3`）
- Test: `backend/src/test/java/com/yourorg/omp/app/build/AppYamlParserTest.java`

### Step 5.1: 加依赖

`backend/pom.xml` 已有 SnakeYAML（Spring Boot 自带）。如缺则加：
```xml
<dependency><groupId>org.yaml</groupId><artifactId>snakeyaml</artifactId></dependency>
```

### Step 5.2: 写 AppYaml POJO

字段对应设计文档 §5。`Frontend` 内部类（framework, buildCmd, outputDir），`Backend` 内部类（runtime, buildCmd, startCmd, healthPath, startTimeoutSec，默认 60）。

### Step 5.3: 写 Parser + 测试

```java
public class AppYamlParser {
  public AppYaml parse(Path file) throws IOException {
    try (Reader r = Files.newBufferedReader(file)) {
      AppYaml yaml = new Yaml(new Constructor(AppYaml.class)).load(r);
      if (yaml == null || yaml.getFrontend() == null || isBlank(yaml.getFrontend().getBuildCmd()))
        throw new InvalidAppYamlException("frontend.build_cmd is required");
      return yaml;
    }
  }
}
```

测试覆盖：合法 yaml、有 backend、缺字段抛异常、空文件抛异常。

### Step 5.4: 写 BuildExecutor（接口 + 默认实现）

```java
public interface BuildExecutor {
  BuildResult build(App app, AppVersion version);
}

@Component
@RequiredArgsConstructor
public class DefaultBuildExecutor implements BuildExecutor {
  private final AppYamlParser parser;
  private final PortAllocator portAllocator;
  private final NginxManager nginxManager; // Task 6
  private final AppRepository appRepo;
  private final AppVersionRepository versionRepo;

  @Async("appBuildExecutor")
  public BuildResult build(App app, AppVersion version) {
    Path workDir = Paths.get("/opt/omp/data/apps", app.getName(), version.getEnv().name().toLowerCase(),
        "v" + version.getId());
    Path srcDir = workDir.resolve("src");
    Path artifactRoot = Paths.get("/opt/omp/data/apps", app.getName(), version.getEnv().name().toLowerCase());

    try {
      // 1. git clone（拉取指定 commit） — 调用 git CLI
      runShell(workDir, "git", "clone", app.getRepoUrl(), "src");
      runShell(srcDir, "git", "checkout", version.getCommitSha());

      // 2. 读 yaml
      AppYaml yaml = parser.parse(srcDir.resolve(".omp/app.yaml"));

      // 3. 前端构建
      runShell(srcDir, "bash", "-lc", yaml.getFrontend().getBuildCmd());
      Path frontendOut = srcDir.resolve(yaml.getFrontend().getOutputDir());
      copyDir(frontendOut, artifactRoot.resolve("frontend"));

      // 4. 后端（如有）
      Integer backendPort = null;
      if (yaml.getBackend() != null) {
        backendPort = portAllocator.acquire(app.getId(), version.getEnv());
        runShell(srcDir, "bash", "-lc", yaml.getBackend().getBuildCmd());
        // 启动后端（detached）— 写 pid 文件
        Path pidFile = artifactRoot.resolve("backend.pid");
        startBackend(srcDir, yaml.getBackend(), backendPort, pidFile);
        // 轮询 health
        waitHealthy("http://127.0.0.1:" + backendPort + yaml.getBackend().getHealthPath(),
                    yaml.getBackend().getStartTimeoutSec());
      }

      // 5. 标记 version READY
      version.setStatus(AppVersionStatus.READY);
      version.setBackendPort(backendPort);
      version.setFinishedAt(Instant.now());
      version.setArtifactPath(artifactRoot.toString());
      versionRepo.save(version);

      // 6. 推进 app 状态 + Nginx render（Task 6）
      advanceAppState(app, version);
      nginxManager.renderAndReload(app, version);

      return BuildResult.ok(backendPort);
    } catch (Exception e) {
      version.setStatus(AppVersionStatus.FAILED);
      version.setError(truncate(e.toString(), 4000));
      version.setFinishedAt(Instant.now());
      versionRepo.save(version);
      // app 状态回退：BUILDING_PREVIEW→FAILED, BUILDING_PROD→PREVIEW_READY
      rollbackAppState(app);
      return BuildResult.fail(e);
    }
  }
}
```

### Step 5.5: 写 BuildExecutor 单元测试（不实际跑 git）

注入 `ProcessRunner` 接口（自己定义），mock 它在测试里返回固定 stdout/exitCode。覆盖：
- git 失败 → version FAILED
- yaml 缺失 → version FAILED
- 前端构建失败 → version FAILED
- 后端健康检查超时 → version FAILED + 释放端口

### Step 5.6: 写 AsyncConfig

```java
@Configuration
@EnableAsync
public class AsyncConfig {
  @Bean("appBuildExecutor")
  public TaskExecutor appBuildExecutor() {
    ThreadPoolTaskExecutor t = new ThreadPoolTaskExecutor();
    t.setCorePoolSize(3);
    t.setMaxPoolSize(3);
    t.setQueueCapacity(16);
    t.setThreadNamePrefix("app-build-");
    t.initialize();
    return t;
  }
}
```

### Step 5.7: 跑测试 → 实现 → 绿

### Step 5.8: 提交

```bash
git add backend/src/main/java/com/yourorg/omp/app/build/ \
        backend/src/main/java/com/yourorg/omp/app/config/ \
        backend/src/test/java/com/yourorg/omp/app/build/ \
        backend/pom.xml
git commit -m "feat(app-publish): add BuildExecutor and .omp/app.yaml parser"
```

---

## Task 6: NginxManager + 模板渲染

**Files:**
- Create: `backend/src/main/java/com/yourorg/omp/app/nginx/NginxManager.java`
- Create: `backend/src/main/java/com/yourorg/omp/app/nginx/NginxTemplate.java`
- Test: `backend/src/test/java/com/yourorg/omp/app/nginx/NginxTemplateTest.java`

### Step 6.1: 写模板

`NginxTemplate.render(App app, AppVersion version, String hmacToken)` 返回字符串，对应设计文档 §6.2。

### Step 6.2: 写快照测试

```java
class NginxTemplateTest {
  @Test
  void rendersPreviewWithAuthBlock() {
    App app = ...; AppVersion v = ...; // env=PREVIEW
    String out = NginxTemplate.render(app, v, "TOK123");
    assertThat(out).contains("location /preview/demo/");
    assertThat(out).contains("proxy_pass http://127.0.0.1:9123/");
    assertThat(out).contains("if ($http_x_omp_user != \"TOK123\")");
  }

  @Test
  void rendersProdWithoutAuthBlock() {
    App app = ...; AppVersion v = ...; // env=PROD
    String out = NginxTemplate.render(app, v, null);
    assertThat(out).contains("location /apps/demo/");
    assertThat(out).doesNotContain("X-Omp-User");
  }
}
```

### Step 6.3: 写 NginxManager

```java
@Component
@RequiredArgsConstructor
public class NginxManager {
  private final ReentrantLock writeLock = new ReentrantLock();
  @Value("${omp.app-publish.nginx.include-dir}") private Path includeDir;
  @Value("${omp.app-publish.nginx.test-cmd}") private String testCmd;
  @Value("${omp.app-publish.nginx.reload-cmd}") private String reloadCmd;

  public void renderAndReload(App app, AppVersion version) {
    writeLock.lock();
    Path target = includeDir.resolve(app.getName() + ".conf");
    Path backup = includeDir.resolve(app.getName() + ".conf.bak");
    String token = version.getEnv() == AppEnv.PREVIEW
        ? WorkspaceSigner.sign(app.getId() + ":" + app.getOwnerId())
        : null;
    String content = NginxTemplate.render(app, version, token);
    try {
      if (Files.exists(target)) Files.move(target, backup, REPLACE_EXISTING);
      atomicWrite(target, content);
      runShell(testCmd);
      runShell(reloadCmd);
      Files.deleteIfExists(backup);
    } catch (Exception e) {
      // 回滚
      if (Files.exists(backup)) Files.move(backup, target, REPLACE_EXISTING);
      throw new NginxReloadException(e);
    } finally {
      writeLock.unlock();
    }
  }

  public void remove(String appName) {
    writeLock.lock();
    try {
      Files.deleteIfExists(includeDir.resolve(appName + ".conf"));
      runShell(reloadCmd);
    } finally { writeLock.unlock(); }
  }
}
```

`WorkspaceSigner.sign(...)` 复用 `com.yourorg.omp.ide.WorkspaceSigner`（最近提交里刚加的 HMAC 工具）。

### Step 6.4: 集成测试（testcontainers nginx）

跳到 Task 8 集中做；本 Task 仅做模板快照 + NginxManager 用 ProcessRunner mock。

### Step 6.5: 提交

```bash
git add backend/src/main/java/com/yourorg/omp/app/nginx/ \
        backend/src/test/java/com/yourorg/omp/app/nginx/
git commit -m "feat(app-publish): add NginxManager and template rendering"
```

---

## Task 7: 预览鉴权 token + 端到端集成测试

**Files:**
- Create: `backend/src/main/java/com/yourorg/omp/app/PreviewTokenService.java`
- Modify: `backend/src/main/java/com/yourorg/omp/app/AppService.java`（新增 `issuePreviewToken`）
- Modify: `backend/src/main/java/com/yourorg/omp/app/AppController.java`（新增 `/preview-token` 接口）
- Test: `backend/src/test/java/com/yourorg/omp/app/PreviewTokenServiceTest.java`

### Step 7.1: 写 PreviewTokenService

```java
public class PreviewTokenService {
  private final long ttlSec;
  public PreviewTokenService(@Value("${omp.app-publish.preview-token.ttl-sec:86400}") long ttlSec) {
    this.ttlSec = ttlSec;
  }
  public PreviewToken issue(App app) {
    long exp = Instant.now().getEpochSecond() + ttlSec;
    String token = WorkspaceSigner.sign(app.getId() + ":" + app.getOwnerId() + ":" + exp);
    return new PreviewToken(token, exp);
  }
}
```

### Step 7.2: Nginx 主配置 `map`（在 ops 文档里改，不在代码仓库）

在 `/etc/omp/nginx/omp.conf` 加入：
```
map $arg_omp_user $header_omp_user_from_query {
    default $arg_omp_user;
    "" "";
}
# 每个应用 location 内：
set $http_x_omp_user $http_x_omp_user;
if ($http_x_omp_user = "") { set $http_x_omp_user $arg_omp_user; }
```

→ 把 query 参数 `?omp_user=xxx` 合并到 header 检查中。这样作者用地址栏打开也能进（带 token 一次性过期即可）。

### Step 7.3: 端到端集成测试

`backend/src/test/java/com/yourorg/omp/app/AppPublishIntegrationTest.java`：
```java
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class AppPublishIntegrationTest {
  @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8");
  @Container static GenericContainer<?> nginx = new GenericContainer<>("nginx:1.27")
      .withCopyToContainer(nginxConf, "/etc/nginx/conf.d/default.conf");

  // 用一个本地 fixture git 仓库（写到 target/）：
  // 1. POST /api/apps 创建
  // 2. POST /preview 异步触发 → 轮询 version.status=READY
  // 3. curl http://nginx:8080/preview/<name>/ 期望 200（带 X-Omp-User header）
  // 4. curl 同 URL 不带 header 期望 403
  // 5. POST /publish → 轮询 PUBLISHED
  // 6. curl http://nginx:8080/apps/<name>/ 期望 200（任何客户端）
  // 7. DELETE /api/apps/<name> 期望 204
}
```

> 实际跑完整 git clone + 构建在测试环境里较重，可用更轻 fixture：写一个最小 `.omp/app.yaml` 仓库，命令是 `echo "built > index.html"`。能验证编排与状态机即可。

### Step 7.4: 提交

```bash
git add backend/src/main/java/com/yourorg/omp/app/PreviewTokenService.java \
        backend/src/main/java/com/yourorg/omp/app/AppService.java \
        backend/src/main/java/com/yourorg/omp/app/AppController.java \
        backend/src/test/java/com/yourorg/omp/app/
git commit -m "feat(app-publish): add preview token service and integration test"
```

---

## Task 8: 回滚 + 软删除清理

**Files:**
- Modify: `backend/src/main/java/com/yourorg/omp/app/AppService.java`（新增 `rollback`）
- Modify: `backend/src/main/java/com/yourorg/omp/app/AppController.java`（`/rollback/{vid}`、`DELETE`）
- Modify: `backend/src/main/java/com/yourorg/omp/app/BuildExecutor.java`（导出 `restartBackend(version)` 给回滚用）
- Test: `backend/src/test/java/com/yourorg/omp/app/AppRollbackTest.java`

### Step 8.1: 写回滚流程测试（先红）

```java
@Test
void rollback_activatesHistoricalVersion() {
  // 准备：app PUBLISHED，currentVersion = v2 PROD
  // 历史版本 v1 PROD status=ARCHIVED backendPort=9150
  when(svc.rollback(1L, "demo", v1.getId())).thenReturn(appResponse);
  // 期望：currentVersion = v1, NginxManager.renderAndReload 被调用一次（指向 v1）
}
```

### Step 8.2: 实现 rollback

`AppService.rollback(userId, name, vid)`：
1. 校验 owner
2. 校验 `version.appId == app.id` 且 `version.env == PROD` 且 status ∈ {READY, ARCHIVED}
3. 如端口已被释放（HELD 但 appId 不匹配或 FREE）：从历史记录恢复 HELD
4. 调用 `BuildExecutor.restartBackend(version)`（用 pid 文件或重新 start）
5. `NginxManager.renderAndReload(app, version)`
6. `app.currentVersionId = version.id`, `app.status = PUBLISHED`

### Step 8.3: 软删除

`AppService.softDelete`：
1. owner 校验
2. 标记 `deletedAt = now()`
3. 找出所有 READY version → kill 后端进程（通过 pid 文件）
4. `PortAllocator.release(port, appId)`
5. `NginxManager.remove(name)`
6. 把所有 version 改 ARCHIVED

### Step 8.4: 跑测试 → 绿

### Step 8.5: 提交

```bash
git add backend/src/main/java/com/yourorg/omp/app/AppService.java \
        backend/src/main/java/com/yourorg/omp/app/AppController.java \
        backend/src/main/java/com/yourorg/omp/app/BuildExecutor.java \
        backend/src/test/java/com/yourorg/omp/app/AppRollbackTest.java
git commit -m "feat(app-publish): add rollback and soft-delete cleanup"
```

---

## Task 9: 配置项 + 文档 + ChangeLog

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Create: `docs/app-publish.md`（用户面向：如何接入、`.omp/app.yaml` 完整 schema）
- Modify: `backend/CHANGELOG.md`（如有）或新建

### Step 9.1: 写 application.yml 段

按设计文档 §11。

### Step 9.2: 写 docs/app-publish.md

内容包含：
- 概述 + 状态机图
- `.omp/app.yaml` 完整 schema + 3 个示例（纯前端、前端+Node、前端+Java）
- API 速查表（路径 + 方法 + 鉴权）
- 预览鉴权机制说明（HMAC + X-Omp-User + query 参数兼容）
- 排错：构建失败看哪个日志、端口耗尽怎么办

### Step 9.3: 写 ChangeLog

按 CLAUDE.md §ChangeLog 格式新增 `[Unreleased] / Added` 段。

### Step 9.4: 提交

```bash
git add backend/src/main/resources/application.yml \
        docs/app-publish.md \
        backend/CHANGELOG.md
git commit -m "docs(app-publish): add user docs, config, and changelog"
```

---

## 整体验证清单

实施完成后，worktree 内跑：

```bash
cd backend
mvn -q test                                  # 单元 + 切片测试
mvn -q -Dtest=AppPublishIntegrationTest test  # 端到端
mvn -q -DskipTests package                    # 构建 jar
```

期望：全部通过。最后 `git log --oneline feature/app-publish` 应该有 9 个 commit，标题与上述一致。

---

## 后续（不在本期）

- 多副本 Nginx 推送
- pi-iso 沙箱构建
- 应用市场
- 灰度发布
- 实时日志 WebSocket 推送（当前仅"完成后可下载"）