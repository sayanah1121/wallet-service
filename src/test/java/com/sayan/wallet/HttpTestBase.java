package com.sayan.wallet;

import org.springframework.boot.test.context.SpringBootTest;

// Boots the real servlet stack (security filters, rate limiter, validation) on a random port.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class HttpTestBase extends IntegrationTestBase {
}
