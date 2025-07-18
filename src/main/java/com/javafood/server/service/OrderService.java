package com.javafood.server.service;

import com.javafood.server.dto.request.OrderDetailRequest;
import com.javafood.server.dto.request.OrderRequest;
import com.javafood.server.dto.request.PaymentRequest;
import com.javafood.server.dto.response.BestSellingProduct;
import com.javafood.server.dto.response.OrderResponse;
import com.javafood.server.dto.response.RevenueReport;
import com.javafood.server.dto.response.SimpleOrderResponse;
import com.javafood.server.entity.*;
import com.javafood.server.exception.AppException;
import com.javafood.server.exception.ErrorCode;
import com.javafood.server.mapper.OrderMapper;
import com.javafood.server.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
@Slf4j
@Service
public class OrderService {
    static final String adminRole = "hasAuthority('SCOPE_ADMIN')";
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    OrderDetailRepository orderDetailRepository;
    @Autowired
    PaymentRepository paymentRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    StockRepository stockRepository;
    @Autowired
    StockTransactionRepository stockTransactionRepository;
    @Autowired
    ProductReposity productRepository;
    @Autowired
    EmailService emailService;
    @Autowired
    OrderMapper orderMapper;

    @Autowired
    TemplateEngine templateEngine;

    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_ADMIN') or hasAuthority('SCOPE_USER')")
    public OrderResponse createOrder(OrderRequest orderRequest) {
        if (orderRequest.getUserId() == null || orderRequest.getSubtotalAmount() == null ||
                orderRequest.getShippingAddress() == null || orderRequest.getShippingMethod() == null ||
                orderRequest.getFinalAmount() == null || orderRequest.getOrderDetails() == null ||
                orderRequest.getOrderDetails().isEmpty()) {
            throw new IllegalArgumentException("Dữ liệu đầu vào không hợp lệ");
        }

        UserEntity user = userRepository.findById(orderRequest.getUserId()).orElseThrow( ()-> new AppException(ErrorCode.NOT_EXISTS_DATA));
        OrderEntity orderEntity = OrderEntity.builder()
                .user(user)
                .orderDate(orderRequest.getOrderDate() != null ? orderRequest.getOrderDate() : LocalDateTime.now())
                .subtotalAmount(orderRequest.getSubtotalAmount())
                .shippingAddress(orderRequest.getShippingAddress())
                .shippingMethod(orderRequest.getShippingMethod())
                .shippingFee(orderRequest.getShippingFee() != null ? orderRequest.getShippingFee() : BigDecimal.valueOf(0.0))
                .finalAmount(orderRequest.getFinalAmount())
                .status(orderRequest.getStatus())
                .build();

        orderEntity = orderRepository.save(orderEntity);

        // Tạo và lưu OrderDetailEntity
        Set<OrderDetailEntity> orderDetails = new HashSet<>();
        for (OrderDetailRequest detailRequest : orderRequest.getOrderDetails()) {
            // Kiểm tra productId (nếu không null)
            ProductEntity product = null;
            if (detailRequest.getProductId() != null) {
                product = productRepository.findById(detailRequest.getProductId())
                        .orElseThrow(() -> new IllegalArgumentException("Sản phẩm không tồn tại: " + detailRequest.getProductId()));
            }

            OrderDetailEntity detail = new OrderDetailEntity();
            detail.setOrder(orderEntity);
            detail.setProduct(product);
            detail.setQuantity(detailRequest.getQuantity());
            detail.setOriginalPrice(detailRequest.getOriginalPrice());
            detail.setFinalPrice(detailRequest.getFinalPrice());
            orderDetails.add(detail);
        }
        orderDetailRepository.saveAll(orderDetails);


        Set<PaymentEntity> payments = new HashSet<>();
        if (orderRequest.getPayments() != null && !orderRequest.getPayments().isEmpty()) {
            for (PaymentRequest paymentRequest : orderRequest.getPayments()) {
                if (paymentRequest.getAmount() == null || paymentRequest.getPaymentMethod() == null) {
                    throw new IllegalArgumentException("Dữ liệu thanh toán không hợp lệ");
                }

                PaymentEntity payment = new PaymentEntity();
                payment.setOrder(orderEntity);
                payment.setPaymentMethod(paymentRequest.getPaymentMethod());
                payment.setPaymentDate(paymentRequest.getPaymentDate() != null ? paymentRequest.getPaymentDate() : LocalDateTime.now());
                payment.setTransactionId(paymentRequest.getTransactionId());
                payment.setAmount(paymentRequest.getAmount());
                payment.setTransactionStatus(paymentRequest.getTransactionStatus());
                payments.add(payment);
            }
            paymentRepository.saveAll(payments);
        }

        try {

            DecimalFormat df = new DecimalFormat("#,##0.00");
            List<Map<String, String>> orderDetailsForEmail = new ArrayList<>();
            for (OrderDetailEntity detail : orderDetails) {
                Map<String, String> detailMap = new HashMap<>();
                detailMap.put("productName", detail.getProduct() != null ? detail.getProduct().getProductName() : "Custom item");
                detailMap.put("quantity", String.valueOf(detail.getQuantity()));
                detailMap.put("finalPrice", df.format(detail.getFinalPrice()));
                BigDecimal subtotal = detail.getFinalPrice().multiply(BigDecimal.valueOf(detail.getQuantity()));
                detailMap.put("subtotal", df.format(subtotal));
                orderDetailsForEmail.add(detailMap);
            }

            String orderDateFormatted = orderEntity.getOrderDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
            String subtotalFormatted = df.format(orderEntity.getSubtotalAmount());
            String shippingFeeFormatted = df.format(orderEntity.getShippingFee());
            String finalAmountFormatted = df.format(orderEntity.getFinalAmount());

            String customerEmail = user.getEmail();
            Context context = new Context();
            context.setVariable("customerName", user.getUsername());
            context.setVariable("orderId", orderEntity.getOrderId());
            context.setVariable("orderDate", orderDateFormatted);
            context.setVariable("orderDetails", orderDetailsForEmail);
            context.setVariable("shippingAddress", orderEntity.getShippingAddress());
            context.setVariable("shippingMethod", orderEntity.getShippingMethod());
            context.setVariable("subtotal", subtotalFormatted);
            context.setVariable("shippingFee", shippingFeeFormatted);
            context.setVariable("finalAmount", finalAmountFormatted);

            String emailContent = templateEngine.process("order-confirmation", context);

            Email email = new Email();
            email.setEmail(customerEmail);
            email.setSubject("Xác nhận đơn hàng #" + orderEntity.getOrderId());
            email.setBody(emailContent);

            emailService.sendEmail(email);

        } catch(Exception e){
            // Log lỗi nhưng không làm ảnh hưởng đến quá trình tạo đơn hàng
            System.err.println("Lỗi khi gửi email xác nhận đơn hàng: " + e.getMessage());
        }

        OrderResponse orderResponse = orderMapper.toOrderResponse(orderEntity);
        orderResponse.setOrderDetails(orderMapper.toOrderDetailResponseList(new ArrayList<>(orderDetails)));
        orderResponse.setPayments(orderMapper.toPaymentResponseList(new ArrayList<>(payments)));
        return orderResponse;
    }


