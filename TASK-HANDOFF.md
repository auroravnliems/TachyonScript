# Bàn giao TachyonScript — tiếp tục master mission, 2026-10-07

## Bản 0.7.0-SNAPSHOT — sửa lỗi audit + backend bytecode (Claude, 2026-10-07, mới nhất)

Người dùng gọi "tiếp tục" và báo lỗi mới: sau khi tắt rồi bật lại AI, mọi `/tys reload` chỉ báo
`TachyonScript failed to start (java.io.IOException: Security audit integrity check failed;
activation blocked)` — TYS không dùng được nữa. Log server ghi 23:43:06 theo giờ UTC (container),
một phút trước tin nhắn; server chạy JAR 0.6.0-SNAPSHOT build 2026-10-06 23:00 (code security giống
hệt source lúc đó). Đã tăng version **0.6.0 → 0.7.0-SNAPSHOT** vì người dùng đã chạy JAR 0.6.0 và
bản này có thêm tính năng (backend bytecode); language level/IR format vẫn 2.

**Nguyên nhân (đã tái hiện, không đoán):** `ScriptEngine.shutdown` gọi `followUps.shutdownNow()`,
ngắt luồng follow-up đang commit kết quả duyệt nền. Luồng bị ngắt làm `FileChannel` tự đóng giữa
lúc ghi: (a) cờ ngắt có sẵn → report `.report.json` 0 byte → lần sau "Invalid incomplete security
report; activation blocked"; (b) ngắt giữa lúc ghi journal → dòng đã xuống đĩa nhưng `append` ném
lỗi, `previousHash` trong RAM không đổi → bản ghi kế tiếp trỏ sai → "Security audit integrity
check failed". Trên đúng JAR 0.6.0 cũ: 22/300 lần ra integrity, 17/300 ra report rỗng
(harness `OldJarMidWrite`/`OldJarInterrupt` trong scratchpad phiên 57ebb61b — đã chạy trước khi
build 07:19 ghi đè `tachyon-plugin/build/libs/TachyonScript-0.6.0-SNAPSHOT.jar`; JAR đó giờ chứa
code đã sửa, ĐỪNG dùng nó làm "JAR cũ"; JAR hotfix 0.5.1 ở `../TachyonScript-hotfix-0.5.1/` cũng
có lỗi này). Stress test bật/tắt AI + restart giữa lúc duyệt: 2/300 report rỗng trước sửa.
Archive người dùng 05/10 (`D:\Downloads\archive-...zip`, có `security/audit.jsonl` 137 dòng)
verify OK — hỏng xảy ra sau đó.

**Sửa (`SecurityAuditStore` viết lại phần lưu trữ):** mọi I/O chạy trên luồng riêng không bị ngắt
(`shielded`, cả `appendFile` của Discord); append journal lỗi thì truncate lại; report ghi lỗi thì
xoá; trước mỗi append so kích thước journal — khác (file bị chép đè/upload khi server chạy, hoặc
dòng ghi được mà báo lỗi) thì `reopen` verify lại thay vì tạo nhánh. Khởi động KHÔNG còn chặn:
journal hỏng → giữ bản `security/audit-damaged-<UTC>.jsonl`, giữ các bản ghi đã verify, dựng lại
phần còn lại từ report theo **thời gian** (trước là theo tên file — sai thứ tự restore/quarantine);
denial chỉ có trong phần hỏng vẫn được giữ, approve/restore cần report; report không đọc được đổi
tên `*.report.json.unreadable`. Thông báo SEVERE qua `SecurityAuditStore(folder, notices)` và
`/tys security status`. Store đã close từ chối ghi; `snapshot()` dùng `incident()` (sửa hồi quy
eviction 0.6.0). Outbox Discord bỏ qua dòng hỏng thay vì làm plugin không khởi động.

**Bằng chứng:** full build 0.7.0 `validation/claude-build-0.7.0-20261007.log` — **618 test, 0
lỗi/skip** (mới: `SecurityAuditRecoveryTest` 10 ca, `SecurityAuditToggleTest` 60 vòng; test cũ
"audit bị sửa thì không mở store" đổi sang hợp đồng mới). Wiki CLI test 12/12
(`validation/claude-wiki-check-0.7.0-20261007.log`). Probe live cùng JAR SHA-256
`2f5c0fa3d7f2024827073b8eeedf44123d655c749b53f7cc2808581ac43042d6`: Paper 28/28 interpreter +
28/28 bytecode, Folia 32/32 + 32/32, tắt sạch (`validation/claude-probes-0.7.0-20261007.log`,
runs `20261007T0024*`–`T0026*`); thêm 1 run Paper với `--seed-security` (thư mục hỏng do code cũ
tạo: journal rẽ nhánh + report rỗng) → khởi động, 2 thông báo phục hồi, 29/29
(`runs/20261007T002938229077Z-paper`). `run.py` có thêm `--backend` và `--seed-security`.
12 thư mục hỏng do JAR cũ tạo đều mở được bằng code mới, cách ly giữ nguyên, restart sạch.

**Backend bytecode** (code từ phiên trước, nay hoàn tất tài liệu + probe live): opt-in
`runtime.backend: bytecode`, mặc định vẫn interpreter. Tài liệu: CHANGELOG mục 0.7.0, docs/status,
architecture §12, benchmarks (bảng JMH), security, integration; wiki Home/Installation/
Configuration/Security/Performance-and-Folia/FAQ/Release-Notes/Writing-Addons/_Footer; sách
00, 01, 03, 04, 30 (Ch.45: runtime.backend + NOTE phục hồi security), 31 (dump bytecode), 32
(Ch.47: "Bộ chạy bytecode ở 0.7.0"), 45 (Ch.63), 46, 47 (Ch.65), 90, 92, 96, 97.

**Sách/PDF:** build đủ bước RELEASE-051 (Typst phải là đường dẫn TUYỆT ĐỐI qua TYPST_CLI): 761 ví dụ
(96 lỗi/24 cảnh báo cố ý như trước), 301 scenario, 214 bài/41 thử thách/74 mã lỗi, JUnit 618/0;
**853 trang**; bìa `titlepage.typ` đã đổi 0.7.0. Log `../TachyonScript-book/validation/build-0.7.0-20261007.log`.
Đã soát trực quan trang 1, 3, 527 (NOTE security), 538 (bảng bytecode), 736 (phụ lục thông báo).
PDF ra Desktop, SHA-256 `84dead47ca9e28671b022e0c3ccd4d2b5623fc970f2b7977a1229d6cd5dff59c`; bản cũ ở
`book/out/history/TachyonScript-tu-A-den-Z-before-0.7.0-59862aa623e6.pdf`.

**Wiki (lượt 2, theo yêu cầu "cập nhật cả wiki"):** rà toàn bộ 36 trang; sửa thêm Home ("New in this update"
cho 0.7.0), Admin-Commands (`/tys version` 0.7.0, `tys dump passes|bytecode`), Security (status), Configuration
(thư mục `security/`), FAQ (mục lỗi "integrity check failed", cách cập nhật), Performance. API-Reference sinh lại
bằng CLI 0.7.0 → giống hệt, không đổi. Kiểm: JUnit `:tachyon-cli:test` 12/12 (255 ví dụ wiki), script mới
`validation/wiki/check_wiki.py`: 559 liên kết nội bộ + anchor, 0 hỏng; 74/74 mã lỗi; YAML khớp config.yml.
Hồ sơ `validation/wiki/20261007-074747/` (before/, check.json, result.json, gradle log). Lúc 07:49 các thay đổi wiki
bị stage bởi công cụ khác (VS Code mở lúc 07:46) — không phải Claude; không commit/push.

