# TachyonScript — nhiệm vụ phát triển toàn diện đã lưu

Cập nhật: 2026-10-06. Đây là bản lưu mục tiêu, ràng buộc và lộ trình từ master
mission 126 mục của người dùng, để tiếp tục sau khi clear cuộc trò chuyện. Trạng thái
thực tế, artifact và bằng chứng gần nhất nằm ở `../TASK-HANDOFF.md`; mã nguồn và test
là nguồn sự thật. Danh sách dưới đây là phạm vi cần khảo sát và hoàn thiện, không
phải tuyên bố các tính năng đã được triển khai.

## Mục tiêu và nguyên tắc

Phát triển TachyonScript thành ngôn ngữ scripting Minecraft có kiểu tĩnh, compiler,
runtime, công cụ IDE, hot reload, bảo mật và API addon đủ tin cậy cho vận hành thực.
Giữ kiến trúc và những tính năng đang chạy; không làm lại dự án hoặc trở thành một
bản Skript chỉ đổi cú pháp. Kiểm tra tính năng đã có, tìm lỗi và cải thiện khi cần;
bỏ qua phần đã tốt. Sau mỗi mốc cập nhật tài liệu, sách và wiki theo code thực tế.

Trước thay đổi lớn, đọc `AGENTS.md`, `TASK-HANDOFF.md`, `README.md`, `CHANGELOG.md`,
`docs/status.md`, `docs/architecture.md`, `docs/dependencies.md`, `settings.gradle.kts`,
`build.gradle.kts`; khảo sát các module API, language, IR, compiler, runtime, engine,
security, stdlib, Paper, plugin, CLI, tests, benchmarks, tools và validation.

Giữ pipeline `.tys → lexer/parser/AST → semantic analysis → typed register IR →
verifier → optimizer → backend → engine → Paper/Folia`. Frontend/IR/runtime độc lập
với Bukkit khi có thể. Interpreter là chuẩn tham chiếu cho bytecode backend.

- Kiểm tra git status/diff trước khi sửa; không xoá WIP hoặc thay đổi của người dùng.
- Làm từng đơn vị có thể review: hiểu → thiết kế → code → test → build → đo nếu cần
  → tài liệu → tiếp tục. Không dừng ở danh sách TODO hoặc một việc nhỏ dễ làm.
- Giữ tương thích script. Breaking change cần lý do, ảnh hưởng, migration, test,
  tài liệu và changelog; ưu tiên deprecation.
- Không sửa tay output có generator; chạy generator và kiểm output tương ứng.
- Build đầy đủ sau thay đổi đáng kể; warning được cấu hình là lỗi phải được sửa.
- Không giảm chất lượng/tắt test để làm build xanh; phân biệt lỗi có sẵn với regression.
- Không tự commit, push hoặc triển khai server thật. Không xuất credential vào log,
  chat, repo hoặc đưa script lên dịch vụ ngoài ngoài phạm vi được phép.
- Tự quyết định các việc kỹ thuật nhỏ theo kiến trúc hiện tại; chỉ hỏi khi có blocker
  cần thông tin/quyền mà repo và môi trường không giải quyết được.
- Quy tắc tăng version trong AGENTS tiếp tục áp dụng cho lần bàn giao mới. Việc giữ
  `0.5.1-SNAPSHOT` là yêu cầu riêng cho hotfix bảo mật đã bàn giao, không phải miễn tăng
  version vĩnh viễn. Không đổi số trong bằng chứng lịch sử.

## Thứ tự ưu tiên

| Mức | Công việc |
|---|---|
| P0 | Build, correctness, regression, bảo mật và mất dữ liệu |
| P1 | Kiểm chứng Folia thực tế và CI Paper/Folia |
| P2 | Hoàn thiện optimizer và differential tests |
| P3 | Backend JVM bytecode thật và equivalence với interpreter |
| P4 | LSP dùng chung frontend và formatter |
| P5 | VS Code; chiến lược IntelliJ tái sử dụng LSP |
| P6 | Ổn định addon API, versioning và Maven artifact |
| P7 | Skript audit và các API Minecraft thực sự hữu ích |
| P8 | Profiler, native profiling và công cụ quản trị |
| P9 | Hiệu năng, bộ nhớ, reload stress, concurrency |
| P10 | Release engineering và quality gate 1.0 |

