# Báo cáo vá GUI — 0.5.1-SNAPSHOT, 2026-10-05

## ROOT CAUSE

`PaperEventBridge.activeEventsChanged()` đăng ký executor ở từng priority và trước
đây chuyển cả `InventoryClickEvent`/`InventoryDragEvent` của ScriptMenu vào engine.
`MenuListener.onClick()` bảo vệ menu ở LOWEST, nhưng generic handler chạy sau đó có
thể gọi `event.uncancel()` hoặc gán `event.cancelled = false`. Binding Cancellable
hoạt động đúng hợp đồng; lỗi là chuyển sự kiện thuộc menu sang handler tổng quát.

## EVENT FLOW TRƯỚC

Paper event → MenuListener LOWEST đặt cancellation/callback → PaperEventBridge →
generic `player.inventoryClick` ở priority sau gỡ cancellation → Paper có thể chuyển
icon ra khỏi menu. Drag cũng có đường dispatch chồng lấn tương tự.

## EVENT FLOW SAU

Ở đầu executor, bridge kiểm event là click/drag và
`event.getView().getTopInventory().getHolder(false) instanceof ScriptMenu`.
Nếu đúng, bridge kết thúc trước khi dispatch hoặc bọc Screens; MenuListener tiếp tục
sở hữu view. Quy tắc áp dụng ô trên, inventory dưới và ngoài cửa sổ, mọi priority,
`@ignoreCancelled`, cả khi `allowTaking = true`. Nhận diện bằng holder/class, không
dùng tiêu đề hoặc metadata.

MenuListener và generic cancellation bindings giữ nguyên. Callback MenuClick ở ô
có handler chạy đúng một lần; giá trị ban đầu `cancelled = !allowTaking`. Callback
có thể chủ động cho phép một click bằng `cancelled = false`. Drag chạm menu bị chặn
khi khoá; các thao tác chỉ trong túi người chơi vẫn giữ quy tắc cũ.

## FILES CHANGED

- `tachyon-platform-paper/.../PaperEventBridge.java`: bộ lọc view tại biên dispatch.
- `tachyon-platform-paper/src/test/.../MenuEventIsolationTest.java`,
  `MenuTestRegistries.java`, test service provider: regression compiler/runtime,
  binding thật, Bukkit events và RegisteredListener thật.
- `validation/gui/GuiProbe.java`, `probe.tys`, `client.cjs`, `run.py`: plugin/client
  kiểm thử trên Paper riêng với snapshot server, không dựa vào dự đoán client.
- `docs/language/gui.md`, `events.md`, spec `10-events-player.api`, reference sinh
  tự động và chương Menu trong sách: ghi rõ hợp đồng cách ly toàn view.
- Công việc tiếp nối: operator controls/reload/logging, `Extensions.java` và
  `TransientMetadata.java` cùng spec/bindings/API đã có trong bàn giao; bổ sung test,
  kiểm finite float/chuẩn hoá rotation và trạng thái disabled trong `/tys info`.
- Tài liệu vận hành, khảo sát Skript, các chương sách và phụ lục API được cập nhật.
  Runner đọc version hiện tại, có module security và duyệt fixture theo hash toàn
  nhóm source trong HTTP giả lập. Không thay policy security của plugin.
- Tăng runtime/plugin/CLI/sách từ `0.5.0-SNAPSHOT` lên `0.5.1-SNAPSHOT`. `AGENTS.md`
  lưu yêu cầu tăng phiên bản cho mỗi bản cập nhật bàn giao; language level và IR
  format vẫn là 2.

## TESTS ADDED

GUI regression: **15 invocations**. Sáu priority LOWEST, LOW, NORMAL, HIGH, HIGHEST,
MONITOR; `@ignoreCancelled`; LEFT/RIGHT/SHIFT_LEFT/SHIFT_RIGHT/NUMBER_KEY/DOUBLE_CLICK/
DROP/CONTROL_DROP/SWAP_OFFHAND; top/bottom/outside; drag; allowTaking; override;
PLAYER/CHEST/FURNACE/HOPPER/holder ngoài cùng tiêu đề/holder null; reload thu hồi menu.

Paper live: **36/36 ca**, trên JAR cuối, gồm snapshot icon/cursor/diamond trong túi
và dưới đất, hotbar/offhand, drag trái/phải, drag chỉ túi dưới, lấy đồ được phép,
uncancel GUI ngoài và click dùng window cũ sau reload. Mỗi lần prepare kiểm baseline
server trước khi gửi packet. DOUBLE_CLICK trên ô đầy và gom đồ qua ô trống được
kiểm riêng theo hành vi vanilla. Các lần thất bại khi phát triển fixture được giữ
trong `validation/gui/runs/`; không tính chúng là lần kiểm đạt.

