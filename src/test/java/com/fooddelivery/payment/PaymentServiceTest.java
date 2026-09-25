package com.fooddelivery.payment;

import com.fooddelivery.order.Order;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit test: simulates the surrounding transaction's synchronization callbacks by hand. */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    PaymentGateway gateway;
    @Mock
    PaymentRepository paymentRepository;
    @InjectMocks
    PaymentService service;

    Order order;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        order = new Order();
        order.setTotalAmount(new BigDecimal("438.99"));
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private static void completeTransaction(int status) {
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(status));
    }

    @Test
    void approvedChargeIsRefundedIfTransactionRollsBack() {
        when(gateway.charge(any())).thenReturn(ChargeResult.approved("ref_1"));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Payment payment = service.charge(order, PaymentMethod.CARD);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);

        completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(gateway).refund("ref_1", new BigDecimal("438.99"));
    }

    @Test
    void approvedChargeIsNotRefundedOnCommit() {
        when(gateway.charge(any())).thenReturn(ChargeResult.approved("ref_1"));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.charge(order, PaymentMethod.UPI);
        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);

        verify(gateway, never()).refund(any(), any());
    }

    @Test
    void declineThrowsAndRegistersNoRefund() {
        when(gateway.charge(any())).thenReturn(ChargeResult.declined("insufficient funds"));

        assertThatThrownBy(() -> service.charge(order, PaymentMethod.CARD))
                .isInstanceOf(PaymentDeclinedException.class);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void cashOnDeliverySkipsTheGateway() {
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Payment payment = service.charge(order, PaymentMethod.CASH_ON_DELIVERY);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verifyNoInteractions(gateway);
    }
}
