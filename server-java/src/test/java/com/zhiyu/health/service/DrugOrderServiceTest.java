package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.pharmacy.CampusPharmacy;
import com.zhiyu.health.entity.pharmacy.PharmacyAvailability;
import com.zhiyu.health.entity.pharmacy.PharmacyMedication;
import com.zhiyu.health.entity.pharmacy.PharmacyProfile;
import com.zhiyu.health.entity.prescription.DrugOrder;
import com.zhiyu.health.entity.prescription.DrugOrderFulfillmentEvent;
import com.zhiyu.health.entity.prescription.DrugOrderItem;
import com.zhiyu.health.entity.prescription.Medication;
import com.zhiyu.health.entity.prescription.Prescription;
import com.zhiyu.health.entity.prescription.PrescriptionItem;
import com.zhiyu.health.mapper.pharmacy.CampusPharmacyMapper;
import com.zhiyu.health.mapper.pharmacy.PharmacyMedicationMapper;
import com.zhiyu.health.mapper.prescription.DrugOrderFulfillmentEventMapper;
import com.zhiyu.health.mapper.prescription.DrugOrderItemMapper;
import com.zhiyu.health.mapper.prescription.DrugOrderMapper;
import com.zhiyu.health.mapper.prescription.MedicationMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionItemMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionMapper;
import com.zhiyu.health.service.prescription.DrugOrderService;
import com.zhiyu.health.service.prescription.MedCheckinService;
import com.zhiyu.health.support.TestContracts;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 药品订单 service 单测（票 88，ADR-0035）：整单原子扣减、取消/过期回补恰好一次、
 * 处方防重与支付核销、惰性过期收口、两条履约状态机、提醒交付触发与敏感字段脱敏。
 */
class DrugOrderServiceTest {

    private final DrugOrderMapper orderMapper = mock(DrugOrderMapper.class);
    private final DrugOrderItemMapper itemMapper = mock(DrugOrderItemMapper.class);
    private final DrugOrderFulfillmentEventMapper eventMapper = mock(DrugOrderFulfillmentEventMapper.class);
    private final PrescriptionMapper prescriptionMapper = mock(PrescriptionMapper.class);
    private final PrescriptionItemMapper prescriptionItemMapper = mock(PrescriptionItemMapper.class);
    private final PharmacyMedicationMapper pharmacyMedicationMapper = mock(PharmacyMedicationMapper.class);
    private final MedicationMapper medicationMapper = mock(MedicationMapper.class);
    private final com.zhiyu.health.service.pharmacy.CampusPharmacyService campusPharmacyService =
            mock(com.zhiyu.health.service.pharmacy.CampusPharmacyService.class);
    private final CampusPharmacyMapper campusPharmacyMapper = mock(CampusPharmacyMapper.class);
    private final MedCheckinService medCheckinService = mock(MedCheckinService.class);
    private final DrugOrderService service = new DrugOrderService(
            orderMapper,
            itemMapper,
            eventMapper,
            prescriptionMapper,
            prescriptionItemMapper,
            pharmacyMedicationMapper,
            medicationMapper,
            campusPharmacyService,
            campusPharmacyMapper,
            transactionTemplate(),
            TestContracts.instance(),
            medCheckinService);

    // ---------- 下单：处方药路径 ----------

    @Test
    void prescriptionOrderLocksSourceCampusPharmacyAndReusesQuantities() {
        stubPrescriptionCreateBase();
        PharmacyMedication pm = pharmacyMedication(101L, 1L, "18.50", 10);
        when(pharmacyMedicationMapper.selectForUpdateByPharmacyAndMedicationIds(71L, List.of(1L)))
                .thenReturn(List.of(pm));
        when(pharmacyMedicationMapper.deductStock(101L, 2)).thenReturn(1);
        stubInsertAssignsId(51L);

        DrugOrderService.OrderView view = service.create(new DrugOrderService.CreateCommand(
                7L,
                31L,
                null,
                null,
                "DELIVERY",
                new DrugOrderService.ReceiverInput("张三", "13812345678", "澜山市城东区梧桐路12号3栋")));

        assertThat(view.status()).isEqualTo("UNPAID");
        assertThat(view.source()).isEqualTo("PRESCRIPTION");
        assertThat(view.pharmacy().id()).isEqualTo(71L);
        // 金额快照：药品 18.50*2=37.00 + 配送费 5.00；处方数量 2 完全复用（患者不可改）
        assertThat(view.medicationAmount()).isEqualByComparingTo("37.00");
        assertThat(view.deliveryFee()).isEqualByComparingTo("5.00");
        assertThat(view.totalAmount()).isEqualByComparingTo("42.00");
        assertThat(view.remainingPaySeconds()).isGreaterThan(0L);
        ArgumentCaptor<DrugOrder> captor = ArgumentCaptor.forClass(DrugOrder.class);
        verify(orderMapper).insert(captor.capture());
        assertThat(captor.getValue().getPharmacyName()).isEqualTo("主院区药房");
        assertThat(captor.getValue().getCampusAddress()).isEqualTo("澜山市城东区梧桐路1号");
        verify(pharmacyMedicationMapper).deductStock(101L, 2);
        // 成交明细锁定药房药品关系快照
        ArgumentCaptor<DrugOrderItem> itemCaptor = ArgumentCaptor.forClass(DrugOrderItem.class);
        verify(itemMapper).insert(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getPharmacyMedicationId()).isEqualTo(101L);
        assertThat(itemCaptor.getValue().getQuantity()).isEqualTo(2);
    }

