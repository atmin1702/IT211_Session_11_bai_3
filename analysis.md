# BÁO CÁO PHÂN TÍCH LOGIC NGHIỆP VỤ GIỎ HÀNG (SHOPPING CART SERVICE)
## Chủ đề: Vận dụng chuyên sâu & Kiểm thử các kịch bản đa người dùng phức tạp

---

## 1. Mô tả kịch bản nghiệp vụ (Timeline Scenarios)

Trong môi trường thương mại điện tử với lưu lượng truy cập lớn, việc quản lý tính nhất quán giữa **Giỏ hàng (ShoppingCart)** và **Kho hàng (Inventory/Product Stock)** là một trong những thách thức lớn nhất.

Kịch bản dưới đây minh họa một tình huống tranh chấp tài nguyên điển hình giữa hai người dùng (Người dùng A và Người dùng B) đối với **Sản phẩm X**:

* **Thời điểm $T_1$ (Trạng thái ban đầu):** Sản phẩm X có giá niêm yết là `$20.0`. Hệ thống ghi nhận số lượng tồn kho khả dụng hiện tại là **7 sản phẩm**.
* **Thời điểm $T_2$ (Người dùng A tương tác):** Người dùng A thêm Sản phẩm X vào giỏ hàng cá nhân với số lượng là **5**.
    * *Xử lý của hệ thống:* Kiểm tra $5 \le 7$ (Hợp lệ) $\rightarrow$ Giỏ hàng của A ghi nhận `quantity = 5`. Kho hệ thống vẫn giữ nguyên là **7** vì A mới chỉ "lưu giỏ" chứ chưa thực hiện thanh toán/đặt giữ chỗ (Hold Stock).
* **Thời điểm $T_3$ (Người dùng B mua hàng):** Người dùng B truy cập hệ thống và tiến hành đặt hàng + thanh toán thành công **3 sản phẩm X**.
    * *Xử lý của hệ thống:* Trừ kho vật lý của Sản phẩm X đi 3 đơn vị. Tồn kho khả dụng lúc này giảm xuống còn: $7 - 3 = \mathbf{4}$ **sản phẩm**.
* **Thời điểm $T_4$ (Người dùng A cập nhật):** Người dùng A quay lại giỏ hàng và thực hiện hành động tăng số lượng Sản phẩm X từ 5 lên **7**.

---

## 2. Phân tích các điểm tiềm ẩn gây lỗi (Edge Cases)

Nếu lớp `ShoppingCartService` được hiện thực một cách ngây thơ (naive implementation) hoặc thiếu các kịch bản kiểm thử biên, hệ thống sẽ đối mặt với 3 lỗ hổng logic nghiêm trọng tại thời điểm **$T_4$**:

### A. Lỗi tính toán sai lệch số lượng tăng thêm (Delta Quantity Bug)
* **Bản chất lỗi:** Lập trình viên thiết kế hàm `updateProductQuantity` bằng cách lấy số lượng mới trừ đi số lượng cũ đang có trong giỏ nhằm tìm ra lượng chênh lệch cần bù vào:
  $$\Delta = Q_{\text{mới}} - Q_{\text{cũ}} = 7 - 5 = 2$$
  Sau đó, hệ thống chỉ kiểm tra xem kho hiện tại có đủ đáp ứng cho lượng tăng thêm này hay không:
  $$\text{Nếu } (Q_{\text{kho}} \ge \Delta) \rightarrow \text{Nếu } (4 \ge 2) \rightarrow \text{Cho phép cập nhật!}$$
* **Hậu quả:** Hệ thống phê duyệt yêu cầu cập nhật. Giỏ hàng của A chuyển thành **7**, trong khi tổng kho thực tế của toàn hệ thống lúc này **chỉ còn 4**. Khi người dùng A tiến hành bấm nút thanh toán (Checkout), hệ thống sẽ rơi vào trạng thái **âm kho vật lý**, hoặc luồng xử lý đơn hàng bị sụp đổ đổ vỡ dữ liệu, tạo ra các đơn hàng "ảo" không thể giao, gây khủng hoảng vận hành (Logistics).

> **Nguyên tắc đúng:** Hệ thống bắt buộc phải so sánh **Tổng số lượng mới yêu cầu ($7$)** với **Tổng tồn kho hiện hành ($4$)**. Vì $7 > 4$, hệ thống phải lập tức chặn hành động này lại.

