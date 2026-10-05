# Bàn giao TachyonScript — hoàn tất 0.5.1-SNAPSHOT, 2026-10-05

## Trạng thái hiện tại

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