Có thể đổi thứ tự khi bằng chứng/prerequisite yêu cầu. Việc gần nhất là kiểm tra main
checkout kết hợp hotfix với WIP optimizer, rồi đưa WIP qua differential tests trước
khi tính nó là tính năng hoàn tất. Khảo sát Folia/CI tiếp tục song song về mặt kế hoạch;
không tuyên bố đã kiểm chứng Folia khi chỉ có unit test hoặc Paper run.

## Compiler, optimizer và backend

- Optimizer: CFG, def/use, liveness; constant/copy propagation, DCE/dead store,
  comparison/branch folding, jump threading, move/temp elimination, basic block
  simplification, register reuse, CSE/strength reduction/peephole khi có lợi và an toàn.
  Giữ native calls, exceptions, captures, globals/persistence/event state và ranh giới
  async/scheduler. Không xoá lỗi runtime quan sát được hoặc watchdog checkpoints.
- Từng pass phải tắt được để debug; hỗ trợ dump IR trước/sau, code và metadata backend
  theo CLI hiện có. Giữ deterministic compilation và thứ tự output ổn định.
- Differential suite chạy source thật qua compiler/IR/runtime, so optimized/unoptimized:
  số học, nhánh, vòng lặp lồng, hàm/recursion, closure, record, nullability, cast/type
  checks, globals/imports, lỗi và try/catch/finally, native side effects, lệnh/sự kiện.
  Randomized/property tests dùng seed và lưu regression tối giản khi tìm lỗi.
- Bytecode dùng verified IR, ASM nếu phù hợp; hỗ trợ dần primitive/object/null, locals/
  globals, control flow/calls, records/collections/closures, exceptions, natives/modules,
  templates/casts. Kiểm JVM verification, line/source/function debug info, pathological CFG.
  So return/error/mutation/native-call order với interpreter, không đổi mặc định sớm.
- Backend config phải phản ánh runtime thực. Không giả bytecode bằng silent fallback.
  Quản lý classloader theo generation, kiểm thu hồi sau reload và không giữ qua callbacks,
  scheduler, menus, native bindings hoặc profiler. Adaptive execution/superinstructions
  chỉ làm sau correctness và có số đo chứng minh lợi ích.
- Diagnostics/recovery: giảm cascades; gợi ý symbol/member/overload/nullability/import/
  cast/constant/thread/security chính xác. Fuzz lexer, parser, binder, verifier, assembler,
  formatter và bytecode để tránh hang/stack overflow/internal crash; tối giản reproducer.
- IR verifier coi input là boundary không đáng tin: register/type/operand, jump/return,
  exception regions, capture và slot invariants. Ngăn invalid IR tới runtime.
- Language level, compatibility corpus, deprecation metadata liên thông compiler/docs/
  LSP; cache artifact phải khoá theo toàn bộ semantic inputs và dependency hashes.

## IDE và trải nghiệm viết script

- LSP dùng chính frontend và metadata stdlib: diagnostics, completion có kiểu, hover,
  definition/references xuyên module, signature help, symbols, semantic tokens, rename,
  code actions, workspace symbols. Rename theo symbol identity, không thay chuỗi theo tên.
- Completion hỗ trợ locals/parameters/record members/extensions/natives/events/commands/
  imports/types/enum-key constants, narrowing và expected argument types. Hover có chữ
  ký, docs, nullability, nguồn module và deprecation từ metadata dùng chung.
- LSP cache snapshot/hash/module graph đúng invalidation; benchmark workspace lớn.
- Formatter chính thức `tys fmt`, `--check`: deterministic, idempotent, giữ semantics
  và xử lý input dở dang hợp lý; ổn định rồi mới bắt buộc trong CI.