    @Transactional(readOnly = true)
    public Page<SimpleOrderResponse> getAllOrders(Pageable pageable) {
        Page<OrderEntity> orderPage = orderRepository.findAll(pageable);
        return orderPage.map(orderMapper::toSimpleOrderResponse);
    }


    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('SCOPE_ADMIN')")
    public OrderResponse getOrderById(Integer orderId) {
        OrderEntity order = orderRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Đơn hàng không tồn tại: " + orderId));

        OrderResponse response = orderMapper.toOrderResponse(order);
        response.setOrderDetails(orderMapper.toOrderDetailResponseList(new ArrayList<>(order.getOrderDetails())));
        response.setPayments(orderMapper.toPaymentResponseList(new ArrayList<>(order.getPayments())));
        return response;
    }


    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('SCOPE_ADMIN') or hasAuthority('SCOPE_USER')")
    public List<OrderResponse> getOrdersByUserId(Integer userId) {
        List<OrderEntity> orders = orderRepository.findByUserId(userId);
        if (orders.isEmpty()) {
            return Collections.emptyList();
        }
        return orders.stream().map(order -> {
            OrderResponse response = orderMapper.toOrderResponse(order);
            response.setOrderDetails(orderMapper.toOrderDetailResponseList(new ArrayList<>(order.getOrderDetails())));
            response.setPayments(orderMapper.toPaymentResponseList(new ArrayList<>(order.getPayments())));
            return response;
        }).collect(Collectors.toList());
    }