### B. Lỗi bỏ quên "Giá Hiện Hành" tại thời điểm cập nhật (Stale Price Bug)
* **Bản chất lỗi:** Giả sử tại khoảng thời gian giữa $T_2$ và $T_4$, bộ phận vận hành (Admin) cập nhật giá của Sản phẩm X từ `$20.0` lên `$25.0` (do biến động thị trường hoặc hết chương trình Flash Sale). Khi người dùng A cập nhật số lượng ở $T_4$, nếu dịch vụ chỉ thực hiện lệnh SQL `UPDATE cart_item SET quantity = 7` dựa trên dữ liệu giỏ hàng có sẵn mà không nạp lại thực thể `Product` từ cơ sở dữ liệu để lấy giá mới nhất.
* **Hậu quả:** Tổng tiền giỏ hàng (`totalPrice`) của người dùng A vẫn bị tính theo đơn giá cũ `$20.0`. Điều này vi phạm nghiêm trọng quy tắc nghiệp vụ: *"Người dùng luôn phải thanh toán đúng với giá niêm yết mới nhất của sản phẩm tại thời điểm tương tác"*, gây thất thoát doanh thu trực tiếp cho doanh nghiệp.

### C. Lỗi tranh chấp đồng thời (Race Condition / Lost Update)
* **Bản chất lỗi:** Điều gì xảy ra nếu tại chính mili giây của thời điểm $T_4$, Người dùng A bấm nút "Cập nhật giỏ hàng lên 7" và một Người dùng C nào đó cũng bấm nút "Thanh toán 2 sản phẩm X"?
* **Hậu quả:** Cả hai luồng xử lý (Thread A và Thread C) cùng đọc dữ liệu tồn kho từ Database tại cùng một thời điểm và đều thấy số lượng kho là 4. Nếu hệ thống không áp dụng các cơ chế khóa đồng thời (**Concurrency Control**):
    * Thread C thấy kho đủ ($2 \le 4$), trừ kho xuống còn 2 và lưu DB.
    * Thread A cũng thấy kho là 4, thực hiện kiểm tra logic (nếu thiết kế đúng quy tắc so sánh tổng kho) và nghĩ rằng kho đủ cho mình cập nhật (Giả sử A chỉ cập nhật lên 4). Thread A tính toán và ghi đè dữ liệu kho.
    * Kết quả là một trong hai luồng sẽ ghi đè dữ liệu sai lên luồng còn lại, làm hỏng tính toàn vẹn dữ liệu (Data Inconsistency) của toàn bộ hệ thống kho bãi.

---

## 3. Các ngoại lệ (Exceptions) bắt buộc phải ném ra

Để đảm bảo hệ thống vận hành an toàn, tường minh và giúp Frontend hiển thị thông báo lỗi rõ ràng cho người dùng thay vì trả về lỗi chung chung `Internal Server Error (500)`, `ShoppingCartService` phải được thiết kế để ném ra các ngoại lệ sau:

1.  **`InsufficientStockException` (Ngoại lệ thiếu hụt tồn kho):** * *Thời điểm kích hoạt:* Tại $T_4$, khi phát hiện $Q_{\text{yêu cầu mới}} > Q_{\text{tồn kho hiện tại}}$ ($7 > 4$).
    * *Thông báo phản hồi:* `"Số lượng sản phẩm trong kho không đủ để đáp ứng yêu cầu. Hiện tại chỉ còn 4 sản phẩm khả dụng."`
2.  **`ResourceNotFoundException` (Ngoại lệ không tìm thấy tài nguyên):**
    * *Thời điểm kích hoạt:* Khi luồng xử lý thực hiện `productRepository.findById(productId)` nhưng trả về `Optional.empty()` (Do sản phẩm đã bị xóa hoàn toàn khỏi hệ thống bởi Admin trong lúc người dùng đang thao tác).
3.  **`InvalidQuantityException` (Ngoại lệ số lượng không hợp lệ):**
    * *Thời điểm kích hoạt:* Khi người dùng cố tình thay đổi số lượng bằng các giá trị không hợp lệ như số âm (`-5`) hoặc số không (`0`) thông qua việc thay đổi request bên thứ ba (Postman/Tamper Data).
4.  **`OptimisticLockingFailureException` (Ngoại lệ xung đột dữ liệu đồng thời):**
    * *Bản chất:* Do Hibernate kích hoạt thông qua cơ chế `@Version` nếu phát hiện dữ liệu dòng sản phẩm trong DB đã bị thay đổi bởi một User khác trong quá trình luồng hiện tại đang xử lý, ép hệ thống phải Rollback Transaction để bảo vệ an toàn kho.