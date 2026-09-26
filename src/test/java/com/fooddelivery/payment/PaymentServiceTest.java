package com.fooddelivery.payment;

import com.fooddelivery.order.Order;
import com.fooddelivery.outbox.OutboxWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PaymentService never touches the gateway: it only records state (the gateway is called outside transactions). */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    PaymentRepository paymentRepository;
    @Mock
    OutboxWriter outboxWriter;
    @InjectMocks
    PaymentService service;

    private static Order order() {
        Order order = new Order();
        ReflectionTestUtils.setField(order, "id", 42L);
        order.setTotalAmount(new BigDecimal("438.99"));
        return order;
    }

    private Payment existing(PaymentStatus status) {
        Payment p = new Payment();
        ReflectionTestUtils.setField(p, "id", 7L);
        p.setStatus(status);
        when(paymentRepository.findByOrderId(42L)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void onlinePaymentStartsInitiatedAndCodPending() {
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.initiate(order(), PaymentMethod.UPI).getStatus()).isEqualTo(PaymentStatus.INITIATED);
        assertThat(service.initiate(order(), PaymentMethod.CASH_ON_DELIVERY).getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void capturedPaymentBecomesRefundPendingWithAnOutboxEvent() {
        Payment p = existing(PaymentStatus.SUCCESS);

        service.reverse(order());

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        verify(outboxWriter).paymentRefundRequested(7L);
    }

    @Test
    void codIsVoidedWithoutRefund() {
        Payment p = existing(PaymentStatus.PENDING);

        service.reverse(order());

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.VOIDED);
        verify(outboxWriter, never()).paymentRefundRequested(anyLong());
    }

    @Test
    void confirmAndFail() {
        Payment p = existing(PaymentStatus.INITIATED);
        service.confirm(order(), "ref_1");
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(p.getProviderRef()).isEqualTo("ref_1");

        p.setStatus(PaymentStatus.INITIATED);
        service.markFailed(order());
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }
}