**Bàn giao:** `../TachyonScript-0.7.0/` = JAR (SHA ở trên) + `SHA256SUMS.txt` + `HUONG-DAN.md` (cài đặt,
dòng log phục hồi sẽ thấy, bật bytecode, cách chữa tạm với JAR cũ). Không chép config.yml có secret.

**Tiếp theo:** chờ phản hồi người dùng sau khi cài 0.7.0 (log phục hồi trên server thật); cân nhắc
bytecode mặc định khi có bằng chứng từ server thật; P4 LSP/formatter theo mission. Không commit/push/deploy.

## Backend bytecode — ghi chú phiên trước (đã hoàn tất ở mục trên)

ĐÃ XONG (code + test, chưa có tài liệu): `runtime.backend: interpreter|bytecode` nối thật
(TachyonSettings → EngineOptions.backend → Linker); hàm vượt 8000 byte JIT hoặc lỗi sinh mã chạy
interpreter **kèm cảnh báo** (`LinkedModule.notes`, engine log WARN); `tys dump bytecode`;
`BytecodeBackendTest` 80 ca (corpus optimizer + 64 chương trình sinh, CheckClassAdapter, watchdog,
dòng .tys qua StackWalker SHOW_HIDDEN_FRAMES, hidden class được thu hồi); toàn bộ `:tachyon-tests`
chạy với `-Ptachyon.backend=bytecode`: 84/84 đạt. JMH (`validation/claude-jmh-bytecode-20261007.log`):
int loop 30,1→2,75 µs, double 29,8→2,77, fib 815→292, handler 74→45 ns. 32 script người dùng:
371 hàm, 0 vượt JIT, sinh+định nghĩa 148–270 ms (`validation/perf/BytecodeSize.java`).

CÒN LẠI: (1) full build lại + chạy probe Paper/Folia với bytecode (thêm `--backend` vào
`validation/integration/run.py`, dòng ~98 ghi `runtime:
  backend: bytecode`); (2) tài liệu: CHANGELOG,
docs/status (Bytecode backend: Planned → Implemented, opt-in), architecture, benchmarks, wiki
Configuration/Performance/Release-Notes, sách chương 47 (32-hieu-nang.md) + build sách/PDF;
(3) cân nhắc để bytecode mặc định sau khi có bằng chứng live. Wiki YAML đã khớp config.yml mới.

## Mốc "nhẹ hơn 0.5.1" — Claude, 2026-10-06 (lịch sử, đã giao trong JAR 0.6.0)