    @Test
    void pickupOrderForcesZeroFeeAndNullReceiver() {
        stubPrescriptionCreateBase();
        PharmacyMedication pm = pharmacyMedication(101L, 1L, "18.50", 10);
        when(pharmacyMedicationMapper.selectForUpdateByPharmacyAndMedicationIds(71L, List.of(1L)))
                .thenReturn(List.of(pm));
        when(pharmacyMedicationMapper.deductStock(101L, 2)).thenReturn(1);
        stubInsertAssignsId(51L);

        // 自取订单即使误传收货信息也不落库（ck_drug_orders_receiver_snapshot 兜底）
        DrugOrderService.OrderView view = service.create(new DrugOrderService.CreateCommand(
                7L, 31L, null, null, "PICKUP", new DrugOrderService.ReceiverInput("张三", "13812345678", "某地址")));

        assertThat(view.deliveryFee()).isEqualByComparingTo("0.00");
        ArgumentCaptor<DrugOrder> captor = ArgumentCaptor.forClass(DrugOrder.class);
        verify(orderMapper).insert(captor.capture());
        assertThat(captor.getValue().getReceiverName()).isNull();
        assertThat(captor.getValue().getReceiverPhone()).isNull();
    }

    @Test
    void deliveryOrderWithoutReceiverIsRejected() {
        DrugOrderService.CreateCommand command =
                new DrugOrderService.CreateCommand(7L, 31L, null, null, "DELIVERY", null);
        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("配送订单必须填写收货人");
        verify(orderMapper, never()).insert(any(DrugOrder.class));
    }

