package com.erp.backend.service;

import com.erp.backend.dto.category.*;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.Product;
import com.erp.backend.entity.ProductCategory;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.ProductCategoryRepository;
import com.erp.backend.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * S2-06: Quản lý nhóm hàng nhiều cấp.
 * - Cây tối đa MAX_LEVEL cấp (yêu cầu tối thiểu 3 cấp).
 * - Chuyển sản phẩm giữa các nhóm.
 * - Nhóm còn nhóm con hoặc còn sản phẩm thì không xoá được.
 */
@Service
@RequiredArgsConstructor
public class ProductCategoryService {

    static final int MAX_LEVEL = 5;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final ProductCategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    /** Toàn bộ cây dạng danh sách phẳng, sắp theo cấp rồi theo tên. */
    @Transactional(readOnly = true)
    public List<CategoryResponse> getAll() {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : productRepository.countByCategory()) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return categoryRepository.findAllByOrderByLevelAscNameAsc().stream()
                .map(c -> toResponse(c, counts.getOrDefault(c.getId(), 0L)))
                .toList();
    }

    @Transactional
    public CategoryResponse create(CreateCategoryRequest req) {
        String code = req.getCode().trim().toUpperCase();
        if (categoryRepository.existsByCodeIgnoreCase(code)) {
            throw BusinessException.conflict("CATEGORY_CODE_EXISTS", "Mã nhóm hàng '" + code + "' đã tồn tại", "code");
        }

        ProductCategory parent = null;
        int level = 1;
        if (req.getParentId() != null) {
            parent = categoryRepository.findById(req.getParentId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "PARENT_NOT_FOUND",
                            "Không tìm thấy nhóm cha", "parentId"));
            level = parent.getLevel() + 1;
        }
        if (level > MAX_LEVEL) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MAX_LEVEL_EXCEEDED",
                    "Cây nhóm hàng tối đa " + MAX_LEVEL + " cấp", "parentId");
        }

        ProductCategory category = ProductCategory.builder()
                .code(code)
                .name(req.getName().trim())
                .description(blankToNull(req.getDescription()))
                .level(level)
                .parent(parent)
                .build();
        return toResponse(categoryRepository.save(category), 0L);
    }

    /** Sửa tên, mô tả. Đổi tên thì cập nhật luôn ô nhóm hàng của các sản phẩm trong nhóm. */
    @Transactional
    public CategoryResponse update(Long id, CategoryRequest req) {
        ProductCategory category = findCategory(id);
        category.setName(req.getName().trim());
        category.setDescription(blankToNull(req.getDescription()));
        ProductCategory saved = categoryRepository.save(category);

        List<Product> products = productRepository.findByProductCategory_Id(id);
        products.forEach(p -> p.setCategory(saved.getName()));
        productRepository.saveAll(products);

        return toResponse(saved, products.size());
    }

    @Transactional
    public void delete(Long id) {
        ProductCategory category = findCategory(id);
        if (categoryRepository.existsByParent_Id(id)) {
            throw BusinessException.conflict("CATEGORY_HAS_CHILDREN",
                    "Nhóm hàng còn nhóm con, hãy xoá hoặc chuyển nhóm con trước", null);
        }
        if (productRepository.existsByProductCategory_Id(id)) {
            throw BusinessException.conflict("CATEGORY_HAS_PRODUCTS",
                    "Nhóm hàng còn sản phẩm, hãy chuyển sản phẩm sang nhóm khác trước", null);
        }
        categoryRepository.delete(category);
    }

    /**
     * Sản phẩm trong nhóm. includeSubgroups = true thì lấy cả sản phẩm của các nhóm con, cháu...
     */
    @Transactional(readOnly = true)
    public PageResponse<CategoryProductItem> getProducts(Long id, boolean includeSubgroups, int page, int size) {
        findCategory(id);
        Set<Long> ids = includeSubgroups ? collectWithDescendants(id) : Set.of(id);

        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Page<Product> result = productRepository.findByProductCategory_IdIn(ids,
                PageRequest.of(safePage, safeSize, Sort.by("name").ascending().and(Sort.by("id"))));
        return PageResponse.of(result.map(this::toItem));
    }

    /** Chuyển sản phẩm vào nhóm (sản phẩm đang ở nhóm nào cũng được). */
    @Transactional
    public MoveProductsResponse moveProducts(Long id, MoveProductsRequest req) {
        ProductCategory target = findCategory(id);
        Set<Long> productIds = new LinkedHashSet<>(req.getProductIds());

        List<Product> products = productRepository.findAllById(productIds);
        if (products.size() != productIds.size()) {
            Set<Long> found = new HashSet<>();
            products.forEach(p -> found.add(p.getId()));
            List<Long> missing = productIds.stream().filter(pid -> !found.contains(pid)).toList();
            throw new BusinessException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND",
                    "Không tìm thấy sản phẩm có id: " + missing, "productIds");
        }

        products.forEach(p -> {
            p.setProductCategory(target);
            p.setCategory(target.getName());
        });
        productRepository.saveAll(products);
        return new MoveProductsResponse(target.getId(), target.getName(), products.size());
    }

    // ======================= HÀM PHỤ =======================

    private ProductCategory findCategory(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy nhóm hàng"));
    }

    /** id của nhóm và toàn bộ nhóm con, cháu... (số nhóm hàng ít nên duyệt trong bộ nhớ). */
    private Set<Long> collectWithDescendants(Long rootId) {
        Map<Long, List<Long>> children = new HashMap<>();
        for (ProductCategory c : categoryRepository.findAll()) {
            if (c.getParent() != null) {
                children.computeIfAbsent(c.getParent().getId(), k -> new ArrayList<>()).add(c.getId());
            }
        }
        Set<Long> result = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>(List.of(rootId));
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            if (result.add(current)) {
                queue.addAll(children.getOrDefault(current, List.of()));
            }
        }
        return result;
    }

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    private CategoryResponse toResponse(ProductCategory c, long productCount) {
        return new CategoryResponse(c.getId(), c.getCode(), c.getName(), c.getLevel(),
                c.getParent() != null ? c.getParent().getId() : null, c.getDescription(), productCount);
    }

    private CategoryProductItem toItem(Product p) {
        ProductCategory c = p.getProductCategory();
        return new CategoryProductItem(p.getId(), p.getSku(), p.getName(), p.getBaseUnit(), p.getStatus(),
                c != null ? c.getId() : null, c != null ? c.getName() : null);
    }
}
