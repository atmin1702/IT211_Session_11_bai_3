package atmin.service;

import atmin.entity.CartItem;
import atmin.entity.Product;
import atmin.entity.ShoppingCart;
import atmin.exception.InsufficientStockException;
import atmin.exception.InvalidQuantityException;
import atmin.exception.ResourceNotFoundException;
import atmin.repository.CartRepository;
import atmin.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ShoppingCartService {

    private final CartRepository cartRepository;
    private final ProductRepository productRepository;

    @Transactional
    public void addProductToCart(Long userId, Long productId, int quantity) {
        if (quantity <= 0) {
            throw new InvalidQuantityException("Số lượng thêm vào giỏ hàng phải lớn hơn 0");
        }

        // 1. Lấy hoặc tự động tạo mới giỏ hàng nếu chưa tồn tại
        ShoppingCart cart = cartRepository.findByUserId(userId)
                .orElseGet(() -> {
                    ShoppingCart newCart = new ShoppingCart();
                    newCart.setUserId(userId);
                    newCart.setItems(new ArrayList<>());
                    newCart.setTotalPrice(0.0);
                    return newCart;
                });

        // 2. Kiểm tra sản phẩm có tồn tại trong hệ thống không
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Sản phẩm không tồn tại trên hệ thống"));

        // 3. Tìm xem sản phẩm đã có trong giỏ hàng chưa
        CartItem existingItem = null;
        for (CartItem item : cart.getItems()) {
            if (item.getProduct().getId().equals(productId)) {
                existingItem = item;
                break;
            }
        }

        int newTotalQuantity = quantity;
        if (existingItem != null) {
            newTotalQuantity = existingItem.getQuantity() + quantity;
        }

        // 4. Kiểm tra tồn kho tổng quy đổi
        if (product.getStock() < newTotalQuantity) {
            throw new InsufficientStockException("Số lượng tồn kho không đủ để đáp ứng yêu cầu");
        }

        // 5. Cập nhật item vào danh sách
        if (existingItem != null) {
            existingItem.setQuantity(newTotalQuantity);
        } else {
            CartItem newItem = new CartItem();
            newItem.setProduct(product);
            newItem.setQuantity(quantity);
            cart.getItems().add(newItem);
        }

        // 6. Tính toán lại tổng tiền dựa trên giá hiện hành
        recalculateTotalPrice(cart);

        cartRepository.save(cart);
    }

    @Transactional
    public void updateProductQuantity(Long userId, Long productId, int newQuantity) {
        if (newQuantity <= 0) {
            throw new InvalidQuantityException("Số lượng cập nhật phải lớn hơn 0");
        }

        ShoppingCart cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy giỏ hàng của người dùng"));

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Sản phẩm không tồn tại trên hệ thống"));

        // Kiểm tra tồn kho hệ thống hiện tại so với số lượng mới yêu cầu
        if (product.getStock() < newQuantity) {
            throw new InsufficientStockException("Số lượng tồn kho hiện tại đã thay đổi, không đủ đáp ứng");
        }

        CartItem targetItem = null;
        for (CartItem item : cart.getItems()) {
            if (item.getProduct().getId().equals(productId)) {
                targetItem = item;
                break;
            }
        }

        if (targetItem == null) {
            throw new ResourceNotFoundException("Sản phẩm không nằm trong giỏ hàng");
        }

        // Cập nhật số lượng mới
        targetItem.setQuantity(newQuantity);

        // Đồng bộ tính lại tiền dựa trên giá mới nhất từ database
        recalculateTotalPrice(cart);

        cartRepository.save(cart);
    }

    @Transactional
    public void removeProductFromCart(Long userId, Long productId) {
        ShoppingCart cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy giỏ hàng của người dùng"));

        // Nghiệp vụ đặc biệt: Chấp nhận cho phép xóa rác ngay cả khi productRepository.findById trả về Empty (đã bị admin xóa)
        CartItem itemToRemove = null;
        for (CartItem item : cart.getItems()) {
            if (item.getProduct().getId().equals(productId)) {
                itemToRemove = item;
                break;
            }
        }

        if (itemToRemove != null) {
            cart.getItems().remove(itemToRemove);
        }

        // Tải lại giá và tính lại tiền cho các sản phẩm còn lại hợp lệ
        recalculateTotalPrice(cart);

        cartRepository.save(cart);
    }

    private void recalculateTotalPrice(ShoppingCart cart) {
        double total = 0.0;
        for (CartItem item : cart.getItems()) {
            // Lấy trực tiếp giá từ đối tượng Product liên kết (đảm bảo cập nhật giá hiện hành)
            Optional<Product> currentProductOpt = productRepository.findById(item.getProduct().getId());
            if (currentProductOpt.isPresent()) {
                double currentPrice = currentProductOpt.get().getPrice();
                total += currentPrice * item.getQuantity();
            } else {
                // Nếu sản phẩm bỗng dưng biến mất khỏi DB, tạm thời giữ nguyên giá cũ hoặc bỏ qua
                total += item.getProduct().getPrice() * item.getQuantity();
            }
        }
        cart.setTotalPrice(total);
    }
}