API: **7 test** về ownership metadata khi ghi đè/reload/disable/shutdown, cleanup
entity/chunk/world, key validation, rotation biên số học, transform bản sao,
bounding box/boundary, overload statistic và sign update/side/index.

Operator: **7 test**, gồm selective reload, rollback importer, emergency stop/
restart/race và lỗi I/O khi lưu disable/enable. PluginSupportTest kiểm opt-in warning
và selected read không mở file quá lớn không liên quan.

Book runner: **4 ca** cho scanner và duyệt fixture: không duyệt bị chặn; đúng hash
được duyệt sau khi chứng minh gate đã chặn; sửa library/importer đều bị chặn lại.
HTTP của fixture chạy trong bộ nhớ, không gửi Discord hoặc gọi AI.

## BUILD RESULT

`gradlew.bat build check :tachyon-plugin:shadowJar :tachyon-cli:installDist` thành công.
JUnit XML ghi **343 test: 342 đạt, 0 fail/error, 1 skipped**. Skip có sẵn là
`CliTest.wikiReferenceIsUpToDate` do không có wiki checkout; không thêm skip hay
disable test. Log: `validation/build-check-0.5.1-20261005.log` (47 giây).

JAR: `tachyon-plugin/build/libs/TachyonScript-0.5.1-SNAPSHOT.jar`.
SHA-256: `292b2d9b49ed783012b3ec00da4b42733a93d8ab1f76e2e853b138d46df5d66d`.
Đã kiểm `plugin.yml` trong JAR và `tys version` đều báo `0.5.1-SNAPSHOT`; fixture
test không được đóng gói vào plugin.
Live evidence: `validation/gui/runs/20261005-144158/result.json`, `client.log`,
`server.log`. Server thử đã dừng; process audit không còn probe/server.

Sách **879 trang, 761 ví dụ, 301 scenario, 0 lỗi kiểm thử**. 96 lỗi/24 cảnh báo
compiler và 10 lỗi runtime thuộc các ví dụ cố ý sai, đã đối chiếu kỳ vọng; không
có lỗi runtime ngoài dự kiến hoặc API thiếu trong test platform. Đủ lời giải cho
214 bài tập, 41 thử thách và tài liệu cho 74 diagnostic ID.

PDF đã xuất tới `../TachyonScript-tu-A-den-Z.pdf`, khớp SHA-256 với bản trong
workspace sách: `38adf7e1d3ff13ef25068f2f036108c8b481a257c918746580fcfb5d7befef41`.
Đã xem các trang 3, 443, 455, 521, 697, 738, 879: chữ Việt, bảng và code đọc được,
không thấy cắt nội dung trên các trang này. Renderer là Typst CLI 0.15.0 chính thức.
Chi tiết: `TachyonScript-book/validation/release-0.5.1-SNAPSHOT.json` và
`book/out/build-051-release-20261005.log` trong workspace sách. Phụ lục sinh từ
registry: 111 sự kiện, 239 hàm/thuộc tính toàn cục, 780 thành viên trong 187 mục kiểu.

## COMPATIBILITY

Giữ engine TachyonScript, Java 21, Paper 1.21.11, Gradle 9.7.1 và các thay đổi
security hiện có. Không sửa `Security_ExploitGuard.tys`. Generic `cancel()`,
`uncancel()`, writable `cancelled` giữ nguyên ở inventory thường và GUI plugin khác;
script vẫn có thể chủ động gỡ cancellation của plugin khác. Open/close bindings
không bị bộ lọc click/drag loại bỏ. Không có commit, push hoặc deploy server thật.

## SECURITY RESULT

Đường exploit generic script gỡ cancellation để lấy icon của ScriptMenu đã được
chặn tại dispatch, độc lập priority và cả với drag. Đây là bảo vệ hợp đồng giữa
generic handlers và ScriptMenu; callback chủ động mở khoá và listener Java của
plugin khác vẫn nằm trong quyền của chúng. Folia chưa được thử live trong đợt này.

Đối chiếu Skript chưa toàn bộ: 29/941 lớp có quyết định review, 912 chưa review;
Loot/AnvilView và thống kê offline còn thiếu. Xem `docs/skript-audit/README.md`.