    @PreAuthorize("hasAuthority('SCOPE_ADMIN')")
    public RevenueReport getRevenueReport(LocalDateTime startDate, LocalDateTime endDate) {
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate phải trước endDate");
        }
        BigDecimal totalRevenue = orderRepository.getTotalRevenue(startDate, endDate);
        Long numberOfOrders = orderRepository.getNumberOfOrders(startDate, endDate);
        BigDecimal averageOrderValue = numberOfOrders > 0 ? totalRevenue.divide(BigDecimal.valueOf(numberOfOrders), 0, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        return new RevenueReport(totalRevenue, numberOfOrders, averageOrderValue);
    }

    @PreAuthorize("hasAuthority('SCOPE_ADMIN')")
    public List<BestSellingProduct> getBestSellingProducts(LocalDateTime startDate, LocalDateTime endDate, int limit) {
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate phải trước endDate");
        }
        List<Object[]> results = orderDetailRepository.findBestSellingProducts(startDate, endDate);
        return results.stream()
                .limit(limit)
                .map(obj -> {
                    ProductEntity product = (ProductEntity) obj[0];
                    Long totalQuantity = (Long) obj[1];
                    BestSellingProduct bsp = new BestSellingProduct();
                    bsp.setProductId(product.getProductId());
                    bsp.setProductName(product.getProductName());
                    bsp.setTotalQuantitySold(totalQuantity);
                    return bsp;
                })
                .collect(Collectors.toList());
    }

    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_ADMIN')")
    public void updateOrderStatus(Integer orderId, OrderRequest orderRequest){
        OrderEntity order = orderRepository.findByOrderId(orderId).orElseThrow(() -> new AppException(ErrorCode.NOT_EXISTS_DATA));

        PaymentEntity payment = paymentRepository.findByOrderId(orderId);
        List<OrderDetailEntity> orderDetails = orderDetailRepository.findByOrder_OrderId(orderId);
        if (orderDetails.isEmpty()) {
            throw new AppException(ErrorCode.NOT_EXISTS_DATA);
        }
        // Cập nhật trạng thái
        String newStatus = orderRequest.getStatus();
        order.setStatus(newStatus);

        if ("Hoàn thành".equals(newStatus)) {
            payment.setTransactionStatus("Đã thanh toán");

            // Xử lý từng order detail
            for (OrderDetailEntity detail : orderDetails) {
                Integer quantity = detail.getQuantity();
                ProductEntity product = detail.getProduct();
                if (product == null || product.getProductId() == null) {
                    throw new AppException(ErrorCode.NOT_EXISTS_DATA);
                }

                Integer productId = product.getProductId();

                // Tìm stock
                StockEntity stock = stockRepository.findByProduct_ProductId(productId)
                        .orElseThrow(() -> new AppException(ErrorCode.NOT_EXISTS_DATA));

                // Kiểm tra và cập nhật kho
                if (stock.getQuantity() < quantity) {
                    throw new RuntimeException("Số lượng không hợp lệ");
                }
                stock.setQuantity(stock.getQuantity() - quantity);

                // Ghi nhận giao dịch kho
                StockTransactionEntity stockTransaction = StockTransactionEntity.builder()
                        .product(product)
                        .price(product.getPrice())
                        .quantity(quantity)
                        .transactionType(StockTransactionEntity.TransactionType.EXPORT)
                        .build();
                stockTransactionRepository.save(stockTransaction);

                // Lưu thay đổi stock
                stockRepository.save(stock);
            }
        } else {
            payment.setTransactionStatus("Chưa thanh toán");
        }

        // Lưu thay đổi payment và order
        paymentRepository.save(payment);
        orderRepository.save(order);
    }
}