Người dùng (sau phiên Codex) yêu cầu làm tiếp và: "phiên bản 0.5.1-SNAPSHOT hotfix hiện tại
đang nặng hơn so với dự định". Log server thật của họ: `Loaded 33 scripts ... (1 compiled,
32 unchanged) in 18368.1 ms` và "A reload is already running." Vẫn là cập nhật 0.6.0-SNAPSHOT
(chưa bàn giao JAR 0.6.0 nào cho người dùng) nên **không tăng version thêm**.

Nguyên nhân đã đo và đã sửa (code + test + tài liệu):

- **Review AI chặn reload** (18 s/1 file; restart duyệt tuần tự mọi script). Mới:
  `security.ai.review-mode: background` (mặc định) + `max-concurrent-reviews: 2`. Load không
  chờ; bản sửa đổi giữ bản cũ chạy tới khi duyệt xong rồi tự kích hoạt (selected reload trong
  `ScriptEngine.followUp`, kèm importer chờ cùng — quy tắc `waitingOn`). Kết quả lưu
  `security/ai-reviews.json` (`AiReviewCache`, key = manifest + approval context +
  `QwenSecurityProvider.fingerprint`). `blocking` giữ hành vi cũ (constructor Ai 14 tham số
  mặc định blocking → test cũ không đổi). Test: `BackgroundSecurityReviewTest` (5 ca).
- **Request AI quá lớn** (6,0 MB cho 32 script; ExploitGuard 1,4 MB/2 request). Mới: view gọn
  (`reviewable`, gộp node trùng vị trí có `contexts`/`otherSources`, gộp cờ đối số, `line:col`,
  path rút gọn, giải thích rule một lần): 1,22 MB; ExploitGuard 156 KB/1 request. Hành động
  (economy, send...) luôn gửi; chỉ bỏ hàm thuần/getter không taint. Test: `AiReviewViewTest`.
- **Interpreter kiểm tra revocation trước MỖI lệnh** (4 volatile + Set lookup). Mới: khi vào
  hàm (bỏ qua khi cùng script trong một execution — `ExecutionStack.verified`), trước native
  (`nativeArguments`) và ở loop slow path. JMH 3 fork: int loop 50,1→28,7 µs, double
  53,4→27,7, fib(20) 1362→934. Watchdog đếm cả lời gọi hàm → vá lỗ hổng đệ quy không vòng lặp.
  Test: `RevocationPointsTest`, `InterpreterTest.stopsRunawayRecursionThatNeverLoops`.
- **JAR 5,0 MB → 1,9 MB**: bỏ OkHttp/Okio/Kotlin; `SecureWebTransport` viết lại trên socket JDK
  (DNS pinning, SNI + HTTPS endpoint identification, giới hạn 2 MB cả sau gzip, deadline 30 s
  có timer đóng socket, redirect thủ công). Test 12 ca gồm TLS hostname thật bằng keytool.
- **Phân tích bảo mật tất định** 756→236 ms ấm, 2,3→1,4 s lạnh (32 script), kết luận giống hệt
  (đối chiếu `archive-check-20261005.json`: 18/13/1, cùng rule).
- **Bộ nhớ**: manifest giữ lại không còn node (`withoutNodes`), cache review LRU 256 (trước tăng
  mỗi lần sửa), audit chỉ giữ 512 incident gần nhất + incident quyết định trạng thái; cũ hơn đọc
  từ `.report.json`. Test: `SecurityAuditMemoryTest`. Báo cáo reload không lex lại file mỗi
  diagnostic.
- ASM sửa 9.11 (không tồn tại) → 9.10.1 (bản mới nhất trên Maven Central).

Bằng chứng: full build `validation/claude-build-light4-20261006.log` (**523 test, 0 lỗi/skip**);
wiki CLI test 11/11; probe live Paper `validation/integration/runs/20261006T160119285737Z-paper`
**27/27**, Folia `…20261006T160213862568Z-folia` **31/31**, cả hai tắt sạch; JMH
`validation/claude-jmh-before-20261006.json` vs `claude-jmh-final-20261006.json`; công cụ đo
`validation/perf/{StartupCost,ManifestSize,ReviewRequestSize}.java` (đọc ZIP người dùng chỉ đọc,
không chạy script, không gửi mạng). Tài liệu: CHANGELOG (mục "Lighter and faster"), docs/security,
dependencies, status, benchmarks; wiki Configuration/Security/Release-Notes; sách chương 30, 32, 47.

Lưu ý môi trường: Git Bash heredoc hay hỏng với chuỗi phức tạp → viết script Python bằng Write
rồi chạy. JMH nhiễu khi đang ghi file (OneDrive/Defender) → đo lại lúc máy rảnh, ≥3 fork.

**Tiếp theo (theo mission):** P3 bytecode backend — `BytecodeCompiler` của Codex đã biên dịch
được nhưng CHƯA có test/differential/config; cần kiểm tra JVM verify (CheckClassAdapter),
differential với interpreter (tái dùng corpus `OptimizerDifferentialTest`), nối
`runtime.backend: bytecode` thật, CLI dump bytecode, đo JMH. Sau đó LSP/formatter (P4).

## Mốc đã kiểm chứng — optimizer + Paper/Folia 0.6.0, 2026-10-06

Người dùng tiếp tục gọi **tiếp tục** trong phiên. Master mission vẫn đang thực hiện;
không hiểu phần này là yêu cầu pause. Mốc dưới đã xong, bước kế tiếp là khảo sát và
triển khai P3 JVM bytecode/equivalence theo prerequisite, rồi các ưu tiên đã lưu.

- **IMPLEMENTED:** optimizer conservative, differential corpus/96 seed, engine
  equivalence, CLI pass dump và cờ bật/tắt; probe riêng + runner + CI Paper/Folia.
- **FIXED:** getter entity/player đọc đúng ownership; diagnostic không chạm entity
  ngoài owner; global tick không chờ region; displayName ghi qua owner. Engine
  teardown trong callback disable thay vì đăng ký task sau khi Folia dừng scheduler.
- **TESTS:** full build/check/CLI/wiki **501 test, 0 fail/error/skip**. Live Folia
  1.21.11 build14 **31/31**, Paper build132 **27/27**, GUI client Paper **36/36**.
  Cùng SHA JAR `c78f1593beee7d9bb8b653caab2ce58565e7b177c6b42074b5a5335319de76d3`.
  Hai server probe tắt sạch; unload hook và SQLite `saved=5`, `unloads=2` được đọc
  sau khi Java dừng. CI mới thêm, chưa có remote run; Folia client/menu còn thiếu.
- **PERFORMANCE:** JMH cùng máy off/on chưa chứng minh tăng tốc loop; compile có
  chi phí thêm. Số liệu thô `validation/master-jmh-optimizer-20261006.json`.
- **SECURITY:** giữ hotfix đã bàn giao. Fixture vô hạn bị chặn đúng, đổi sang loop
  hữu hạn dài để thử watchdog, không nới policy. Không gọi AI/Discord thật.
- **DOCUMENTATION:** repo/wiki/API sinh từ CLI đã đồng bộ. Sách 761 ví dụ, 301
  scenario, chỉ 96 lỗi compile/24 warning và 10 lỗi runtime minh họa mong đợi;
  214 bài/41 thử thách/74 mã lỗi đủ. PDF **850 trang, 65 chương, 9 phụ lục** đã xuất
  Desktop, SHA `16313b8d2975a83e82398cf4d0518ede12dd1d70b041bb21c5a605ff4520f425`.
  Giảm từ 879 trang do log cảnh báo security gọn hơn; không mất chương/ví dụ.
  Bìa stale 0.1 đã sửa 0.6.0. Xem 12 trang preview, chữ/bảng/code không bị cắt.
- **REMAINING HIGH PRIORITY:** P3 backend JVM bytecode thật và đối chiếu interpreter;
  mở rộng Folia client/menu/disconnect/load, quan sát CI; P4 LSP/formatter tiếp sau.

Hồ sơ máy đọc: `validation/milestone-optimizer-folia-20261006.json`; hướng dẫn và
phạm vi ở `docs/integration.md`, `docs/compiler/optimizer.md`. Full build log:
`validation/master-integration-milestone-20261006.log`; GUI:
`validation/gui/runs/20261006-080155/result.json`. Book log:
`../TachyonScript-book/validation/build-0.6.0-20261006.log`. PDF cũ được giữ trong
`../TachyonScript-book/book/out/history/` trước khi xuất Desktop.

Version đã tăng **một lần** lên 0.6.0-SNAPSHOT cho cập nhật đang làm; chưa tăng thêm
cho sửa nội bộ. Language level/IR format vẫn 2. Không commit/push/deploy.

### Nhật ký đầu mốc (lịch sử, các việc pending dưới đây đã được mốc trên thay thế)

Người dùng đã gọi tiếp tục. Phát triển đang diễn ra trên main, giữ WIP/hotfix đã có.
Đã tăng version **một lần** lên `0.6.0-SNAPSHOT`; language level/IR format vẫn 2.
Đây là ghi chép lúc bắt đầu mốc; PDF đã được xuất ở mốc phía trên, master mission chưa hoàn tất.

- Baseline main kết hợp hotfix + optimizer: **353/353 test, 0 skip**;
  `validation/master-combined-baseline-20261006.log`.
- Hoàn tất differential corpus: source → compiler → IR → runtime cho toàn pipeline,
  từng pass riêng/từng pass tắt và từng snapshot đã verify; 96 seed có oracle Java;
  so lỗi có vị trí, host effects, globals, closures, exceptions/finally, watchdog.
  Engine tests thêm imports, saved/player state, commands/events, scheduler/reload.
- CLI có `dump passes`, `--no-optimize`, `--disable-pass=<name>`; cờ không lưu vào server.
- Full build/check/installDist mốc optimizer: **488 test đạt, 0 fail/error/skip**;
  `validation/master-optimizer-milestone-20261006.log`. JAR 0.6.0 trong build/libs.
- JMH bật/tắt trên i3-10105F, JDK21.0.12.1, 2 forks, 3 warmup/5 measurement ×1s:
  chưa chứng minh tăng tốc hai loop; compile có chi phí thêm. Raw
  `validation/master-jmh-optimizer-20261006.json`; không ghi đè baseline lịch sử.
- Đã cập nhật repo/wiki/source sách về optimizer; đang đồng bộ phần integration.
- Đang triển khai `tachyon-integration` (probe test riêng), runner Python localhost
  và CI matrix Paper/Folia. Folia 1.21.11 build14 đã tải từ PaperMC và kiểm SHA-256.
  Lần đầu server khởi động nhưng security chặn fixture `while true` đúng thiết kế;
  fixture đổi sang loop hữu hạn dài, giữ nguyên security, đang kiểm lại.
- Tiếp tục xử lý failures thật của Folia/Paper, kiểm/build sau mốc, cập nhật tài liệu,
  sinh wiki reference bằng CLI, chạy sách và xuất PDF trước khi bàn giao.

Các phần bên dưới là bàn giao trước khi bắt đầu phiên này; trạng thái pause và
optimizer WIP trong chúng không thay thế mốc ở trên. Không commit/push/deploy.

## Điểm tiếp tục sau khi clear cuộc trò chuyện

Người dùng yêu cầu lưu việc đang làm rồi tiếp tục hoàn thiện TachyonScript toàn diện,
kèm sách và wiki. Hotfix bảo mật/JAR/config đã bàn giao; master mission **chưa hoàn tất**.
Phạm vi và các yêu cầu dài hạn được lưu tại
[`docs/master-development-mission.md`](docs/master-development-mission.md).

Workspace chính: `C:\Users\cnoc7\OneDrive\Desktop\TachyonScript-main`.
`../TachyonScript-security-hotfix` là worktree detached chỉ để build hotfix ổn định;
không chuyển phát triển master sang đó hoặc chép đè main từ worktree. `../wiki` là
repo riêng; `../TachyonScript-book` là workspace sách. HEAD hiện cùng `bda56fd`;
mọi thay đổi phiên này còn trên đĩa, **chưa commit/push**.

Thứ tự bắt đầu lần tới:

1. Đọc AGENTS, phần bàn giao mới nhất này, master mission, README/CHANGELOG và
   docs/status, architecture, dependencies; kiểm git status/diff. Bảo toàn các file
   tracked lẫn untracked, đặc biệt các pass optimizer mới và tài liệu người dùng.
2. Chạy baseline trên **main đang kết hợp hotfix + optimizer WIP**. 353 test dưới đây
   chứng minh hotfix độc lập; chưa thay cho full build của trạng thái main kết hợp.
3. Review optimizer WIP, thêm differential tests source → compile → execute so sánh
   bật/tắt tối ưu và từng pass; kiểm exception/liveness/captures/globals/native side
   effects và watchdog. Sửa lỗi trước khi mở rộng thêm pass. Chạy verifier mỗi pass
   khi debug; không đánh dấu optimizer hoàn tất từ các test cũ đã qua.
4. Khảo sát và bổ sung Folia integration/CI theo điều kiện môi trường; đi tiếp các
   ưu tiên của master mission theo bằng chứng và prerequisite. Duy trì test/build/
   benchmark khi liên quan và cập nhật status chính xác sau mỗi mốc.
5. Khi bàn giao mốc mới, tăng version một lần theo AGENTS, đồng bộ sách/wiki và xuất
   PDF. Ngoại lệ giữ 0.5.1 chỉ áp dụng hotfix vừa giao. Chưa có bằng chứng người dùng
   đã cài JAR/config lên server; không tự deploy hoặc khôi phục quarantine.

Optimizer WIP cần đọc (đều chỉ nằm trong main, không có trong JAR hotfix):

- `tachyon-ir/.../opt/Optimizer.java`: named pipeline, disable từng pass, observer và verifier.
- File mới `ConstantEvaluation`, `ConstantPropagation`, `CopyPropagation`,
  `ControlFlowGraph`, `Liveness`, `DeadCodeElimination`, `Discardable`, `JumpThreading`.
- `tachyon-compiler/.../CompilerOptions.java`: disabled passes, giữ constructor 3 tham số;
  `Compiler.java` truyền options vào pipeline.
- `tachyon-runtime/.../code/Assembler.java`: bỏ slot của register không dùng.
- Existing tests đã qua ở `validation/master-optimizer-first-20261005.log`; chưa có
  differential corpus, benchmark sau tối ưu hoặc tài liệu thuật toán đầy đủ.

Lệnh Windows tham khảo (Java 21 đã có):

```powershell
Set-Location 'C:\Users\cnoc7\OneDrive\Desktop\TachyonScript-main'
$env:JAVA_HOME = 'C:\Users\cnoc7\.jdks\ms-21.0.12.1'
.\gradlew.bat build check '-Ptachyon.wiki=C:/Users/cnoc7/OneDrive/Desktop/wiki' --console=plain
.\gradlew.bat :tachyon-ir:test :tachyon-compiler:test :tachyon-runtime:test :tachyon-tests:test --console=plain
```

Khi ghi log dài, redirect vào file và đọc tail; giữ exit code Gradle. Luôn quote
tham số `-P...` trong PowerShell. Không đặt `core.autocrlf=false` tạm khi diff checkout
CRLF vì gây báo toàn repo thay đổi giả. Không đọc/in `config.yml` bàn giao ra tool
output: file chứa credential thật. Kiểm tra credential đã xong bằng GET metadata;
không cần lặp lại hoặc gọi AI/Discord trong việc optimizer.

Baseline trước WIP: 343 test đạt, không skip ở
`validation/master-baseline-rerun-20261005.log`; JMH raw ở
`validation/master-jmh-baseline-20261005.json` và `.log` (2 forks, 3 warmup, 5 measurement,
1 giây/iteration). Đây chỉ là baseline, chưa có kết luận optimizer tăng tốc.

Tạm dừng phát triển tại yêu cầu lưu task này. Chỉ bắt đầu lại khi người dùng gọi tiếp tục.

## Trạng thái hiện tại

**Tiếp nối cấu hình 2026-10-06:** đã tạo
`../TachyonScript-hotfix-0.5.1/config.yml` trực tiếp từ `config.yml` trong ZIP người dùng.
API key thật bị bọc sai `${...}`; đã bỏ wrapper và chuyển sang `failure-policy: warn`.
Mọi thiết lập không liên quan giữ nguyên, gồm Discord `enabled: false`. Bộ đọc
SecuritySettings của chính JAR mới xác nhận hợp lệ. GET metadata OpenRouter và
Discord đều HTTP 200, không gọi model/gửi script/gửi tin Discord. Secret chỉ nằm
trong file config bàn giao, không đưa vào repo hoặc log. Có `HUONG-DAN.md` tiếng Việt
trong thư mục bàn giao. JAR/checksum 2026-10-05 bên dưới không đổi.

**Bản vá bảo mật khẩn cấp 2026-10-05 (mới nhất):** người dùng ưu tiên lỗi Qwen làm
31 script không kích hoạt và yêu cầu rõ giữ phiên bản `0.5.1-SNAPSHOT`; đây là ngoại
lệ cho quy tắc tăng phiên bản bên dưới. Đã sửa trong checkout chính và tạo worktree
`../TachyonScript-security-hotfix` từ `bda56fd`, chỉ chép phần security vào để build
artifact, không đưa optimizer đang làm dở vào bản thay gấp.

- AI mặc định `failure-policy: warn`, kể cả config cũ `required: true`; `keep-pending`
  phải được đặt rõ. Giữ kiểm tra tích hợp, quarantine và thu hồi importer.
- Cooldown Qwen chung 60 giây, incident lỗi AI gộp, tối đa một cảnh báo mỗi 5 phút;
  lý do lỗi đã khử bí mật. Giảm false positive callback menu/task, log WARN ngắn hơn.
- Full build/check/shadowJar/installDist worktree thành công: **353 test, 0 fail/error/skip**.
  Log `../TachyonScript-security-hotfix/validation/security-hotfix-build.log`.
- ZIP người dùng `D:\Downloads\archive-2026-10-05T131108Z.zip` được đọc trực tiếp,
  không sửa/giải nén/chạy script/gửi ngoài. `validation/security/SecurityArchiveCheck.java`
  biên dịch đủ 32 file: 18 ALLOW, 13 WARN, 1 QUARANTINE. File cuối là GUI_DynamicJson,
  `papi.parse` → console tại dòng 138; không tự approve/restore. Report và hướng dẫn:
  `docs/security-hotfix-0.5.1.md`.
- JAR chuẩn nằm ở `../TachyonScript-security-hotfix/tachyon-plugin/build/libs/`.
  Bản giao để thay ngay: `../TachyonScript-hotfix-0.5.1/TachyonScript-0.5.1-SNAPSHOT.jar`
  kèm `SHA256SUMS.txt` và `SECURITY-HOTFIX.md`. Đã đối chiếu JAR, cấu hình nhúng và
  xác nhận không chứa các lớp optimizer WIP.
  SHA-256 `d3f48da19ec7edba510b78f176a4ad26adb795da9323d4fcd4301ec1f3449a9d`.
  Tài liệu repo/wiki và source sách đã bổ sung chính sách mới; PDF 879 trang bên dưới
  là bản trước hotfix, chưa xuất lại. Không commit/push/deploy live.
- Kiểm lại wiki sau cập nhật: CLI test đạt, API reference sinh lại khớp, YAML mặc định
  khớp source từng byte nội dung. Cấu trúc sách: 214 bài, 41 thử thách, 74 diagnostic
  đều đủ. `git diff --check` đạt. Evidence: `validation/security/hotfix-20261005.json`
  và `validation/security/archive-check-20261005.json`.

**Master mission đang dở:** baseline 343/343 test và JMH hai workload đã lưu tại
`validation/master-*20261005.*`. Main checkout có WIP optimizer (CFG/liveness,
constant/copy propagation, DCE, jump threading, named passes), CompilerOptions và
Assembler. Test hiện có đã qua nhưng chưa có differential suite/chưa bàn giao;
không ghi nhận optimizer hoàn tất, không xoá WIP. Người dùng chuyển sang sửa security
khẩn trước khi phần này kết thúc. Sau hotfix mới tiếp tục optimizer và sách/wiki.

**Tiếp nối wiki ngày 2026-10-05:** đã đồng bộ wiki tại
`C:\Users\cnoc7\OneDrive\Desktop\wiki` từ nội dung 0.2 lên đúng runtime
`0.5.1-SNAPSHOT` vừa bàn giao. Thay 23 trang hiện có và thêm `Security.md`,
`Release-Notes.md`; giữ các chỉnh sửa sẵn có, không commit/push. Đã cập nhật GUI,
selective reload, disable/enable, slow warning, security, API mới, cấu hình đầy đủ,
log, version và giới hạn khảo sát Skript. API reference sinh bằng CLI hiện tại.

Kiểm wiki: **10/10 test CLI đạt, 0 skipped; 255 ví dụ wiki biên dịch; 549 liên kết
nội bộ hợp lệ; 74/74 diagnostic ID; YAML mặc định khớp source**. Lệnh:
`gradlew.bat :tachyon-cli:test -Ptachyon.wiki=C:/Users/cnoc7/OneDrive/Desktop/wiki`.
Evidence và bản sao các trang trước sửa: `validation/wiki/20261005-145220/`;
log cuối `gradle-wiki-check-final.log`, manifest `result.json`.
Test wiki từng skip trong lần build plugin dưới đây nay đã chạy đạt với checkout này;
bằng chứng build/JAR/PDF trước đó giữ nguyên theo lịch sử.

**Đã hoàn tất phần tiếp nối: vá GUI, kiểm thử, build plugin/CLI và cập nhật/xuất sách.**
Giữ engine TachyonScript và các thay đổi security có sẵn. Không commit/push/deploy.

Người dùng bổ sung yêu cầu: **mỗi bản cập nhật phải tăng phiên bản**. Đã tăng
`0.5.0-SNAPSHOT` → `0.5.1-SNAPSHOT`, đồng bộ Gradle, runtime, tài liệu, CLI và sách;
lưu quy tắc lâu dài trong `AGENTS.md`. Language level/IR format vẫn 2, Java 21,
Paper 1.21.11 và Gradle 9.7.1 giữ nguyên.

Kết quả cuối:

- Vá `PaperEventBridge`: toàn view ScriptMenu không đi qua generic click/drag ở
  mọi priority. Giữ MenuClick, allowTaking và generic cancellation ngoài ScriptMenu.
- Hoàn tất live fixture: seed sau khi mở cửa sổ, kiểm baseline server, tách đúng
  hành vi vanilla DOUBLE_CLICK ô đầy/gom qua ô trống. **36/36 ca đạt** trên JAR mới.
  Evidence: `validation/gui/runs/20261005-144158/result.json` cùng client/server log.
- Full `build check :tachyon-plugin:shadowJar :tachyon-cli:installDist` thành công
  trong 47 giây. **343 test: 342 đạt, 0 fail/error, 1 wiki skip có sẵn** vì thiếu
  checkout. Log: `validation/build-check-0.5.1-20261005.log`.
- Bổ sung 7 test API metadata/rotation/display/statistic/sign/bounding box; operator
  controls có 7 test gồm lỗi I/O persistence. Sửa finite float/rotation extremes và
  `/tys info` cho script bị disable. Reload, emergency stop, log và opt-in warning
  được giữ và mô tả trong tài liệu.
- Sách: **879 trang, 761 ví dụ, 301 scenario**, không có lỗi kiểm thử. Các ví dụ cố
  ý sai được đối chiếu diagnostic/runtime mong đợi. Phụ lục API sinh lại từ registry:
  111 sự kiện, 239 global entries, 780 members, 187 type sections.
- Runner đọc version động, có security module; 4 ca kiểm review fixture đạt. Chỉ
  duyệt hash cả source group sau khi scanner chặn; HTTP/files giả lập trong bộ nhớ.
  Không bật/gọi AI hay gửi Discord thật.
- Dùng Typst CLI 0.15.0 chính thức (archive đã kiểm digest) do DLL Python bị Windows
  Application Control chặn. Không đổi policy Windows. Xem hướng dẫn sách
  `validation/RELEASE-051.md` để lặp lại build.
- Đã xem 7 trang PDF gồm phần giới thiệu, Menu, Display, operator controls và phụ
  lục; đã copy ra Desktop và xác minh hash trùng. Đã xác nhận server/probe dừng sạch.

Artifact cuối:

- JAR: `tachyon-plugin/build/libs/TachyonScript-0.5.1-SNAPSHOT.jar`.
  SHA-256: `292b2d9b49ed783012b3ec00da4b42733a93d8ab1f76e2e853b138d46df5d66d`.
- CLI: `tachyon-cli/build/install/tys/bin/tys.bat`; `version` báo 0.5.1-SNAPSHOT.
- PDF: `C:\Users\cnoc7\OneDrive\Desktop\TachyonScript-tu-A-den-Z.pdf`.
  SHA-256: `38adf7e1d3ff13ef25068f2f036108c8b481a257c918746580fcfb5d7befef41`.
- Báo cáo đủ 8 mục theo yêu cầu: `docs/gui-security-report.md`.
- Kết quả máy đọc: `../TachyonScript-book/validation/release-0.5.1-SNAPSHOT.json`;
  test XML tổng hợp: `../TachyonScript-book/validation/build-results-0.5.1-SNAPSHOT.json`.

Giới hạn được ghi rõ: Folia chưa thử live trong đợt này; generic script vẫn có thể
chủ động uncancel GUI của plugin khác; Skript mới review 29/941 lớp, 912 chưa review,
không tuyên bố parity toàn bộ. Bằng chứng Paper recipe từ đợt trước vẫn là lịch sử.

## Bàn giao cũ ngày 2026-10-04 — chỉ để tra lịch sử

Các trạng thái “chưa hoàn tất”, failure, version và thứ tự tiếp tục bên dưới là
ảnh chụp trước lần tiếp nối; mục **Trạng thái hiện tại** ở trên thay thế chúng.

## Trạng thái và yêu cầu tiếp tục

Người dùng yêu cầu lưu task để clear hội thoại. **Dừng theo yêu cầu, chưa hoàn tất task.**
Đọc file này trước, tiếp tục trên những thay đổi đang có. Không làm lại từ đầu.
Trả lời người dùng bằng tiếng Việt. Không tự commit/push/deploy lên server đang dùng.

Workspace:

- Plugin: `C:\Users\cnoc7\OneDrive\Desktop\TachyonScript-main`
- Sách: `C:\Users\cnoc7\OneDrive\Desktop\TachyonScript-book`
- Skript tham khảo, chỉ đọc: `D:\Downloads\Skript-master\Skript-master`
- Java hiện tại: `C:\Users\cnoc7\.jdks\ms-21.0.12.1`
- Project hiện tại: **0.5.0-SNAPSHOT**, language level 2, Paper 1.21.11, Gradle 9.7.1.
- Không đổi Java/Paper/Gradle version. Giữ engine TachyonScript.
- Không có AGENTS áp dụng trong plugin/sách/ancestors. Không spawn subagents trừ khi người dùng hoặc chỉ dẫn áp dụng yêu cầu rõ.
- Repo đã có nhiều thay đổi security từ trước phiên này. Giữ chúng; không revert các file dirty/untracked.
- PowerShell `Get-Content -Encoding UTF8` để đọc tiếng Việt. `rg` cho tìm kiếm.
- Write qua shell/Gradle hay bị sandbox chặn; dùng escalation có lý do cụ thể. `apply_patch` sửa file được nhưng tạo thư mục mới đôi lúc cần `New-Item` escalated trước.
- Đã kiểm tra sau khi dừng: **không còn python/node/java của GUI probe hay server thử nghiệm chạy**. Server riêng đã stop sạch. Không đụng server gốc của người dùng.

## Yêu cầu người dùng

### Công việc ban đầu (vẫn phải hoàn tất)

1. Log lỗi dễ đọc hơn.
2. `/tys reload <file>` phải chỉ áp dụng file đó và những importer cần recompile, không lôi thay đổi của file khác vào.
3. Emergency `/tys disable` để dừng script có bug kinh tế dù compile đúng; người dùng gõ `diable`, đã hỗ trợ alias.
4. Cảnh báo >10ms phiền: làm cấu hình chủ động, tắt mặc định, giữ profiler.
5. Đối chiếu source Skript để thêm API cần thiết, cải tiến/loại phần không tốt nhưng **luôn giữ engine riêng**. Hỏi API cụ thể thì người dùng trả lời “nó chắc là có trong skript á”. Không được nói đã đạt toàn bộ parity; inventory còn nhiều phần chưa review.
6. Cập nhật sách ở workspace sách và xuất lại PDF.

### Yêu cầu ưu tiên mới nhất: vá exploit GUI trong source

Script `event player.inventoryClick` gọi `event.cancel(); ... event.cancelled=false` có thể uncancel event của MenuListener và lấy icon GUI.

Bắt buộc:

- ScriptMenu do `Menu(...)` tạo phải chỉ qua MenuListener/MenuClick; generic `player.inventoryClick` **không nhận** bất cứ click nào trong view này, bao gồm inventory dưới và ngoài cửa sổ, ở mọi priority, kể cả `allowTaking=true`.
- Audit drag và sửa cùng kiến trúc; generic `player.inventoryDrag` cũng không nhận ScriptMenu.
- Nhận diện holder/class, không title/metadata hack.
- Inventory người chơi, chest, furnace, hopper, GUI plugin khác vẫn dispatch và giữ mọi binding.
- Giữ generic `event.cancel()`, `event.uncancel()`, writable `event.cancelled` và Cancellable contract. GUI bên ngoài có thể bị script chủ động uncancel; báo cáo rõ, không phá compatibility để chặn bừa.
- Giữ MenuClick callback đúng một lần; `allowTaking=false` chặn movement, `true` cho phép; explicit `MenuClick.cancelled=false` vẫn override một click theo contract cũ.
- Kiểm tra LOWEST, LOW, NORMAL, HIGH, HIGHEST, MONITOR và `@ignoreCancelled`.
- Tests LEFT/RIGHT/SHIFT_LEFT/SHIFT_RIGHT/NUMBER_KEY/DOUBLE_CLICK/DROP/CONTROL_DROP/SWAP_OFFHAND, hotbar, cursor, drag, bottom inventory, reload lifecycle.
- Unit/regression tests đúng framework, full suite, Gradle build/check; không disable failing tests.
- Không chỉ sửa `Security_ExploitGuard.tys` (chúng ta không sửa file đó).
- Tài liệu giữ đúng Menu isolation contract.

Báo cáo cuối người dùng yêu cầu các heading:
`ROOT CAUSE` (file/class/method/nguyên nhân), `EVENT FLOW TRƯỚC`, `EVENT FLOW SAU`,
`FILES CHANGED`, `TESTS ADDED`, `BUILD RESULT`, `COMPATIBILITY`, `SECURITY RESULT`.

## GUI: source đã trace và bản vá hiện tại

- `tachyon-platform-paper/.../lib/MenuListener.java`:
  - `onClick` @LOWEST, lấy top `getHolder(false) instanceof ScriptMenu`.
  - Top slot tạo MenuClick với `cancelled = !menu.allowTaking()`; callback qua Screens/context; `event.setCancelled(click.cancelled())`.
  - Bottom/outside: shift và COLLECT_TO_CURSOR bị cancel khi không allowTaking.
  - `onDrag` @LOWEST cancel khi raw slots chạm top và không allowTaking.
  - open/close @MONITOR, lifetime đóng menu khi script retire.
  - **Không thay đổi MenuListener cho patch này**.
- `PaperBindings.java` khoảng325: CANCEL -> `setCancelled(true)`, UNCANCEL -> false; CANCELLED setter trực tiếp `setCancelled(a.getBool(1))`.
  EventApi và docs hiện hành xác nhận mutable API; không sửa generic cancellation.
- `PaperEventBridge.activeEventsChanged` đăng ký listener riêng từng event/priority, Bukkit `ignoreCancelled=false`. Engine tự xét ignoreCancelled từng handler theo trạng thái hiện tại.
- Lỗi cũ: bridge không lọc ScriptMenu -> handler priority sau có thể uncancel bảo vệ @LOWEST.
- **Bản vá nhỏ trong PaperEventBridge.java**, đầu executor đăng ký:

```java
if ((fired instanceof InventoryClickEvent || fired instanceof InventoryDragEvent)
        && ((InventoryEvent) fired).getView().getTopInventory().getHolder(false) instanceof ScriptMenu) {
    return;
}
```

  Trước dispatch và Screens wrapper, nên độc lập order/priority và nhận cả subclasses.
  Open/close events giữ nguyên. Không snapshot cancellation, không hardcode title.

### GUI regression tests đã pass

`tachyon-platform-paper/src/test/java/dev/tachyonscript/platform/paper/MenuEventIsolationTest.java`

- Fixture chạy **compiler/runtime + real Paper bindings + real InventoryClick/Drag events + real RegisteredListener/HandlerList + MenuListener annotations + bridge**. Chỉ fake server/player/inventory.
- @ResourceLock("Bukkit.server"), restore static server sau test.
- 15 invocations:
  - `scriptMenusNeverReachGenericClickOrDragAtAnyPriority` (6 priority, mỗi cái 9 click types và drag): hostile generic cancel/uncancel không chạy, icon/cursor giữ nguyên, callback một lần.
  - `takingPolicyAndBottomInventoryTransfersArePreserved` (false/true): 9 types, bottom shift/double/left/outside, drag top/bottom.
  - `menuCallbackCanExplicitlyAllowOneClick`.
  - `ordinaryAndExternalInventoriesKeepBindingsAndUncancelSemantics` (PLAYER/CHEST/FURNACE/HOPPER): holder ngoài cùng title vẫn dispatch, đầy đủ bindings, uncancel và drag, holder null.
  - `prioritiesAndIgnoreCancelledStillApplyOutsideScriptMenus`.
  - `reloadingClosesOldMenuAndNeverRunsItsRetiredCallback`.
- `MenuTestRegistries.java` + test resource `META-INF/services/io.papermc.paper.registry.RegistryAccess` cấp MenuType identity tối thiểu để Paper1.21.11 InventoryType init; chỉ test, không ship.
- Fixture từng fail do thiếu RegistryAccess và logger không capture native logs; đã sửa.

### GUI docs đã sửa

- `docs/language/gui.md`: whole view isolation click/drag, initial `!allowTaking`, explicit callback override.
- `docs/language/events.md`: generic uncancel có thể gỡ cancel plugin khác, ScriptMenu ngoại lệ dispatch.
- `tools/stdlib-gen/spec/10-events-player.api`: doc inventoryClick/Drag; đã generate.
- `docs/language/reference.md`: đã regenerate đúng CLI, consistency test pass.
- Sách `book/src/27e-menu.md`: thêm whole view isolation và sửa mô tả default cancelled.

## Live Paper GUI probe: đang cần hoàn tất

Files mới trong `validation/gui/`:

- `GuiProbe.java`: test plugin (depend TachyonScript); `/guiprobe prepare locked|taking|override|external [cursor|bottom]`, `/guiprobe inspect`; LOWEST cancel external GUI; MONITOR gửi click/drag state. Snapshot **server-side** icon0,slot1,cursor,diamond inventory/drop.
- `probe.tys`: tạo menus, callback đếm qua chat; 6 generic click handlers cố uncancel ở mọi priority; drag uncancel.
- `client.cjs`: Mineflayer gửi raw packets, kiểm server snapshot (không dùng optimistic client state).
- `run.py`: compile probe, tạo server mới loopback/port ngẫu nhiên trong `runs/<timestamp>`, dùng cached Paper jars; không thay đổi/chạy `book/paper-server`. Java CREATE_NO_WINDOW. Tự stop trong finally.
- `.gitignore` bỏ qua runs/ (giữ evidence trên đĩa).

Chạy `python validation/gui/run.py` (escalated). Dùng Node modules có sẵn trong sách `validation/paper/node_modules`. Chưa tải dependency mới.

**Kết quả mới nhất trước khi user yêu cầu dừng**:

- Run `validation/gui/runs/20261004-190259`: 0 pass do packet cursorItem sai HashedSlot1.21.11. Đã đổi `cursorItem: {itemCount:0}` -> `cursorItem: null` (optional hashed slot), lỗi serialization đã hết.
- Run **`validation/gui/runs/20261004-190439`**: **5 pass** LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT, NUMBER_KEY. Tất cả icon DIAMOND:8, cursor rỗng, không diamond trong inventory/drop, callback đúng1, không generic.
- **Fail tại DOUBLE_CLICK, client.cjs:74**: cursor thực tế AIR:0, kỳ vọng DIAMOND:8. Icon vẫn DIAMOND:8, event DOUBLE_CLICK cancelled=true, callback1, không generic. Đây là **unresolved test failure**, chưa chứng minh lỗi runtime; nghi seed cursor đặt trước deferred menu open nên bị clear. Cần snapshot ngay sau prepare và sửa fixture để seed sau khi cửa sổ thật sự mở (có thể lệnh seed riêng hoặc next tick), không nới assertion.
- Chưa chạy được các ca sau đó: DROP/CONTROL_DROP/SWAP_OFFHAND/bottom/drag/allowTaking/override/external trên live.
- Server đã stop sạch, process audit không còn probe/server java/node/python.
- JAR SHA256 đang được live thử: `f19247c579fa2786dca5e060d450ccba7c215c79bc8c32c5608e4771b79c6b23`.
- `result.json`, `client.log`, `server.log` giữ cả failed evidence. Không được báo live suite xanh.
- Unit test comment nói separate live probe; cần hoàn tất live hoặc ghi rõ limitation.

## Công việc ban đầu: code đã triển khai

### Reload chọn file / emergency controls

- `ScriptSource.read(Set<String>)` default; `ScriptDirectory` override chỉ đọc requested paths, tránh oversized/broken unrelated files. Giữ symlink/realpath/4MB protections và bỏ tên bắt đầu `-`.
- Mới `tachyon-engine/.../ScriptControls.java`: volatile immutable State(all,paths,revision), memory + properties persistence; atomic move, gate disable trước save; enable rollback nếu save fail; all-disabled chặn cả file mới qua restart.
- `LoadedScript` có BooleanSupplier enabled trong revocation gate; revoke vĩnh viễn version cũ để enable không hồi sinh exports/callback cũ.
- `ScriptEngine.controls(...)`, `disable(paths,all)` tính transitive importers, persist/finally revoke; hủy tasks/callback/resources và **không chạy unload hooks** khi emergency stop.
- `ScriptEngine.reload(source,targets)`: nguồn hiệu lực = target đọc mới + source snapshot đang active của các file khác; importers recompile source đang active. Không áp unrelated edit/add/delete. Rollback nhóm selected khi compile/link fail, kể cả lenient. Check controls revision trước và trong activation lock để không race disable/reload.
- Unrelated error history/slow throttle được giữ khi selective reload.
- Plugin startup controls file `plugins/TachyonScript/disabled-scripts.properties`.
- `TysCommand`: `reload [script|all]`, `disable [script|all]` (default all), alias `diable`, `enable <script|all>`, `performance <ms|off>`.
- Permission mới `tachyonscript.manage` và wildcard. Tab all + file paths active/failed/disabled/disk, paths có spaces cho reload/manage.
- `enable <file>` chỉ bật file chỉ định; importer đã bị disable vẫn cần enable riêng/all.
- `/tys scripts` hiển thị operator disabled/global stop.
- Cần review bổ sung: `/tys info` có thể mô tả disabled thiếu chính xác; full load vẫn đọc disabled oversized file trước engine filter; test persistence I/O failures chưa đầy đủ. Đừng mở rộng nếu không thực sự cần.

### Logging/performance

- EngineOptions.DEFAULT threshold0; settings `slow-execution-warnings` opt-in false, threshold50ms.
- Cũ có threshold5/10ms nhưng không bật bool thì không warn. `/tys performance` đổi live và lưu config.
- Chỉ warn tick thread, outermost execution, throttle30s atomic; async vẫn profiler, không warn.
- `DiagnosticRenderer.renderCompact`: severity/path:line:col/code/message + numbered source/caret + notes, không blank spam. CLI renderer cũ giữ nguyên.
- Plugin log từng dòng, giữ redactor; chat hover compact source, click copy location.
- Runtime errors giữ first line/backtrace contract, thêm `--> path:line:col [kind]`, gutter, tabs4, hint.
- `PaperLogger` multiline split; ErrorReporter location có column, chỉ clear replaced paths.

### Tests phần vận hành đã pass

`tachyon-tests/.../OperatorControlsTest.java` 6 tests:

- targetedReloadPreservesUnrelatedEditsDeletionsAdditionsVariablesAndTimers
- importersUseTheirActiveSourcesAndRollbackTogetherOnIncompatibleChanges
- emergencyStopCancelsTasksRevokesExportsSkipsUnloadAndPersistsWithDependents
- globalStopAlsoBlocksNewFilesAndSurvivesRestartUntilExplicitlyEnabled
- disableDuringReloadCannotReactivateThePreparedVersion
- defaultWarningsAreOffAndCanBeChangedWithoutReplacingScripts

`PluginSupportTest`: defaults0, slowWarningsRequireExplicitOptInEvenWithLegacyThreshold, selectedReadDoesNotOpenUnrelatedOversizedScripts.

### API bổ sung từ khảo sát Skript (giữ engine)

- Spec mới `tools/stdlib-gen/spec/12-extensions.api` ~102 declarations:
  - Statistic enum, Player statistic get/set/increment/decrement, material/entity overloads.
  - Metadata tạm Entity/Block/World, lifecycle cleanup.
  - Typed BlockData parse/matches/merge/copy/lighting/tool/etc; **Block.data** typed, Block.blockData string cũ giữ nguyên.
  - SignSide/DyeColor, đọc/ghi sign 2 mặt, glow/color/waxed.
  - Display base + ItemDisplay/BlockDisplay, TextDisplay kế thừa Display; transforms, interpolation, billboard, brightness, rotations; text alignment/opacity/default background.
  - Quaternion, Brightness, BoundingBox (clone trước expand/shift/union), collision getters.
- `Extensions.java`: typed helpers không reflection; signs update, quaternions, transform copies.
- `TransientMetadata.java`: resource ownership theo writer version, remove lúc reload/disable/entity removal/chunk/world unload/shutdown; null xóa; key1..128; max100k active entries. Indexed maps target/chunk/world, không scan-all per entity removal.
  Weak resource map trong LoadedScript; release closure không giữ Entry (đã kiểm tra).
- `PaperContext`, `PaperPlatform` tạo/register/clear metadata.
- Generated `ExtensionsApi`, `ExtensionsBindings`, types/library/bindings và enum key resources; import Extensions xuất hiện nhiều generated files đúng generator.
- **Chưa thêm behavior tests riêng đầy đủ cho các API mới**; cần test metadata ownership/overwrite/cleanup, math validation/copy và ít nhất compile examples/live helper checks. Xem finite-vector float overflow/normalization edge cases nếu cần, không overengineer.

### Inventory Skript và mức độ hoàn thành

- `tools/skript-audit.py` đọc source @Name/prefix và imported Bukkit events, hash nguồn.
- Outputs `docs/skript-audit/features.tsv`, `event-references.tsv`, `summary.json`; `reviewed.json` quyết định29entries.
- 941 source classes:168 condition,40other,155effect,56event,513expression,9section.
- Review:912 unreviewed,6adapted,4missing,6existing,1partial,12implemented.
- 162 explicit event refs,79 có TYS binding; có base/deprecated classes, **không tương đương 83 missing features**.
- Source hash `67783f8582a4f78a343a97c7be3da05372e5d52cc823c76dbd2d972a500bad48`.
- Loot/anvil là missing, timeplayed partial; không implement vì đang ưu tiên GUI.
- **Không tuyên bố “lấy sạch Skript” hay parity hoàn toàn.** Cần tài liệu ngắn quyết định đã thích nghi và phần chưa làm. Không copy wholesale source engine khác.

## Build/test evidence

Đã chạy thành công:

```powershell
$env:JAVA_HOME='C:\Users\cnoc7\.jdks\ms-21.0.12.1'
.\gradlew.bat :tachyon-plugin:test :tachyon-tests:test :tachyon-runtime:test --console=plain
.\gradlew.bat :tachyon-tests:test --tests '*OperatorControlsTest' --console=plain
.\gradlew.bat :tachyon-platform-paper:test --tests '*MenuEventIsolationTest' --console=plain
.\gradlew.bat :tachyon-plugin:shadowJar :tachyon-cli:installDist --console=plain
.\gradlew.bat build check --console=plain
```

**Full `build check` mới nhất SUCCESSFUL in25s**,72tasks,31executed/41up-to-date. Tất cả modules pass. `CliTest.wikiReferenceIsUpToDate` tự skip bởi assumption có sẵn vì không có wiki checkout; không thêm skip/disable test. Chưa đếm tổng test XML.

JAR: `tachyon-plugin/build/libs/TachyonScript-0.5.0-SNAPSHOT.jar`.
CLI: `tachyon-cli/build/install/tys/bin/tys.bat`.

Generate:

```powershell
python tools/stdlib-gen/generate.py --paper-sources C:/Users/cnoc7/OneDrive/Desktop/TachyonScript-book/paperapi/src
```

Output11areas,160types(27keyed),883members,104generated events; handwritten events cộng7 thành111.
Reference phải khớp EXACT `ReferenceGenerator.generate`, `CliTest` kiểm.
PowerShell capture CLI docs và `.NET WriteAllText` UTF8-noBOM, join LF + trailing LF; tránh redirect UTF16.

## Sách: phần lớn còn cần cập nhật và build

Mới chỉ sửa `book/src/27e-menu.md` cho GUI. Các chương khác vẫn mô tả0.2/mặc định5ms/reloadfile như reloadall.

Files cần cập nhật có trọng tâm:

- `00-front.md`: current0.5.0-SNAPSHOT, ghi thay đổi vận hành/API; không biến bằng chứng kiểm thử cũ thành bằng chứng mới.
- `06-doc-loi.md`, `28-loi-bien-dich.md`, `29-go-loi.md`: log format nếu cần.
- `30-lenh-tys.md`: reloadfile paragraph ~80 hiện sai; thêm disable/enable/manage/performance, persistent controls, trạng thái disabled, targeted rollback/importers. Performance section ~209 và bảng/summary còn5ms.
- `32-hieu-nang.md`: ~52 còn default5ms. Sửa opt-infalse/50ms/tick-only/throttle/profiler.
- `47-gioi-han.md`: version/capabilities, remaining Skript gaps trung thực.
- `90-phu-luc-api.md`: old0.2 reference233global+688members174type headings; cần bổ sung full new API/counts dựa generated ref, đừng bịa số.
- `98-bang-kiem.md`: operator runbook.
- Có thể thêm chương API mới có ví dụ compile-checked, không đổi đánh số chương tùy tiện.

Book build:

- `python book/build.py` kiểm **mọi snippet tys** bằng CLI + chạy adjacent scenario bằng Java runner + Typst PDF. Không dùng `--force`.
- `--only REGEX`, `--preview FIRST-LAST` hỗ trợ subset/debug.
- `book/out/TachyonScript-tu-A-den-Z.pdf`; desktop export `C:\Users\cnoc7\OneDrive\Desktop\TachyonScript-tu-A-den-Z.pdf`.
- PDF cũ841pages,754examples,301scenarios: chỉ là lịch sử, chưa validation mới.
- **Runner classpath stale**: `runner/make_classpath.py` hardcode0.2 jar names và thiếu tachyon-security. Cần đọc version gradle.properties, thêm module security/transitive libs cần thiết; rebuild `runner/Runner.java` vào runner/classes theo hướng dẫn existing.
- `book/build.py` trỏ current CLI; Java hardcoded đúng21 hiện tại.
- `HANDOFF-GPT.md`, `book/PLAN.md` là handoff/history cũ, không ghi đè mất lịch sử; không hiểu149 live asserts cũ là evidence của patch này.
- `paper-server`, `validation/paper` có cached binaries/node; dùng read-only làm template.
- Không chạy `validation/security/paper_live_security_smoke.py` hay test gửi Qwen/Discord (không có authorization gửi message).

## Thứ tự tiếp tục đề nghị

1. Đọc latest live probe failure/evidence; sửa fixture cursor seed sau menu mở, thêm baseline snapshot; hoàn tất live click/drag/allowTaking/external tests. Không sửa runtime chỉ để làm probe xanh khi chưa trace.
2. Tests bổ sung API/metadata, review thay đổi vận hành cần thiết, tránh refactor unrelated cho GUI.
3. Hoàn tất docs/plugin report và sách cho thay đổi vận hành/API, ghi rõ mức độ audit Skript.
4. Nếu code/spec đổi, regenerate bindings/reference, chạy tests affected rồi full build/check, cập nhật JAR. Không lặp full tests vô ích khi không có thay đổi/rủi ro mới.
5. Sửa runner classpath, build sách không force; kiểm tra PDF và copy desktop export nếu phù hợp.
6. Báo cáo cuối theo heading người dùng yêu cầu, links source/test/JAR/PDF/evidence; nói rõ external uncancel giữ nguyên và Skript parity chưa toàn bộ.

Thông báo cuối trước pause: unit GUI15pass, contract preserved; full build đã pass nhưng live còn5pass/failure cursor fixture. Người dùng **chưa được báo task hoàn tất**.