- VS Code dùng LSP cho semantics, TextMate cho tô màu cơ bản, snippets/brackets/comment
  rules. IntelliJ tái sử dụng compiler/LSP; không duy trì parser thứ hai/thứ ba.

## Paper/Folia, stdlib và addon

- Tự động khởi động server thử có giấy phép/tooling phù hợp; test load, events, commands,
  entity/block ownership, cross-region read/write, teleport/spawn/drop, scheduling,
  async→owner, menus, persistence/DB, reload rollback, shutdown trên Paper và Folia.
- Phân loại native: pure/owned read, owned mutation, global, async-safe, blocking,
  cross-region. Metadata threading dùng chung cho dispatch, docs, compiler/LSP khi có ích.
  Không đưa tất cả Folia về global scheduler; tìm giới hạn sidebar cụ thể.
- Làm rõ read-after-write khi thao tác khác region bị deferred; ưu tiên diagnostics/docs
  nếu syntax mới không có semantic model, cancellation/deadlock model rõ ràng.
- Giữ event registration theo số consumer, cheap filter preconditions và ordering/cancel
  semantics. Commands dùng metadata đã resolve; tab completion không DB/network sync.
  Placeholder/menu callbacks cần watchdog và vòng đời generation, disconnect, close,
  nested menus, reload, shutdown. Không cache dữ liệu mà chưa chứng minh semantics.
- Tiếp tục Skript source audit: IMPLEMENT/ADAPT/ALREADY EXISTS/PARTIAL/REJECT/NOT APPLICABLE
  có lý do; không dùng số class làm tuyên bố parity. Tập trung loot/anvil/offline stats,
  inventory/entity/world, scoreboard/teams, advancement/attribute, potion/particle/sound,
  recipes/damage, permissions/PDC/metadata, resource packs/maps/display, ray/chunk/biome/
  structure khi có nhu cầu và mô hình typed tốt.
- Stdlib khai báo sinh tự động: tên ổn định, type/nullability, thread/Folia semantics,
  docs/examples/tests. Inventory coverage và duplicate/naming audit; thêm compatibility
  aliases/deprecation thay vì đổi tên phá script. Generator phải báo API upstream bị đổi.
- Addon API: types/natives/constants/events/lifecycle/docs/thread contracts/LSP metadata,
  không lộ compiler internals không cần thiết. API compatibility/version/capabilities,
  lỗi startup rõ; sources/javadocs/POM và dependency scopes để xuất Maven artifact.
  ABI check và sample addon compile trong CI để chống regression.
- Xác định rõ Minecraft/Paper version strategy, controlled adapters và CI matrix vừa đủ.
  Advanced types/pattern matching/package ecosystem chỉ thêm khi nhu cầu thật biện minh.

## Security, reliability và vận hành

- Tiếp tục module security đang có; deterministic rules/type/taint/capability/runtime/
  permission vẫn là nền tảng. AI là tín hiệu bổ sung, opt-in và dữ liệu ra ngoài minh bạch.
- Qwen: timeouts/rate limits/backoff, bounded requests/responses, malformed/partial JSON,
  prompt injection, script-controlled text, secret redaction, audit và false positives.
  Tôn trọng hotfix outage policy mới; không tái tạo cảnh báo trên mỗi script vô hại.
- Findings có rule/severity, source range, explanation/flow, mitigation và action;
  source/sink paths có thể inspect. Taint bao phủ arguments/chat/network/DB/placeholder/
  config qua aliases, calls/imports, collections/records/null/lambdas. Phân biệt data/code/
  command/path/URL/identifier, không coi mọi external string nguy hiểm như nhau.
- Corpus script nguy hiểm phải bị phát hiện và script an toàn không bị chặn nhầm; fuzz
  flow, test quarantine/revocation/importers, exact-hash approvals và override có audit.
- HTTP: protocol/timeouts/redirect/body bounds, private/local/metadata destinations,
  DNS rebinding và credential leakage. Files: traversal/absolute/symlink/junction sandbox.
  Privileged commands phân biệt trusted constant, validated argument, dynamic untrusted input.
