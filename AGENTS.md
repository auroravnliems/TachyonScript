# Quy tắc làm việc với TachyonScript

- Giữ engine TachyonScript và những thay đổi hiện có; không làm lại từ đầu hoặc
  hoàn tác thay đổi không thuộc công việc đang xử lý.
- Theo yêu cầu người dùng, mỗi bản cập nhật bàn giao phải tăng số phiên bản một
  lần. Bản sửa lỗi tăng patch; cập nhật tính năng hoặc thay đổi không tương thích
  chọn mức tăng phù hợp. Không tăng thêm cho từng lần sửa nội bộ hay chạy lại build
  trong cùng một bản cập nhật.
- Đồng bộ `gradle.properties`, `TachyonVersion.RUNTIME`, changelog, tài liệu, tên
  artifact, sách trong workspace `../TachyonScript-book` và wiki tại `../wiki`.
  Khi đồng bộ wiki cho một bản runtime vừa bàn giao, dùng đúng phiên bản đó.
  Giữ nguyên số phiên
  bản trong log và bằng chứng kiểm thử lịch sử. Language level và IR format chỉ
  tăng khi ngữ nghĩa tương ứng thay đổi.
- Build và kiểm thử artifact của phiên bản mới trước khi bàn giao. Ghi kết quả và
  đường dẫn artifact vào `TASK-HANDOFF.md` để lần tiếp tục sau không lặp lại việc
  đã hoàn tất.
- Wiki là repo riêng. Giữ các chỉnh sửa đang có, sinh `API-Reference.md` bằng CLI
  `docs --wiki` và kiểm bằng `:tachyon-cli:test -Ptachyon.wiki=../wiki` để kiểm tra
  cả ví dụ lẫn reference, thay vì bỏ qua vì thiếu checkout trong repo plugin.
- Không tự commit, push hoặc triển khai lên server đang dùng.
