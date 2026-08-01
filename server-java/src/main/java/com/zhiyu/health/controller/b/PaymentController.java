package com.zhiyu.health.controller.b;

import com.zhiyu.health.service.PaymentService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/b/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService service;

    @GetMapping
    public List<PaymentService.PaymentView> list(@RequestParam(required = false) String status) {
        return service.listForAdmin(status);
    }

    @GetMapping("/{id}")
    public PaymentService.PaymentView detail(@PathVariable long id) {
        return service.getForAdmin(id);
    }

    @PostMapping("/{id}/pay")
    public PaymentService.PaymentView pay(@PathVariable long id) {
        return service.payForAdmin(id);
    }
}