    @Test
    void redeemedPrescriptionCannotCreateOrder() {
        Prescription prescription = prescription("APPROVED");
        prescription.setRedeemedAt(OffsetDateTime.now());
        when(prescriptionMapper.selectForPatient(31L, 7L)).thenReturn(prescription);

        assertThatThrownBy(
                        () -> service.create(new DrugOrderService.CreateCommand(7L, 31L, null, null, "PICKUP", null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("处方已核销");
        verify(orderMapper, never()).insert(any(DrugOrder.class));
    }

    @Test
    void activeOrderBlocksDuplicatePrescriptionOrder() {
        when(prescriptionMapper.selectForPatient(31L, 7L)).thenReturn(prescription("APPROVED"));
        when(campusPharmacyService.requireByCampusId(11L)).thenReturn(pharmacy());
        when(orderMapper.countActiveByPrescription(31L, "CANCELLED", "EXPIRED")).thenReturn(1L);

        assertThatThrownBy(
                        () -> service.create(new DrugOrderService.CreateCommand(7L, 31L, null, null, "PICKUP", null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("该处方已有进行中的购药订单");
        verify(orderMapper, never()).insert(any(DrugOrder.class));
    }

    @Test
    void offSalePrescriptionMedicationRejectsOrder() {
        stubPrescriptionCreateBase();
        PharmacyMedication offSale = pharmacyMedication(101L, 1L, "18.50", 10);
        offSale.setIsOnSale(false);
        when(pharmacyMedicationMapper.selectForUpdateByPharmacyAndMedicationIds(71L, List.of(1L)))
                .thenReturn(List.of(offSale));

        assertThatThrownBy(
                        () -> service.create(new DrugOrderService.CreateCommand(7L, 31L, null, null, "PICKUP", null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("已下架");
        verify(pharmacyMedicationMapper, never()).deductStock(anyLong(), anyInt());
        verify(orderMapper, never()).insert(any(DrugOrder.class));
    }

    @Test
    void insufficientStockRejectsOrderWithoutCreatingIt() {
        stubPrescriptionCreateBase();
        when(pharmacyMedicationMapper.selectForUpdateByPharmacyAndMedicationIds(71L, List.of(1L)))
                .thenReturn(List.of(pharmacyMedication(101L, 1L, "18.50", 1)));
        when(pharmacyMedicationMapper.deductStock(101L, 2)).thenReturn(0);

        assertThatThrownBy(
                        () -> service.create(new DrugOrderService.CreateCommand(7L, 31L, null, null, "PICKUP", null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("药品库存不足");
        verify(orderMapper, never()).insert(any(DrugOrder.class));
    }

    // ---------- 下单：OTC 路径 ----------

    @Test
    void otcOrderRequiresExplicitPharmacyAndOtcDrugs() {
        stubOtcCatalog();
        when(campusPharmacyService.requireById(72L)).thenReturn(pharmacy());
        PharmacyMedication pm = pharmacyMedication(102L, 2L, "22.00", 10);
        when(pharmacyMedicationMapper.selectForUpdateByPharmacyAndMedicationIds(71L, List.of(2L)))
                .thenReturn(List.of(pm));
        when(pharmacyMedicationMapper.deductStock(102L, 3)).thenReturn(1);
        when(campusPharmacyMapper.selectProfileById(71L)).thenReturn(profile());
        stubInsertAssignsId(61L);

        DrugOrderService.OrderView view = service.create(new DrugOrderService.CreateCommand(
                7L, null, 72L, List.of(new DrugOrderService.QuantityInput(2L, 3)), "PICKUP", null));

        assertThat(view.source()).isEqualTo("OTC");
        assertThat(view.prescriptionId()).isNull();
        assertThat(view.totalAmount()).isEqualByComparingTo("66.00");
        verify(pharmacyMedicationMapper).deductStock(102L, 3);
        verify(prescriptionMapper, never()).selectForPatient(anyLong(), anyLong());
    }

    @Test
    void otcOrderWithoutPharmacyIsRejected() {
        assertThatThrownBy(() -> service.create(new DrugOrderService.CreateCommand(
                        7L, null, null, List.of(new DrugOrderService.QuantityInput(2L, 1)), "PICKUP", null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("OTC 下单必须指定履约药房");
    }

    @Test
    void otcOrderRejectsPrescriptionDrug() {
        Medication rx = medication(1L, "阿莫西林胶囊", true);
        when(medicationMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(rx));

        assertThatThrownBy(() -> service.create(new DrugOrderService.CreateCommand(
                        7L, null, 72L, List.of(new DrugOrderService.QuantityInput(1L, 1)), "PICKUP", null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("处方药须凭已审核电子处方购买");
        verify(orderMapper, never()).insert(any(DrugOrder.class));
    }

    // ---------- 支付 / 取消 / 惰性过期 ----------

    @Test
    void payMarksPaidAndRedeemsPrescriptionAtomically() {
        DrugOrder order = order(51L, "UNPAID", "DELIVERY");
        when(orderMapper.selectForPatientForUpdate(51L, 7L)).thenReturn(order);
        when(orderMapper.markPaid(51L, "PAID", "UNPAID")).thenReturn(1);
        when(prescriptionMapper.redeem(31L, 51L)).thenReturn(1);

        DrugOrderService.OrderView view = service.pay(7L, 51L);

        assertThat(view.status()).isEqualTo("PAID");
        assertThat(view.payable()).isFalse();
        verify(prescriptionMapper).redeem(31L, 51L);
    }

    @Test
    void payOnRedeemedPrescriptionIsRejected() {
        DrugOrder order = order(51L, "UNPAID", "DELIVERY");
        when(orderMapper.selectForPatientForUpdate(51L, 7L)).thenReturn(order);
        when(orderMapper.markPaid(51L, "PAID", "UNPAID")).thenReturn(1);
        when(prescriptionMapper.redeem(31L, 51L)).thenReturn(0);

        assertThatThrownBy(() -> service.pay(7L, 51L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("处方已核销");
    }

    @Test
    void payOnOverdueOrderExpiresItAndRestoresStock() {
        DrugOrder order = order(51L, "UNPAID", "DELIVERY");
        order.setPaymentDeadline(OffsetDateTime.now().minusSeconds(1));
        when(orderMapper.selectForPatientForUpdate(51L, 7L)).thenReturn(order);
        when(orderMapper.expire(51L, "EXPIRED", "UNPAID")).thenReturn(1);
        DrugOrderItem item = orderItem(51L, 101L, 2);
        when(itemMapper.selectDetailed(51L)).thenReturn(List.of(item));
        when(pharmacyMedicationMapper.restoreStock(101L, 2)).thenReturn(1);

        assertThatThrownBy(() -> service.pay(7L, 51L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("订单已过支付截止");
        verify(orderMapper).expire(51L, "EXPIRED", "UNPAID");
        verify(pharmacyMedicationMapper).restoreStock(101L, 2);
    }

    @Test
    void cancelRestoresStockExactlyOnce() {
        DrugOrder order = order(51L, "UNPAID", "PICKUP");
        when(orderMapper.selectForPatientForUpdate(51L, 7L)).thenReturn(order);
        when(orderMapper.cancel(51L, "CANCELLED", "UNPAID")).thenReturn(1);
        when(itemMapper.selectDetailed(51L)).thenReturn(List.of(orderItem(51L, 101L, 2)));
        when(pharmacyMedicationMapper.restoreStock(101L, 2)).thenReturn(1);

        DrugOrderService.OrderView view = service.cancel(7L, 51L);

        assertThat(view.status()).isEqualTo("CANCELLED");
        verify(pharmacyMedicationMapper).restoreStock(101L, 2);
    }

    @Test
    void cancelRejectsPaidOrder() {
        DrugOrder order = order(51L, "PAID", "PICKUP");
        when(orderMapper.selectForPatientForUpdate(51L, 7L)).thenReturn(order);

        assertThatThrownBy(() -> service.cancel(7L, 51L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("仅待支付药品订单可取消");
        verify(pharmacyMedicationMapper, never()).restoreStock(anyLong(), anyInt());
    }

    @Test
    void listLazilyExpiresOverdueOrdersAndRestoresStock() {
        DrugOrder overdue = order(51L, "UNPAID", "PICKUP");
        overdue.setPaymentDeadline(OffsetDateTime.now().minusSeconds(30));
        when(orderMapper.selectOverdueUnpaidForUpdate(7L, "UNPAID")).thenReturn(List.of(overdue));
        when(orderMapper.expire(51L, "EXPIRED", "UNPAID")).thenReturn(1);
        when(itemMapper.selectDetailed(51L)).thenReturn(List.of(orderItem(51L, 101L, 2)));
        when(pharmacyMedicationMapper.restoreStock(101L, 2)).thenReturn(1);
        when(orderMapper.selectForPatient(7L)).thenReturn(List.of());

        List<DrugOrderService.OrderView> views = service.listForPatient(7L);

        assertThat(views).isEmpty();
        verify(orderMapper).expire(51L, "EXPIRED", "UNPAID");
        verify(pharmacyMedicationMapper).restoreStock(101L, 2);
    }

    // ---------- 履约状态机 ----------

    @Test
    void adminCancelRestoresStockExactlyOnce() {
        DrugOrder order = order(51L, "UNPAID", "PICKUP");
        when(orderMapper.selectForUpdate(51L)).thenReturn(order);
        when(orderMapper.cancel(51L, "CANCELLED", "UNPAID")).thenReturn(1);
        when(itemMapper.selectDetailed(51L)).thenReturn(List.of(orderItem(51L, 101L, 2)));
        when(pharmacyMedicationMapper.restoreStock(101L, 2)).thenReturn(1);
        DrugOrder cancelled = order(51L, "CANCELLED", "PICKUP");
        when(orderMapper.selectDetailedForAdmin(51L)).thenReturn(cancelled);

        DrugOrderService.OrderView view = service.cancelForAdmin(51L);

        assertThat(view.status()).isEqualTo("CANCELLED");
        verify(pharmacyMedicationMapper).restoreStock(101L, 2);
    }

    @Test
    void adminCancelRejectsPaidOrder() {
        DrugOrder order = order(51L, "PAID", "PICKUP");
        when(orderMapper.selectForUpdate(51L)).thenReturn(order);

        assertThatThrownBy(() -> service.cancelForAdmin(51L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("仅待支付药品订单可取消");
        verify(pharmacyMedicationMapper, never()).restoreStock(anyLong(), anyInt());
    }

    @Test
    void dispenseAdvancesPaidOrderAndWritesEvent() {
        DrugOrder order = order(51L, "PAID", "DELIVERY");
        when(orderMapper.selectForUpdate(51L)).thenReturn(order);
        when(orderMapper.markDispensing(51L, "DISPENSING", "PAID")).thenReturn(1);
        DrugOrder dispensed = order(51L, "DISPENSING", "DELIVERY");
        dispensed.setDispensingAt(OffsetDateTime.now());
        when(orderMapper.selectDetailedForAdmin(51L)).thenReturn(dispensed);

        DrugOrderService.OrderView view = service.fulfill(9L, 51L, "DISPENSE");

        assertThat(view.status()).isEqualTo("DISPENSING");
        ArgumentCaptor<DrugOrderFulfillmentEvent> captor = ArgumentCaptor.forClass(DrugOrderFulfillmentEvent.class);
        verify(eventMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("DISPENSING");
        assertThat(captor.getValue().getStaffId()).isEqualTo(9L);
    }

    @Test
    void shipGeneratesSimulatedCarrierAndUniqueTrackingNo() {
        DrugOrder order = order(51L, "DISPENSING", "DELIVERY");
        when(orderMapper.selectForUpdate(51L)).thenReturn(order);
        when(orderMapper.markShipped(
                        eq(51L), eq("SHIPPED"), eq("DISPENSING"), eq("DELIVERY"), eq("智愈模拟配送"), eq("ZY0000000051")))
                .thenReturn(1);
        DrugOrder shipped = order(51L, "SHIPPED", "DELIVERY");
        shipped.setCarrierName("智愈模拟配送");
        shipped.setTrackingNo("ZY0000000051");
        when(orderMapper.selectDetailedForAdmin(51L)).thenReturn(shipped);

        DrugOrderService.OrderView view = service.fulfill(9L, 51L, "SHIP");

        assertThat(view.carrierName()).isEqualTo("智愈模拟配送");
        assertThat(view.trackingNo()).isEqualTo("ZY0000000051");
    }

    @Test
    void shipOnPickupOrderIsRejectedAsIllegalTransition() {
        // 自取单不得进入配送路径：条件 UPDATE 带 pickup_method 限定，0 行即 409
        DrugOrder order = order(51L, "DISPENSING", "PICKUP");
        when(orderMapper.selectForUpdate(51L)).thenReturn(order);
        when(orderMapper.markShipped(eq(51L), eq("SHIPPED"), eq("DISPENSING"), eq("DELIVERY"), any(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.fulfill(9L, 51L, "SHIP"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("订单当前状态不允许该履约动作");
        verify(eventMapper, never()).insert(any(DrugOrderFulfillmentEvent.class));
    }

    @Test
    void deliveredPrescriptionOrderTriggersCheckinGeneration() {
        DrugOrder order = order(51L, "SHIPPED", "DELIVERY");
        when(orderMapper.selectForUpdate(51L)).thenReturn(order);
        when(orderMapper.markDelivered(51L, "DELIVERED", "SHIPPED")).thenReturn(1);
        when(orderMapper.selectDetailedForAdmin(51L)).thenReturn(order(51L, "DELIVERED", "DELIVERY"));

        service.fulfill(9L, 51L, "DELIVER");

        verify(medCheckinService).generateForDeliveredOrder(order);
    }

    @Test
    void pickedUpPrescriptionOrderTriggersCheckinGeneration() {
        DrugOrder order = order(51L, "READY_FOR_PICKUP", "PICKUP");
        when(orderMapper.selectForUpdate(51L)).thenReturn(order);
        when(orderMapper.markPickedUp(51L, "PICKED_UP", "READY_FOR_PICKUP")).thenReturn(1);
        when(orderMapper.selectDetailedForAdmin(51L)).thenReturn(order(51L, "PICKED_UP", "PICKUP"));

        service.fulfill(9L, 51L, "PICKUP");

        verify(medCheckinService).generateForDeliveredOrder(order);
    }

    @Test
    void deliveredOtcOrderNeverTriggersCheckinGeneration() {
        DrugOrder order = order(51L, "SHIPPED", "DELIVERY");
        order.setPrescriptionId(null);
        when(orderMapper.selectForUpdate(51L)).thenReturn(order);
        when(orderMapper.markDelivered(51L, "DELIVERED", "SHIPPED")).thenReturn(1);
        when(orderMapper.selectDetailedForAdmin(51L)).thenReturn(order(51L, "DELIVERED", "DELIVERY"));

        service.fulfill(9L, 51L, "DELIVER");

        verify(medCheckinService, never()).generateForDeliveredOrder(any(DrugOrder.class));
    }

    @Test
    void illegalFulfillmentDecisionIsRejected() {
        DrugOrder order = order(51L, "PAID", "DELIVERY");
        when(orderMapper.selectForUpdate(51L)).thenReturn(order);

        assertThatThrownBy(() -> service.fulfill(9L, 51L, "DELIVER"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("订单当前状态不允许该履约动作");
        assertThatThrownBy(() -> service.fulfill(9L, 51L, "PAY"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("履约动作无效");
    }

    // ---------- 脱敏 ----------

    @Test
    void patientViewMasksReceiverPhoneAndAddress() {
        DrugOrder order = order(51L, "PAID", "DELIVERY");
        order.setReceiverName("张三");
        order.setReceiverPhone("13812345678");
        order.setReceiverAddress("澜山市城东区梧桐路12号3栋");
        when(orderMapper.selectOverdueUnpaidForUpdate(7L, "UNPAID")).thenReturn(List.of());
        when(orderMapper.selectById(51L)).thenReturn(order);
        when(itemMapper.selectDetailed(51L)).thenReturn(List.of());

        DrugOrderService.OrderView view = service.detailForPatient(7L, 51L);

        assertThat(view.receiverName()).isEqualTo("张三");
        assertThat(view.receiverPhone()).isEqualTo("138****5678");
        assertThat(view.receiverAddress()).isEqualTo("澜山市城东区****");
    }

    @Test
    void adminViewKeepsReceiverPlaintext() {
        DrugOrder order = order(51L, "PAID", "DELIVERY");
        order.setReceiverName("张三");
        order.setReceiverPhone("13812345678");
        order.setReceiverAddress("澜山市城东区梧桐路12号3栋");
        when(orderMapper.selectOverdueUnpaidForUpdate(null, "UNPAID")).thenReturn(List.of());
        when(orderMapper.selectDetailedForAdmin(51L)).thenReturn(order);
        when(itemMapper.selectDetailed(51L)).thenReturn(List.of());

        DrugOrderService.OrderView view = service.detailForAdmin(51L);

        assertThat(view.receiverPhone()).isEqualTo("13812345678");
        assertThat(view.receiverAddress()).isEqualTo("澜山市城东区梧桐路12号3栋");
    }

    // ---------- 购药查询 ----------

    @Test
    void previewReturnsLockedPharmacyWithLivePricing() {
        Prescription prescription = prescription("APPROVED");
        prescription.setDoctorName("林医生");
        when(prescriptionMapper.selectDetailedForPatient(31L, 7L)).thenReturn(prescription);
        when(orderMapper.countActiveByPrescription(31L, "CANCELLED", "EXPIRED")).thenReturn(0L);
        when(campusPharmacyMapper.selectProfileByCampusId(11L)).thenReturn(profile());
        PrescriptionItem item = prescriptionItem(1L, 2);
        when(prescriptionItemMapper.selectDetailed(31L)).thenReturn(List.of(item));
        PharmacyMedication pm = pharmacyMedication(101L, 1L, "18.50", 10);
        when(pharmacyMedicationMapper.selectDetailedByPharmacy(71L, null)).thenReturn(List.of(pm));

        DrugOrderService.PrescriptionPreviewView view = service.previewPrescription(7L, 31L);

        assertThat(view.pharmacy().id()).isEqualTo(71L);
        assertThat(view.pharmacy().displayName()).isEqualTo("主院区药房");
        assertThat(view.doctorName()).isEqualTo("林医生");
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).quantity()).isEqualTo(2);
        assertThat(view.items().get(0).available()).isTrue();
        assertThat(view.medicationAmount()).isEqualByComparingTo("37.00");
    }

    @Test
    void previewRejectsRedeemedPrescription() {
        Prescription prescription = prescription("APPROVED");
        prescription.setRedeemedAt(OffsetDateTime.now());
        when(prescriptionMapper.selectDetailedForPatient(31L, 7L)).thenReturn(prescription);

        assertThatThrownBy(() -> service.previewPrescription(7L, 31L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("处方已核销");
    }

    @Test
    void otcCandidatesOnlyKeepFullyFulfillablePharmacies() {
        stubOtcCatalog();
        PharmacyAvailability full = availability(71L, 2L, "22.00", 10);
        // 药房 71 库存足（10>=3），药房 72 库存不足（1<3）被整体过滤
        PharmacyAvailability shortStock = availability(72L, 2L, "23.00", 1);
        when(pharmacyMedicationMapper.selectOtcAvailability(List.of(2L))).thenReturn(List.of(full, shortStock));

        DrugOrderService.OtcCandidatesView view =
                service.otcCandidates(List.of(new DrugOrderService.QuantityInput(2L, 3)), null, null);

        assertThat(view.items()).hasSize(1);
        assertThat(view.pharmacies()).hasSize(1);
        assertThat(view.pharmacies().get(0).pharmacyId()).isEqualTo(71L);
        assertThat(view.pharmacies().get(0).medicationAmount()).isEqualByComparingTo("66.00");
        // 无定位：不出 distance_meters（视图字段为 null，序列化 NON_NULL 抑制）
        assertThat(view.pharmacies().get(0).distanceMeters()).isNull();
    }

    @Test
    void otcCandidatesSortByRealDistanceWhenLocated() {
        stubOtcCatalog();
        // SQL 稳定序先远后近；有定位时按真实坐标距离升序重排
        PharmacyAvailability far = availability(71L, 2L, "22.00", 10);
        far.setCampusLongitude(121.0);
        far.setCampusLatitude(31.0);
        PharmacyAvailability near = availability(72L, 2L, "23.00", 10);
        near.setCampusLongitude(120.151);
        near.setCampusLatitude(30.271);
        when(pharmacyMedicationMapper.selectOtcAvailability(List.of(2L))).thenReturn(List.of(far, near));

        DrugOrderService.OtcCandidatesView view =
                service.otcCandidates(List.of(new DrugOrderService.QuantityInput(2L, 3)), 120.15, 30.27);

        assertThat(view.pharmacies()).hasSize(2);
        assertThat(view.pharmacies().get(0).pharmacyId()).isEqualTo(72L);
        assertThat(view.pharmacies().get(0).distanceMeters()).isNotNull();
        assertThat(view.pharmacies().get(1).distanceMeters())
                .isGreaterThan(view.pharmacies().get(0).distanceMeters());
    }

    @Test
    void validateOtcItemsRejectsDuplicatesAndInvalidQuantity() {
        assertThatThrownBy(() -> service.validateOtcItems(List.of()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("OTC 下单必须指定药品与数量");
        assertThatThrownBy(() -> service.validateOtcItems(List.of(new DrugOrderService.QuantityInput(2L, 0))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("OTC 下单数量必须为正整数");
        assertThatThrownBy(() -> service.validateOtcItems(
                        List.of(new DrugOrderService.QuantityInput(2L, 1), new DrugOrderService.QuantityInput(2L, 2))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("同一药品重复下单");
    }

    // ---------- 测试基建 ----------

    private void stubPrescriptionCreateBase() {
        when(prescriptionMapper.selectForPatient(31L, 7L)).thenReturn(prescription("APPROVED"));
        when(campusPharmacyService.requireByCampusId(11L)).thenReturn(pharmacy());
        when(orderMapper.countActiveByPrescription(31L, "CANCELLED", "EXPIRED")).thenReturn(0L);
        when(prescriptionItemMapper.selectDetailed(31L)).thenReturn(List.of(prescriptionItem(1L, 2)));
        when(campusPharmacyMapper.selectProfileById(71L)).thenReturn(profile());
    }

    private void stubOtcCatalog() {
        when(medicationMapper.selectBatchIds(List.of(2L))).thenReturn(List.of(medication(2L, "布洛芬缓释胶囊", false)));
    }

    private void stubInsertAssignsId(long id) {
        // insert 回填 id；回读 selectById 返回同一实体并补 DB 默认列 created_at
        // （真实库由 DB 生成默认列，MP 不回填，service 落库后回读成行视图）。
        doAnswer(invocation -> {
                    DrugOrder inserted = invocation.getArgument(0, DrugOrder.class);
                    inserted.setId(id);
                    inserted.setCreatedAt(OffsetDateTime.now());
                    insertedOrder = inserted;
                    return 1;
                })
                .when(orderMapper)
                .insert(any(DrugOrder.class));
        when(orderMapper.selectById(id)).thenAnswer(invocation -> insertedOrder);
        when(itemMapper.selectDetailed(id)).thenReturn(List.of());
    }

    private DrugOrder insertedOrder;

    private CampusPharmacy pharmacy() {
        CampusPharmacy pharmacy = new CampusPharmacy();
        pharmacy.setId(71L);
        pharmacy.setCampusId(11L);
        pharmacy.setDisplayName("主院区药房");
        pharmacy.setDeliveryFee(new BigDecimal("5.00"));
        pharmacy.setEstimatedDeliveryMinutes(45);
        return pharmacy;
    }

    private PharmacyProfile profile() {
        PharmacyProfile profile = new PharmacyProfile();
        profile.setPharmacyId(71L);
        profile.setCampusId(11L);
        profile.setDisplayName("主院区药房");
        profile.setDeliveryFee(new BigDecimal("5.00"));
        profile.setEstimatedDeliveryMinutes(45);
        profile.setHospitalName("云澜医院");
        profile.setCampusName("主院区");
        profile.setCampusAddress("澜山市城东区梧桐路1号");
        profile.setCityName("澜山市");
        return profile;
    }

    private PharmacyMedication pharmacyMedication(long id, long medicationId, String price, int stock) {
        PharmacyMedication pm = new PharmacyMedication();
        pm.setId(id);
        pm.setPharmacyId(71L);
        pm.setMedicationId(medicationId);
        pm.setPrice(new BigDecimal(price));
        pm.setStock(stock);
        pm.setIsOnSale(true);
        pm.setMedicationName("阿莫西林胶囊");
        pm.setSpecification("0.25g*24粒");
        return pm;
    }

    private PharmacyAvailability availability(long pharmacyId, long medicationId, String price, int stock) {
        PharmacyAvailability row = new PharmacyAvailability();
        row.setPharmacyId(pharmacyId);
        row.setPharmacyDisplayName("药房" + pharmacyId);
        row.setDeliveryFee(new BigDecimal("5.00"));
        row.setEstimatedDeliveryMinutes(45);
        row.setHospitalName("云澜医院");
        row.setCampusName("院区" + pharmacyId);
        row.setCampusAddress("地址" + pharmacyId);
        row.setCityName("澜山市");
        row.setMedicationId(medicationId);
        row.setPrice(new BigDecimal(price));
        row.setStock(stock);
        return row;
    }

    private Medication medication(long id, String name, boolean isPrescription) {
        Medication medication = new Medication();
        medication.setId(id);
        medication.setName(name);
        medication.setSpecification("0.25g*24粒");
        medication.setIsPrescription(isPrescription);
        return medication;
    }

    private Prescription prescription(String status) {
        Prescription prescription = new Prescription();
        prescription.setId(31L);
        prescription.setStatus(status);
        prescription.setSourceCampusId(11L);
        return prescription;
    }

    private PrescriptionItem prescriptionItem(long medicationId, int quantity) {
        PrescriptionItem item = new PrescriptionItem();
        item.setId(901L);
        item.setPrescriptionId(31L);
        item.setMedicationId(medicationId);
        item.setMedicationName("阿莫西林胶囊");
        item.setSpecification("0.25g*24粒");
        item.setQuantity(quantity);
        return item;
    }

    private DrugOrderItem orderItem(long orderId, long pharmacyMedicationId, int quantity) {
        DrugOrderItem item = new DrugOrderItem();
        item.setDrugOrderId(orderId);
        item.setMedicationId(1L);
        item.setPharmacyMedicationId(pharmacyMedicationId);
        item.setMedicationName("阿莫西林胶囊");
        item.setSpecification("0.25g*24粒");
        item.setQuantity(quantity);
        item.setUnitPrice(new BigDecimal("18.50"));
        item.setSubtotal(new BigDecimal("37.00"));
        return item;
    }

    private DrugOrder order(long id, String status, String pickupMethod) {
        DrugOrder order = new DrugOrder();
        order.setId(id);
        order.setPatientId(7L);
        order.setPrescriptionId(31L);
        order.setPharmacyId(71L);
        order.setPickupMethod(pickupMethod);
        order.setStatus(status);
        order.setMedicationAmount(new BigDecimal("37.00"));
        order.setDeliveryFee(new BigDecimal("5.00"));
        order.setTotalAmount(new BigDecimal("42.00"));
        order.setPharmacyName("主院区药房");
        order.setHospitalName("云澜医院");
        order.setCampusName("主院区");
        order.setCampusAddress("澜山市城东区梧桐路1号");
        order.setPaymentDeadline(OffsetDateTime.now().plusSeconds(600));
        order.setCreatedAt(OffsetDateTime.now());
        return order;
    }

    private static TransactionTemplate transactionTemplate() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(invocation -> invocation
                .getArgument(0, TransactionCallback.class)
                .doInTransaction(mock(TransactionStatus.class)));
        return template;
    }
}
