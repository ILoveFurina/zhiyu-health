package com.zhiyu.health.controller.staff.prescription;

import com.zhiyu.health.service.prescription.DrugOrderService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/b/drug-orders")
@RequiredArgsConstructor
public class DrugOrderAdminController {
    private final DrugOrderService service;

    @GetMapping
    public List<DrugOrderService.OrderView> list(@RequestParam(required = false) String status) {
        return service.listForAdmin(status);
    }

    @GetMapping("/{id}")
    public DrugOrderService.OrderView detail(@PathVariable long id) {
        return service.getForAdmin(id);
    }

    @PostMapping("/{id}/complete")
    public DrugOrderService.OrderView complete(@PathVariable long id) {
        return service.completeForAdmin(id);
    }

    @PostMapping("/{id}/cancel")
    public DrugOrderService.OrderView cancel(@PathVariable long id) {
        return service.cancelForAdmin(id);
    }
}
