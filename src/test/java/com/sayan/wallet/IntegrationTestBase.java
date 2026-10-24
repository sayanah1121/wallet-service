package com.sayan.wallet;

import com.sayan.wallet.transaction.TransferEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

abstract class IntegrationTestBase extends IntegrationTestSupport {

    // test events must never reach the real Kafka topics
    @MockitoBean
    protected TransferEventPublisher events;
}
