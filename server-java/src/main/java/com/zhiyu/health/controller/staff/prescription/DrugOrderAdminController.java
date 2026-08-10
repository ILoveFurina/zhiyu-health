package com.zhiyu.health.controller.staff.prescription;

import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.service.prescription.DrugOrderService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * B 端药品订单（票 88，ADR-0035）：admin/pharmacist 可操作（路由级角色授权）。
 * 列表按状态/取药方式过滤分页；明细返回履约必要的明文收货信息与状态时间线；
 * 履约推进只暴露合法下一步动作（条件更新裁决，非法跳转 409），不提供任意状态切换。
 */
@Validated
@RestController
@RequestMapping("/api/b/drug-orders")
@RequiredArgsConstructor
public class DrugOrderAdminController {
    private final DrugOrderService service;

    /** 履约动作：DISPENSE/SHIP/DELIVER（配送路径）或 DISPENSE/READY/PICKUP（自取路径）。 */
    public record FulfillmentInput(@NotBlank String decision) {}

    @GetMapping
    public DrugOrderService.AdminOrderPage list(
            @RequestParam(required = false) String status,
            @RequestParam(value = "pickup_method", required = false) String pickupMethod,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.listForAdmin(status, pickupMethod, page, size);
    }

    @GetMapping("/{id}")
    public DrugOrderService.OrderView detail(@PathVariable long id) {
        return service.detailForAdmin(id);
    }

    /** B 端取消（admin/pharmacist 代客取消）：与 C 端同一规则，仅 UNPAID，回补库存同事务。 */
    @PostMapping("/{id}/cancel")
    public DrugOrderService.OrderView cancel(@PathVariable long id) {
        return service.cancelForAdmin(id);
    }

    /** 模拟履约推进：条件更新 + 状态时间戳 + append-only 事件同事务；staffId 取自已鉴权员工。 */
    @PostMapping("/{id}/fulfillment")
    public DrugOrderService.OrderView fulfill(
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId,
            @PathVariable long id,
            @Validated @RequestBody FulfillmentInput input) {
        return service.fulfill(staffId, id, input.decision());
    }
}
