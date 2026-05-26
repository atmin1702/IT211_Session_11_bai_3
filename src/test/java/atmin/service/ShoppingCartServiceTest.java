package atmin.service;


import atmin.entity.CartItem;
import atmin.entity.Product;
import atmin.entity.ShoppingCart;
import atmin.exception.InsufficientStockException;
import atmin.exception.InvalidQuantityException;
import atmin.repository.CartRepository;
import atmin.repository.ProductRepository;
import atmin.service.ShoppingCartService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ShoppingCartServiceTest {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ShoppingCartService shoppingCartService;

    private Product mockProduct;
    private ShoppingCart mockCart;
    private final Long userId = 1L;
    private final Long productId = 100L;

    @BeforeEach
    void setUp() {
        mockProduct = new Product();
        mockProduct.setId(productId);
        mockProduct.setName("Sản phẩm X");
        mockProduct.setPrice(20.0);
        mockProduct.setStock(10);

        mockCart = new ShoppingCart();
        mockCart.setId(1L);
        mockCart.setUserId(userId);
        mockCart.setItems(new ArrayList<>());
        mockCart.setTotalPrice(0.0);
    }

    @Test
    @DisplayName("HP-01: Thêm sản phẩm mới vào giỏ hàng hiện có thành công")
    void testAddProductToCart_Success() {
        when(cartRepository.findByUserId(userId)).thenReturn(Optional.of(mockCart));
        when(productRepository.findById(productId)).thenReturn(Optional.of(mockProduct));
        when(cartRepository.save(any(ShoppingCart.class))).thenReturn(mockCart);

        shoppingCartService.addProductToCart(userId, productId, 2);

        ArgumentCaptor<ShoppingCart> cartCaptor = ArgumentCaptor.forClass(ShoppingCart.class);
        verify(cartRepository, times(1)).save(cartCaptor.capture());

        ShoppingCart savedCart = cartCaptor.getValue();
        assertEquals(1, savedCart.getItems().size());
        assertEquals(2, savedCart.getItems().get(0).getQuantity());
        assertEquals(40.0, savedCart.getTotalPrice());
    }

    @Test
    @DisplayName("HP-02: Cập nhật tăng số lượng sản phẩm hợp lệ trong giỏ hàng")
    void testUpdateProductQuantity_Success() {
        CartItem existingItem = new CartItem();
        existingItem.setProduct(mockProduct);
        existingItem.setQuantity(2);
        mockCart.getItems().add(existingItem);
        mockCart.setTotalPrice(40.0);

        when(cartRepository.findByUserId(userId)).thenReturn(Optional.of(mockCart));
        when(productRepository.findById(productId)).thenReturn(Optional.of(mockProduct));
        when(cartRepository.save(any(ShoppingCart.class))).thenReturn(mockCart);

        shoppingCartService.updateProductQuantity(userId, productId, 5);

        ArgumentCaptor<ShoppingCart> cartCaptor = ArgumentCaptor.forClass(ShoppingCart.class);
        verify(cartRepository).save(cartCaptor.capture());

        ShoppingCart updatedCart = cartCaptor.getValue();
        assertEquals(5, updatedCart.getItems().get(0).getQuantity());
        assertEquals(100.0, updatedCart.getTotalPrice());
    }

    @Test
    @DisplayName("HP-03: Xóa sản phẩm khỏi giỏ hàng thành công")
    void testRemoveProductFromCart_Success() {
        CartItem existingItem = new CartItem();
        existingItem.setProduct(mockProduct);
        existingItem.setQuantity(3);
        mockCart.getItems().add(existingItem);
        mockCart.setTotalPrice(60.0);

        when(cartRepository.findByUserId(userId)).thenReturn(Optional.of(mockCart));
        when(cartRepository.save(any(ShoppingCart.class))).thenReturn(mockCart);

        shoppingCartService.removeProductFromCart(userId, productId);

        ArgumentCaptor<ShoppingCart> cartCaptor = ArgumentCaptor.forClass(ShoppingCart.class);
        verify(cartRepository).save(cartCaptor.capture());

        ShoppingCart savedCart = cartCaptor.getValue();
        assertTrue(savedCart.getItems().isEmpty());
        assertEquals(0.0, savedCart.getTotalPrice());
    }

    @Test
    @DisplayName("UP-01: Thêm sản phẩm thất bại do vượt quá số lượng tồn kho")
    void testAddProductToCart_InsufficientStock() {
        when(cartRepository.findByUserId(userId)).thenReturn(Optional.of(mockCart));
        when(productRepository.findById(productId)).thenReturn(Optional.of(mockProduct));

        assertThrows(InsufficientStockException.class, () -> {
            shoppingCartService.addProductToCart(userId, productId, 15);
        });

        verify(cartRepository, never()).save(any(ShoppingCart.class));
    }

    @Test
    @DisplayName("UP-02: Thêm sản phẩm với số lượng lỗi (<= 0)")
    void testAddProductToCart_InvalidQuantity() {
        assertThrows(InvalidQuantityException.class, () -> {
            shoppingCartService.addProductToCart(userId, productId, 0);
        });

        verify(cartRepository, never()).save(any(ShoppingCart.class));
    }

    @Test
    @DisplayName("ADV-01: Tự động khởi tạo giỏ hàng mới nếu người dùng chưa có giỏ")
    void testAddProductToCart_CreateNewCartAutomatically() {
        when(cartRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(productRepository.findById(productId)).thenReturn(Optional.of(mockProduct));
        when(cartRepository.save(any(ShoppingCart.class))).thenAnswer(invocation -> invocation.getArgument(0));

        shoppingCartService.addProductToCart(userId, productId, 3);

        ArgumentCaptor<ShoppingCart> cartCaptor = ArgumentCaptor.forClass(ShoppingCart.class);
        verify(cartRepository).save(cartCaptor.capture());

        ShoppingCart newCart = cartCaptor.getValue();
        assertNotNull(newCart);
        assertEquals(userId, newCart.getUserId());
        assertEquals(1, newCart.getItems().size());
        assertEquals(60.0, newCart.getTotalPrice());
    }

    @Test
    @DisplayName("ADV-02: Lỗi cập nhật số lượng do tồn kho bị giảm bởi người dùng khác (Kịch bản Phần 1)")
    void testUpdateProductQuantity_StockReducedByAnotherUser() {
        CartItem existingItem = new CartItem();
        existingItem.setProduct(mockProduct);
        existingItem.setQuantity(5);
        mockCart.getItems().add(existingItem);
        mockCart.setTotalPrice(100.0);

        // Giả lập luồng: Người dùng khác đã mua, kho sụt từ 10 xuống còn 4
        mockProduct.setStock(4);

        when(cartRepository.findByUserId(userId)).thenReturn(Optional.of(mockCart));
        when(productRepository.findById(productId)).thenReturn(Optional.of(mockProduct));

        // Người dùng hiện tại cố cập nhật lên 7 -> Phải văng lỗi vì 7 > 4
        assertThrows(InsufficientStockException.class, () -> {
            shoppingCartService.updateProductQuantity(userId, productId, 7);
        });

        verify(cartRepository, never()).save(any(ShoppingCart.class));
    }

    @Test
    @DisplayName("ADV-03: Xóa sản phẩm ra khỏi giỏ hàng khi sản phẩm đó đã bị xóa hoàn toàn khỏi DB")
    void testRemoveProductFromCart_ProductDeletedFromSystem() {
        CartItem staleItem = new CartItem();
        staleItem.setProduct(mockProduct);
        staleItem.setQuantity(2);
        mockCart.getItems().add(staleItem);
        mockCart.setTotalPrice(40.0);

        when(cartRepository.findByUserId(userId)).thenReturn(Optional.of(mockCart));
        when(cartRepository.save(any(ShoppingCart.class))).thenReturn(mockCart);

        assertDoesNotThrow(() -> {
            shoppingCartService.removeProductFromCart(userId, productId);
        });

        ArgumentCaptor<ShoppingCart> cartCaptor = ArgumentCaptor.forClass(ShoppingCart.class);
        verify(cartRepository).save(cartCaptor.capture());

        ShoppingCart updatedCart = cartCaptor.getValue();
        assertTrue(updatedCart.getItems().isEmpty());
        assertEquals(0.0, updatedCart.getTotalPrice());
    }
}