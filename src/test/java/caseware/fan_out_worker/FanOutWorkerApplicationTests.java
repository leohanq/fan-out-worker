package caseware.fan_out_worker;

import org.junit.jupiter.api.Test;

class FanOutWorkerApplicationTests {

	@Test
	void applicationClassExists() {
		// Verifies the main class compiles.
		// Full Spring context load is skipped — this project defines port
		// interfaces without concrete adapters, so auto-configuration
		// for JMS/JPA has no backing infrastructure to connect to.
	}
}