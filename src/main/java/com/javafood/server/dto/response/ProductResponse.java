package com.javafood.server.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductResponse {
    Integer productId;
    CategoryResponse category;
    DiscountResponse discount;
    List<ImageResponse> images;
    String productName;
    String description;
    String brand;
    String material;
    String dimensions;
    String tags;
    BigDecimal price;
    Boolean isActive;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