- Webhook chạy ngoài server threads: durable/bounded queue, timeouts, limits, redaction,
  retry storms và log recursion. Không gửi thử Discord nếu chưa được phép gửi thông báo.
- Watchdog/deep recursion/task/event storms/allocation pressure; native Java blocking
  không thể luôn ngắt an toàn, cần monitoring và tài liệu giới hạn chính xác.
- Database: lifecycle/pools, timeouts, transactions, reconnect/cancel/errors/parameters,
  backpressure và shutdown; không block server-owned thread. Persistence: atomicity,
  crash/shutdown flush, migrations/corruption, disconnect/UUID/large player counts.
- Concurrency invariants cho registries, generation swap, callbacks, security jobs,
  LSP compiler state, Paper/Folia; tránh random synchronized và use-after-retire.
- Reload stress hàng trăm/nghìn lần khi khả thi: handlers/tasks ổn định, retired
  generation/classloader/resources thu hồi được. Audit menus, futures, playerdata,
  AST/IR/source/diagnostics và profiler retention. Failure injection cho DB/AI/webhook/
  addon/native/platform, reload giữa việc async và shutdown khi compile.
- `/tys` UX coherent: status/scripts/info/deps/reload/check/profile/security/timings;
  source hash, generation, dependents, security state, tasks/handlers khi hữu ích.
  Reload failure phải nói bản nào còn chạy, nhóm nào chưa thay và vì sao.
- Phân biệt lỗi script, security, platform, addon, compiler/runtime bug; concise default,
  debug detail có phase/version/backend/module để báo lỗi mà không lộ secret.

## Đo lường, CI, release và tài liệu

- JMH có warmup/forks/measurement/JVM/CPU/workload/backend và variance, so baseline/
  optimized interpreter/bytecode/Java khi hợp lý. Không tuyên bố tăng tốc từ số đo nhiễu.
- Theo dõi compile/startup/reload/dispatch/calls/native bridge/allocations/scheduler/
  filters/templates/menus/persistence. Module graph thử 10/100/1000, tới 10000 files khi
  khả thi: cold, single edit, shared dependency edit, memory và correct invalidation.
- Profiler optional functions/lines/natives/IR blocks; native count/total/avg/p50/p95/p99/
  max giúp thấy addon chậm. Đừng thêm overhead vào đường nóng khi không bật.
- Giữ zero-allocation hot paths có số đo; code admin lạnh ưu tiên dễ đọc. Structured
  metrics nội bộ cho admin/API, không thêm external telemetry mặc định.
- CI JDK21, full build/tests, generators/docs/wiki, formatter khi ổn định, security,
  backend equivalence, Paper/Folia smoke; heavy fuzz/benchmarks chạy riêng. Audit stubs,
  TODO/FIXME/unsupported/silent fallbacks và phân loại có bằng chứng.
- Release/version/language/API policy, migration notes, checksums, reproducible archives,
  workflow GitHub Release/Maven; không phụ thuộc máy một maintainer.
- Quality gate 1.0: build sạch, compatibility policy, Paper/Folia integration, không
  known critical data-loss/security bug, differential suites, bytecode production-ready
  hoặc loại rõ khỏi scope, LSP tối thiểu, formatter ổn, API/artifact, reload stress,
  security corpus và benchmark baseline. Không gọi 1.0 chỉ vì nhiều tính năng.
- README/status/architecture/dependencies/changelog/reference phải khớp code. Đồng bộ
  sách và wiki, compile ví dụ, chạy đúng generator; xuất PDF sau khi nội dung hoàn tất.
  Comment invariants và lý do an toàn, không comment điều hiển nhiên.

Sau mỗi phiên đáng kể, báo ngắn: IMPLEMENTED, FIXED, TESTS, PERFORMANCE, SECURITY,
DOCUMENTATION, REMAINING HIGH PRIORITY. Chỉ ghi hoàn tất khi code và bằng chứng tồn tại.
