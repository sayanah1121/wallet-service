package com.sayan.wallet;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// the test profile keeps Kafka listeners stopped, so this context never competes with
// EventPipelineTest for partitions or writes into the dev database
@SpringBootTest
@ActiveProfiles("test")
class WalletApplicationTests {

	@Test
	void contextLoads() {
	}

}
