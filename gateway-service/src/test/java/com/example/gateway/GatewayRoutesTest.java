package com.example.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import org.springframework.cloud.gateway.server.mvc.config.RouteProperties;

// The test application.yml shadows the main one, so import the routes file explicitly.
@SpringBootTest(properties = "spring.config.import=classpath:gateway-routes.yml")
class GatewayRoutesTest {

	private final GatewayMvcProperties properties;

	@Autowired
	GatewayRoutesTest(GatewayMvcProperties properties) {
		this.properties = properties;
	}

	@Test
	void loadsThreeRoutesWithLoadBalancedUris() {
		Map<String, String> uris = properties.getRoutes().stream()
				.collect(Collectors.toMap(RouteProperties::getId, r -> r.getUri().toString()));

		assertThat(uris).containsOnly(
				Map.entry("order-service", "lb://order-service"),
				Map.entry("inventory-service", "lb://inventory-service"),
				Map.entry("payment-service", "lb://payment-service"));
	}

	@Test
	void inventoryRouteMatchesBothPaths() {
		RouteProperties inventory = properties.getRoutes().stream()
				.filter(r -> r.getId().equals("inventory-service")).findFirst().orElseThrow();

		assertThat(inventory.getPredicates().get(0).getArgs().values())
				.containsExactlyInAnyOrder("/api/v1/inventory/**", "/api/v1/inventory-items/**");
	}
}
